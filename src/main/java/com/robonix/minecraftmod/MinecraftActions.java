// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/** Executes the confirmed inventory and item-action primitive capabilities. */
public final class MinecraftActions implements AutoCloseable {
    private static final long ACTION_TIMEOUT_NANOS = 25_000_000_000L;
    private static final ResourceLocation DIRT = ResourceLocation.fromNamespaceAndPath("minecraft", "dirt");
    private static final ResourceLocation CRAFTING_TABLE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "crafting_table");
    private static final Set<ResourceLocation> BREAKABLE_BLOCKS = Set.of(DIRT, CRAFTING_TABLE);
    private static final Set<ResourceLocation> DEPOSITABLE_ITEMS = BREAKABLE_BLOCKS;
    private final MinecraftControl control;
    private ActiveAction activeAction;

    public MinecraftActions(MinecraftControl control) {
        this.control = control;
    }

    /** Reads all stable main-inventory slots plus offhand on the client thread. */
    public CompletableFuture<JsonObject> inventory() {
        return onClientThread(() -> inventoryResult(requirePlayer(Minecraft.getInstance())));
    }

    /** Validates and begins normal survival-mode destruction of one supported block. */
    public CompletableFuture<JsonObject> breakBlock(
            String expectedBlock,
            boolean collect
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requireSurvivalPlayer(minecraft);
                BlockHitResult hit = crosshairTarget(minecraft, player);
                BlockPos target = hit.getBlockPos();
                BlockState state = minecraft.level.getBlockState(target);
                String originalBlock = blockName(state);
                ResourceLocation collectedItem = ResourceLocation.tryParse(expectedBlock);
                if (!BREAKABLE_BLOCKS.contains(collectedItem)) {
                    throw new IllegalArgumentException(
                            "expected_block must be minecraft:dirt or minecraft:crafting_table");
                }
                if (!originalBlock.equals(expectedBlock)) {
                    throw new IllegalStateException("target block is " + originalBlock + ", not " + expectedBlock);
                }
                if (minecraft.screen != null) {
                    throw new IllegalStateException("close the current screen before breaking a block");
                }
                control.stopForAction();
                int countBefore = inventoryCount(player, collectedItem);
                long deadline = System.nanoTime() + ACTION_TIMEOUT_NANOS;
                activeAction = new ActiveBreak(
                        player,
                        target,
                        originalBlock,
                        collectedItem,
                        collect,
                        countBefore,
                        minecraft.options.keyAttack.isDown(),
                        minecraft.mouseHandler.isMouseGrabbed(),
                        minecraft.options.keyUp.isDown(),
                        deadline,
                        result);
                minecraft.mouseHandler.mouseGrabbed = true;
                minecraft.options.keyAttack.setDown(true);
                if (!minecraft.gameMode.startDestroyBlock(target, hit.getDirection())) {
                    finishActive(new IllegalStateException("Minecraft rejected block destruction"));
                }
            } catch (Exception exception) {
                if (activeAction != null && activeAction.result() == result) {
                    finishActive(exception);
                } else {
                    result.completeExceptionally(exception);
                }
            }
        });
        return result;
    }

    /** Validates and begins a normal chest interaction that transfers one supported item. */
    public CompletableFuture<JsonObject> depositItem(
            int x,
            int y,
            int z,
            String itemName,
            long count
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requireSurvivalPlayer(minecraft);
                ResourceLocation depositedItem = ResourceLocation.tryParse(itemName);
                if (!DEPOSITABLE_ITEMS.contains(depositedItem)) {
                    throw new IllegalArgumentException(
                            "item must be minecraft:dirt or minecraft:crafting_table");
                }
                if (count != 1L) {
                    throw new IllegalArgumentException("count must be exactly 1");
                }
                BlockPos target = new BlockPos(x, y, z);
                BlockHitResult hit = validateTarget(minecraft, player, target);
                BlockState state = minecraft.level.getBlockState(target);
                if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) {
                    throw new IllegalStateException("target block is not a chest: " + blockName(state));
                }
                int playerBefore = inventoryCount(player, depositedItem);
                if (mainInventoryCount(player, depositedItem) < 1) {
                    throw new IllegalStateException("player main inventory contains no " + depositedItem);
                }
                control.stopForAction();
                if (!minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                    throw new IllegalStateException("Minecraft rejected the chest interaction");
                }
                activeAction = new ActiveDeposit(
                        player,
                        target,
                        player.inventoryMenu,
                        depositedItem,
                        playerBefore,
                        System.nanoTime() + ACTION_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Advances the active item action and rejects disconnect, death, and timeouts. */
    public void onClientTick(TickEvent.ClientTickEvent.Post event) {
        ActiveAction action = activeAction;
        if (action == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            LocalPlayer player = requirePlayer(minecraft);
            if (player != action.player()) {
                throw new IllegalStateException("item action interrupted by a player connection change");
            }
            if (!player.isAlive()) {
                throw new IllegalStateException("item action interrupted by player death");
            }
            if (System.nanoTime() >= action.deadlineNanos()) {
                throw new TimeoutException("item action did not complete before timeout");
            }
            if (action instanceof ActiveBreak breaking) {
                tickBreak(minecraft, player, breaking);
            } else if (action instanceof ActiveDeposit deposit) {
                tickDeposit(minecraft, player, deposit);
            }
        } catch (Exception exception) {
            finishActive(exception);
        }
    }

    /** Keeps native attack input held until the world changes, then optionally awaits pickup. */
    private void tickBreak(Minecraft minecraft, LocalPlayer player, ActiveBreak action) {
        if (!action.blockBroken() && !minecraft.level.getBlockState(action.target()).isAir()) {
            validateTarget(minecraft, player, action.target());
            minecraft.mouseHandler.mouseGrabbed = true;
            minecraft.options.keyAttack.setDown(true);
            return;
        }
        if (!action.blockBroken()) {
            action.markBlockBroken();
            stopBreakingInput(minecraft, action);
        }
        int collected = inventoryCount(player, action.collectedItem()) - action.countBefore();
        if (action.collect() && collected < 1) {
            walkTowardBrokenBlock(minecraft, player, action);
            return;
        }
        minecraft.options.keyUp.setDown(action.forwardWasDown());
        JsonObject response = new JsonObject();
        response.addProperty("block_broken", true);
        response.addProperty("original_block", action.originalBlock());
        response.addProperty("collected_item", collected > 0 ? action.collectedItem().toString() : "");
        response.addProperty("collected_count", Math.max(0, collected));
        finishActive(response);
    }

    /** Walk toward the former block centre so normal item attraction can collect its drop. */
    private static void walkTowardBrokenBlock(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveBreak action
    ) {
        double dx = action.target().getX() + 0.5 - player.getX();
        double dz = action.target().getZ() + 0.5 - player.getZ();
        if (Math.hypot(dx, dz) <= 0.6) {
            minecraft.options.keyUp.setDown(action.forwardWasDown());
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        minecraft.options.keyUp.setDown(true);
    }

    /** Stop native destruction immediately while retaining the action for pickup verification. */
    private static void stopBreakingInput(Minecraft minecraft, ActiveBreak action) {
        if (action.attackReleased()) {
            return;
        }
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        minecraft.options.keyAttack.setDown(action.attackWasDown());
        minecraft.mouseHandler.mouseGrabbed = action.mouseWasGrabbed();
        action.markAttackReleased();
    }

    /** Opens the server menu, performs tick-separated clicks, and verifies stable counts. */
    private void tickDeposit(Minecraft minecraft, LocalPlayer player, ActiveDeposit action) {
        validateTarget(minecraft, player, action.target());
        BlockState targetState = minecraft.level.getBlockState(action.target());
        if (!targetState.is(Blocks.CHEST) && !targetState.is(Blocks.TRAPPED_CHEST)) {
            throw new IllegalStateException("target chest was removed or replaced");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (action.phase() == DepositPhase.WAIT_MENU) {
            if (menu == action.inventoryMenu()) {
                return;
            }
            if (!(minecraft.screen instanceof AbstractContainerScreen<?>) || !(menu instanceof ChestMenu)) {
                throw new IllegalStateException("another menu replaced the requested chest interaction");
            }
            int containerSlots = menu.slots.size() - 36;
            if (containerSlots <= 0) {
                throw new IllegalStateException("opened menu has no container slots");
            }
            int containerBefore = menuItemCount(menu, 0, containerSlots, action.item());
            int sourceSlot = findPlayerItemSlot(menu, containerSlots, action.item());
            int targetSlot = findContainerTargetSlot(menu, containerSlots, action.item());
            if (sourceSlot < 0) {
                throw new IllegalStateException(
                        "player inventory contains no " + action.item() + " in the open menu");
            }
            if (targetSlot < 0) {
                throw new IllegalStateException("chest has no slot available for " + action.item());
            }
            if (!menu.getCarried().isEmpty()) {
                throw new IllegalStateException("cannot deposit while another item is held by the cursor");
            }
            action.open(menu, containerSlots, containerBefore, sourceSlot, targetSlot);
            click(minecraft.gameMode, player, menu, sourceSlot, 0);
            action.setPhase(DepositPhase.WAIT_SOURCE_SYNC);
            return;
        }
        if (menu != action.menu()) {
            throw new IllegalStateException("container menu closed before deposit completed");
        }
        if (action.phase() == DepositPhase.WAIT_SOURCE_SYNC) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty() || !itemName(carried.getItem()).equals(action.item().toString())) {
                throw new IllegalStateException("source click did not pick up " + action.item());
            }
            action.setCarriedAfterSource(carried.getCount());
            click(minecraft.gameMode, player, menu, action.targetSlot(), 1);
            action.setPhase(DepositPhase.WAIT_TARGET_SYNC);
            return;
        }
        if (action.phase() == DepositPhase.WAIT_TARGET_SYNC) {
            ItemStack carried = menu.getCarried();
            int carriedCount = carried.isEmpty() ? 0 : carried.getCount();
            if (!carried.isEmpty() && !itemName(carried.getItem()).equals(action.item().toString())) {
                throw new IllegalStateException("target click replaced the carried item unexpectedly");
            }
            if (carriedCount != action.carriedAfterSource() - 1
                    || menuItemCount(menu, 0, action.containerSlots(), action.item())
                    != action.containerBefore() + 1) {
                throw new IllegalStateException("target click did not deposit exactly one " + action.item());
            }
            if (carriedCount > 0) {
                click(minecraft.gameMode, player, menu, action.sourceSlot(), 0);
                action.setPhase(DepositPhase.WAIT_RETURN_SYNC);
            } else {
                action.setPhase(DepositPhase.VERIFY);
            }
            return;
        }
        if (action.phase() == DepositPhase.WAIT_RETURN_SYNC) {
            if (!menu.getCarried().isEmpty()) {
                throw new IllegalStateException("remaining carried items were not returned to the source slot");
            }
            action.setPhase(DepositPhase.VERIFY);
            return;
        }
        verifyDeposit(player, action);
    }

    /** Requires five consecutive synchronized ticks before reporting exact transfer counts. */
    private void verifyDeposit(LocalPlayer player, ActiveDeposit action) {
        int playerAfter = inventoryCount(player, action.item());
        int containerAfter = menuItemCount(action.menu(), 0, action.containerSlots(), action.item());
        if (playerAfter != action.playerBefore() - 1
                || containerAfter != action.containerBefore() + 1) {
            action.resetStableTicks();
            return;
        }
        if (action.incrementStableTicks() < 5) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("transferred_count", 1);
        response.addProperty("player_count_before", action.playerBefore());
        response.addProperty("player_count_after", playerAfter);
        response.addProperty("container_count_before", action.containerBefore());
        response.addProperty("container_count_after", containerAfter);
        finishActive(response);
    }

    /** Completes an action successfully and performs its normal interaction cleanup. */
    private void finishActive(JsonObject response) {
        ActiveAction action = activeAction;
        cleanup(action);
        activeAction = null;
        action.result().complete(response);
    }

    /** Completes an action exceptionally while returning carried items when possible. */
    private void finishActive(Exception failure) {
        ActiveAction action = activeAction;
        if (action == null) {
            return;
        }
        cleanup(action);
        activeAction = null;
        action.result().completeExceptionally(failure);
    }

    /** Stops destruction or closes a container without leaving an item on the cursor. */
    private static void cleanup(ActiveAction action) {
        if (action == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (action instanceof ActiveBreak breaking) {
            stopBreakingInput(minecraft, breaking);
            minecraft.options.keyUp.setDown(breaking.forwardWasDown());
        }
        if (action instanceof ActiveDeposit deposit && player != null) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == deposit.menu() && !menu.getCarried().isEmpty() && minecraft.gameMode != null) {
                click(minecraft.gameMode, player, menu, deposit.sourceSlot(), 0);
            }
            if (menu != deposit.inventoryMenu()) {
                player.closeContainer();
            }
        }
    }

    /** Returns a complete stable-slot inventory snapshot including empty slots. */
    private static JsonObject inventoryResult(LocalPlayer player) {
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < 36; slot++) {
            slots.add(slotResult(slot, player.getInventory().getItem(slot)));
        }
        slots.add(slotResult(40, player.getOffhandItem()));
        JsonObject result = new JsonObject();
        result.addProperty("selected_slot", player.getInventory().selected);
        result.add("slots", slots);
        return result;
    }

    /** Encodes one inventory slot using an exact registry name and nonnegative count. */
    private static JsonObject slotResult(int slot, ItemStack stack) {
        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("item", stack.isEmpty() ? "minecraft:air" : itemName(stack.getItem()));
        result.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
        return result;
    }

    /** Validates world loading, reach, and direct ray visibility for a block target. */
    private static BlockHitResult validateTarget(Minecraft minecraft, LocalPlayer player, BlockPos target) {
        if (!minecraft.level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
            throw new IllegalArgumentException("target block is not loaded");
        }
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        double reach = minecraft.gameMode.getPickRange();
        if (eye.distanceTo(center) > reach) {
            throw new IllegalArgumentException("target block is outside interaction reach");
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(
                eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
            throw new IllegalArgumentException("target block is occluded");
        }
        return hit;
    }

    /** Returns the currently crosshair-targeted block after a fresh reach check. */
    private static BlockHitResult crosshairTarget(Minecraft minecraft, LocalPlayer player) {
        HitResult currentHit = minecraft.hitResult;
        if (!(currentHit instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("crosshair is not pointing at a block");
        }
        BlockPos target = hit.getBlockPos();
        if (!minecraft.level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
            throw new IllegalArgumentException("crosshair target block is not loaded");
        }
        if (player.getEyePosition().distanceTo(hit.getLocation()) > minecraft.gameMode.getPickRange()) {
            throw new IllegalArgumentException("crosshair target block is outside interaction reach");
        }
        return hit;
    }

    /** Counts one exact item across the 36 main inventory slots and offhand. */
    private static int inventoryCount(LocalPlayer player, ResourceLocation item) {
        int count = mainInventoryCount(player, item);
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && itemName(offhand.getItem()).equals(item.toString())) {
            count += offhand.getCount();
        }
        return count;
    }

    /** Counts one exact item across the 36 slots represented in a chest menu. */
    private static int mainInventoryCount(LocalPlayer player, ResourceLocation item) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && itemName(stack.getItem()).equals(item.toString())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Counts an exact item in a bounded range of menu slots. */
    private static int menuItemCount(AbstractContainerMenu menu, int start, int end, ResourceLocation item) {
        int count = 0;
        for (int slot = start; slot < end; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && itemName(stack.getItem()).equals(item.toString())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Finds a player-owned menu slot containing the requested item. */
    private static int findPlayerItemSlot(AbstractContainerMenu menu, int containerSlots, ResourceLocation item) {
        for (int slot = containerSlots; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && itemName(stack.getItem()).equals(item.toString())) {
                return slot;
            }
        }
        return -1;
    }

    /** Finds a compatible partial stack before choosing the first empty container slot. */
    private static int findContainerTargetSlot(AbstractContainerMenu menu, int containerSlots, ResourceLocation item) {
        int empty = -1;
        for (int slot = 0; slot < containerSlots; slot++) {
            Slot target = menu.getSlot(slot);
            ItemStack stack = target.getItem();
            if (stack.isEmpty()) {
                Item candidate = item.equals(DIRT) ? Items.DIRT : Items.CRAFTING_TABLE;
                if (empty < 0 && target.mayPlace(new ItemStack(candidate))) {
                    empty = slot;
                }
            } else if (itemName(stack.getItem()).equals(item.toString())
                    && stack.getCount() < Math.min(stack.getMaxStackSize(), target.getMaxStackSize(stack))) {
                return slot;
            }
        }
        return empty;
    }

    /** Sends one normal menu click through the client game-mode controller. */
    private static void click(
            MultiPlayerGameMode gameMode,
            LocalPlayer player,
            AbstractContainerMenu menu,
            int slot,
            int button
    ) {
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new IllegalStateException("container click slot is unavailable");
        }
        gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, player);
    }

    /** Rejects overlapping break and deposit operations. */
    private void requireNoActiveAction() {
        if (activeAction != null) {
            throw new IllegalStateException("another item action is already active");
        }
    }

    /** Returns a live survival-mode player and game-mode controller. */
    private static LocalPlayer requireSurvivalPlayer(Minecraft minecraft) {
        LocalPlayer player = requirePlayer(minecraft);
        if (minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("item actions require survival mode");
        }
        if (!player.isAlive()) {
            throw new IllegalStateException("player is not alive");
        }
        return player;
    }

    /** Returns a live player in a loaded world. */
    private static LocalPlayer requirePlayer(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }
        return minecraft.player;
    }

    private static String itemName(Item item) {
        return Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.ITEM)
                .getKey(item).toString();
    }

    private static String blockName(BlockState state) {
        return Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BLOCK)
                .getKey(state.getBlock()).toString();
    }

    /** Runs a short read on the client thread and propagates its failure. */
    private static CompletableFuture<JsonObject> onClientThread(ClientRead read) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                result.complete(read.run());
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Cancels an active action on the client thread during process shutdown. */
    @Override
    public void close() {
        Minecraft.getInstance().execute(() ->
                finishActive(new IllegalStateException("item action interrupted by shutdown")));
    }

    @FunctionalInterface
    private interface ClientRead {
        JsonObject run();
    }

    private sealed interface ActiveAction permits ActiveBreak, ActiveDeposit {
        LocalPlayer player();

        long deadlineNanos();

        CompletableFuture<JsonObject> result();
    }

    /** Retains break input state while advancing from destruction to pickup. */
    private static final class ActiveBreak implements ActiveAction {
        private final LocalPlayer player;
        private final BlockPos target;
        private final String originalBlock;
        private final ResourceLocation collectedItem;
        private final boolean collect;
        private final int countBefore;
        private final boolean attackWasDown;
        private final boolean mouseWasGrabbed;
        private final boolean forwardWasDown;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private boolean attackReleased;
        private boolean blockBroken;

        ActiveBreak(
                LocalPlayer player,
                BlockPos target,
                String originalBlock,
                ResourceLocation collectedItem,
                boolean collect,
                int countBefore,
                boolean attackWasDown,
                boolean mouseWasGrabbed,
                boolean forwardWasDown,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.originalBlock = originalBlock;
            this.collectedItem = collectedItem;
            this.collect = collect;
            this.countBefore = countBefore;
            this.attackWasDown = attackWasDown;
            this.mouseWasGrabbed = mouseWasGrabbed;
            this.forwardWasDown = forwardWasDown;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        public LocalPlayer player() { return player; }
        public BlockPos target() { return target; }
        public String originalBlock() { return originalBlock; }
        public ResourceLocation collectedItem() { return collectedItem; }
        public boolean collect() { return collect; }
        public int countBefore() { return countBefore; }
        public boolean attackWasDown() { return attackWasDown; }
        public boolean mouseWasGrabbed() { return mouseWasGrabbed; }
        public boolean forwardWasDown() { return forwardWasDown; }
        public long deadlineNanos() { return deadlineNanos; }
        public CompletableFuture<JsonObject> result() { return result; }
        boolean attackReleased() { return attackReleased; }
        boolean blockBroken() { return blockBroken; }
        void markAttackReleased() { attackReleased = true; }
        void markBlockBroken() { blockBroken = true; }
    }

    private enum DepositPhase {
        WAIT_MENU,
        WAIT_SOURCE_SYNC,
        WAIT_TARGET_SYNC,
        WAIT_RETURN_SYNC,
        VERIFY
    }

    /** Stores the server menu and verification counters for one deposit operation. */
    private static final class ActiveDeposit implements ActiveAction {
        private final BlockPos target;
        private final LocalPlayer player;
        private final AbstractContainerMenu inventoryMenu;
        private final ResourceLocation item;
        private final int playerBefore;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private AbstractContainerMenu menu;
        private int containerSlots;
        private int containerBefore;
        private int sourceSlot = -1;
        private int targetSlot = -1;
        private int carriedAfterSource;
        private int stableTicks;
        private DepositPhase phase = DepositPhase.WAIT_MENU;

        ActiveDeposit(
                LocalPlayer player,
                BlockPos target,
                AbstractContainerMenu inventoryMenu,
                ResourceLocation item,
                int playerBefore,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.inventoryMenu = inventoryMenu;
            this.item = item;
            this.playerBefore = playerBefore;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        void open(
                AbstractContainerMenu opened,
                int slots,
                int before,
                int source,
                int targetSlot
        ) {
            menu = opened;
            containerSlots = slots;
            containerBefore = before;
            sourceSlot = source;
            this.targetSlot = targetSlot;
        }

        void setCarriedAfterSource(int value) {
            carriedAfterSource = value;
        }

        int incrementStableTicks() {
            return ++stableTicks;
        }

        void resetStableTicks() {
            stableTicks = 0;
        }

        void setPhase(DepositPhase value) {
            phase = value;
        }

        BlockPos target() { return target; }
        @Override public LocalPlayer player() { return player; }
        AbstractContainerMenu inventoryMenu() { return inventoryMenu; }
        ResourceLocation item() { return item; }
        int playerBefore() { return playerBefore; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
        AbstractContainerMenu menu() { return menu; }
        int containerSlots() { return containerSlots; }
        int containerBefore() { return containerBefore; }
        int sourceSlot() { return sourceSlot; }
        int targetSlot() { return targetSlot; }
        int carriedAfterSource() { return carriedAfterSource; }
        DepositPhase phase() { return phase; }
    }
}
