// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/** Executes the confirmed inventory and Minecraft action primitive capabilities. */
public final class MinecraftActions implements AutoCloseable {
    private static final long ACTION_TIMEOUT_NANOS = 25_000_000_000L;
    private static final long AIM_TIMEOUT_NANOS = 5_000_000_000L;
    private static final double MAX_AIM_YAW_DEGREES_PER_SECOND = 120.0;
    private static final double MAX_AIM_PITCH_DEGREES_PER_SECOND = 90.0;
    private static final int REQUIRED_AIM_HIT_TICKS = 3;
    private static final int REQUIRED_ITEM_SELECTION_TICKS = 3;
    private static final int MAX_ATTACK_ATTEMPTS = 30;
    private static final long MIN_ATTACK_INTERVAL_NANOS = 650_000_000L;
    private static final float FULL_ATTACK_STRENGTH = 0.999F;
    private static final float MIN_COMBAT_HEALTH = 4.0F;
    private static final double MAX_ATTACK_START_DISTANCE = 12.0;
    private static final double MAX_COMBAT_TRAVEL_DISTANCE = 12.0;
    private static final double MAX_TARGET_TRAVEL_DISTANCE = 16.0;
    private static final double ATTACK_APPROACH_DISTANCE = 2.6;
    private static final double ATTACK_REACH_DISTANCE = 3.1;
    /** A blocked-path snapshot must start near the live Minecraft player. */
    private static final double MAX_ROUTE_ALIGNMENT_DISTANCE_M = 2.0;
    private static final ResourceLocation DIRT = ResourceLocation.fromNamespaceAndPath("minecraft", "dirt");
    private static final ResourceLocation CRAFTING_TABLE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "crafting_table");
    private static final ResourceLocation ZOMBIE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");
    private static final Set<ResourceLocation> BREAKABLE_BLOCKS = Set.of(DIRT, CRAFTING_TABLE);
    private static final Set<ResourceLocation> ATTACKABLE_ENTITIES = Set.of(ZOMBIE);
    private final MinecraftControl control;
    private ActiveAction activeAction;

    public MinecraftActions(MinecraftControl control) {
        this.control = control;
        control.setActionActive(this::hasActiveAction);
    }

    /** Reports whether a client-tick action currently owns interaction and motion. */
    private boolean hasActiveAction() {
        return activeAction != null;
    }

    /** Reads all stable main-inventory slots plus offhand on the client thread. */
    public CompletableFuture<JsonObject> inventory() {
        return onClientThread(() -> inventoryResult(requirePlayer(Minecraft.getInstance())));
    }

    /** Selects the largest matching main-inventory stack into the main hand. */
    public CompletableFuture<JsonObject> selectInventoryItem(String itemName) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requirePlayer(minecraft);
                if (minecraft.screen != null) {
                    throw new IllegalStateException(
                            "close the current screen before selecting an inventory item");
                }
                if (minecraft.gameMode == null) {
                    throw new IllegalStateException("Minecraft game mode is unavailable");
                }
                ResourceLocation requested = ResourceLocation.tryParse(itemName);
                if (requested == null
                        || !minecraft.level.registryAccess().registryOrThrow(Registries.ITEM)
                                .containsKey(requested)) {
                    throw new IllegalArgumentException(
                            "item name is not registered: " + itemName
                                    + "; use an exact Minecraft item registry name");
                }
                Item item = minecraft.level.registryAccess().registryOrThrow(Registries.ITEM)
                        .get(requested);
                int sourceSlot = findLargestMainInventoryItemSlot(player, item);
                if (sourceSlot < 0) {
                    throw new IllegalStateException(
                            "player main inventory contains no " + itemName);
                }
                boolean movedToHotbar = sourceSlot >= 9;
                if (movedToHotbar) {
                    minecraft.gameMode.handlePickItem(sourceSlot);
                } else {
                    player.getInventory().selected = sourceSlot;
                }
                activeAction = new ActiveSelectInventoryItem(
                        player,
                        requested,
                        sourceSlot,
                        movedToHotbar,
                        System.nanoTime() + AIM_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Gradually turns the player until the live crosshair stably hits one reachable block. */
    public CompletableFuture<JsonObject> aimAtBlock(
            int x,
            int y,
            int z,
            String expectedBlock
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requirePlayer(minecraft);
                BlockPos target = new BlockPos(x, y, z);
                ResourceLocation expected = ResourceLocation.tryParse(expectedBlock);
                if (expected == null) {
                    throw new IllegalArgumentException("expected_block is not a valid registry name");
                }
                validateExpectedTarget(minecraft, player, target, expectedBlock);
                if (minecraft.screen != null) {
                    throw new IllegalStateException("close the current screen before aiming at a block");
                }
                control.stopForAction();
                long now = System.nanoTime();
                activeAction = new ActiveAim(
                        player,
                        target,
                        expectedBlock,
                        now,
                        now + AIM_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
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
                if (player.getHealth() < MIN_COMBAT_HEALTH) {
                    throw new IllegalStateException(
                            "entity combat requires at least 4 bot health");
                }
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

    /** Finds the nearest collidable block in the player's short forward corridor. */
    public CompletableFuture<JsonObject> inspectRouteObstacle(double maxDistance) {
        return onClientThread(() -> {
            requireNoActiveAction();
            if (!Double.isFinite(maxDistance) || maxDistance < 0.5 || maxDistance > 4.5) {
                throw new IllegalArgumentException("max_distance must be from 0.5 through 4.5");
            }
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = requirePlayer(minecraft);
            double yaw = Math.toRadians(player.getYRot());
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            BlockPos previous = null;
            for (double distance = 0.4; distance <= maxDistance; distance += 0.2) {
                for (double height : new double[]{0.1, 1.1}) {
                    BlockPos candidate = BlockPos.containing(
                            player.getX() + forwardX * distance,
                            player.getY() + height,
                            player.getZ() + forwardZ * distance);
                    if (candidate.equals(previous)) {
                        continue;
                    }
                    previous = candidate;
                    BlockState state = minecraft.level.getBlockState(candidate);
                    if (state.isAir()
                            || state.getCollisionShape(minecraft.level, candidate).isEmpty()) {
                        continue;
                    }
                    JsonObject response = new JsonObject();
                    response.addProperty("found", true);
                    response.addProperty("block_name", blockName(state));
                    response.addProperty("x", candidate.getX());
                    response.addProperty("y", candidate.getY());
                    response.addProperty("z", candidate.getZ());
                    response.addProperty("distance_m", distance);
                    return response;
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("found", false);
            return response;
        });
    }

    /** Inspects a bounded minecraft_odom path corridor without changing the world. */
    public CompletableFuture<JsonObject> inspectRoutePath(
            double[] navigationX,
            double[] navigationY,
            int bodyY,
            double halfWidth,
            double sampleSpacing,
            int maxBlocks
    ) {
        return onClientThread(() -> {
            requireNoActiveAction();
            if (navigationX == null || navigationY == null
                    || navigationX.length == 0 || navigationX.length != navigationY.length
                    || navigationX.length > 256) {
                throw new IllegalArgumentException("path must contain from 1 through 256 paired points");
            }
            if (!Double.isFinite(halfWidth) || halfWidth < 0.30 || halfWidth > 1.5) {
                throw new IllegalArgumentException("half_width must be from 0.30 through 1.5");
            }
            if (!Double.isFinite(sampleSpacing) || sampleSpacing < 0.1 || sampleSpacing > 1.0) {
                throw new IllegalArgumentException("sample_spacing must be from 0.1 through 1.0");
            }
            if (maxBlocks < 1 || maxBlocks > 256) {
                throw new IllegalArgumentException("max_blocks must be from 1 through 256");
            }
            for (int index = 0; index < navigationX.length; index++) {
                if (!Double.isFinite(navigationX[index]) || !Double.isFinite(navigationY[index])) {
                    throw new IllegalArgumentException("path coordinates must be finite");
                }
            }
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = requirePlayer(minecraft);
            int requestedBodyY = bodyY;
            int playerBodyY = (int) Math.floor(player.getY());
            // Recovery starts at the live player pose.  If the planner model
            // supplied a stale height, use the actual first body layer so a
            // ground-level dirt block is not silently skipped.
            int effectiveBodyY = playerBodyY;
            double playerNavigationX = player.getX();
            double playerNavigationY = -player.getZ();
            double originalPathStartDistance = Math.hypot(
                    navigationX[0] - playerNavigationX,
                    navigationY[0] - playerNavigationY);
            int alignedSegmentIndex = 0;
            double alignedNavigationX = navigationX[0];
            double alignedNavigationY = navigationY[0];
            double routeAlignmentDistance = originalPathStartDistance;
            for (int index = 0; index + 1 < navigationX.length; index++) {
                double deltaX = navigationX[index + 1] - navigationX[index];
                double deltaY = navigationY[index + 1] - navigationY[index];
                double lengthSquared = deltaX * deltaX + deltaY * deltaY;
                double ratio = lengthSquared == 0.0 ? 0.0 : Mth.clamp(
                        ((playerNavigationX - navigationX[index]) * deltaX
                                + (playerNavigationY - navigationY[index]) * deltaY)
                                / lengthSquared,
                        0.0,
                        1.0);
                double projectedX = navigationX[index] + deltaX * ratio;
                double projectedY = navigationY[index] + deltaY * ratio;
                double distance = Math.hypot(
                        projectedX - playerNavigationX,
                        projectedY - playerNavigationY);
                if (distance < routeAlignmentDistance) {
                    routeAlignmentDistance = distance;
                    alignedSegmentIndex = index;
                    alignedNavigationX = projectedX;
                    alignedNavigationY = projectedY;
                }
            }
            if (routeAlignmentDistance > MAX_ROUTE_ALIGNMENT_DISTANCE_M) {
                JsonObject response = new JsonObject();
                response.addProperty("found", false);
                response.addProperty("dirt_count", 0);
                response.addProperty("other_count", 0);
                response.add("dirt_blocks", new JsonArray());
                response.add("other_blocks", new JsonArray());
                response.addProperty("body_y", effectiveBodyY);
                response.addProperty("requested_body_y", requestedBodyY);
                response.addProperty("player_body_y", playerBodyY);
                response.addProperty("player_x", player.getX());
                response.addProperty("player_z", player.getZ());
                response.addProperty("player_navigation_x", playerNavigationX);
                response.addProperty("player_navigation_y", playerNavigationY);
                response.addProperty("path_start_navigation_x", navigationX[0]);
                response.addProperty("path_start_navigation_y", navigationY[0]);
                response.addProperty("path_start_distance_m", originalPathStartDistance);
                response.addProperty("nearest_path_navigation_x", alignedNavigationX);
                response.addProperty("nearest_path_navigation_y", alignedNavigationY);
                response.addProperty("nearest_path_segment_index", alignedSegmentIndex);
                response.addProperty("route_alignment_distance_m", routeAlignmentDistance);
                response.addProperty(
                        "detail",
                        String.format(
                                java.util.Locale.ROOT,
                                "planner path is not aligned with current minecraft_odom pose "
                                        + "(nearest route segment is %.2f m from player; maximum is %.2f m)",
                                routeAlignmentDistance,
                                MAX_ROUTE_ALIGNMENT_DISTANCE_M));
                return response;
            }
            Map<BlockPos, String> dirt = new LinkedHashMap<>();
            Map<BlockPos, String> other = new LinkedHashMap<>();
            for (int index = alignedSegmentIndex; index < navigationX.length; index++) {
                double startX = index == alignedSegmentIndex ? alignedNavigationX : navigationX[index];
                double startNavigationY = index == alignedSegmentIndex
                        ? alignedNavigationY : navigationY[index];
                double startZ = -startNavigationY;
                double endX = index + 1 < navigationX.length ? navigationX[index + 1] : startX;
                double endZ = index + 1 < navigationX.length ? -navigationY[index + 1] : startZ;
                double length = Math.hypot(endX - startX, endZ - startZ);
                int steps = Math.max(1, (int) Math.ceil(length / sampleSpacing));
                for (int step = 0; step <= steps; step++) {
                    double ratio = (double) step / steps;
                    double sampleX = startX + (endX - startX) * ratio;
                    double sampleZ = startZ + (endZ - startZ) * ratio;
                    int minX = (int) Math.floor(sampleX - halfWidth);
                    int maxX = (int) Math.floor(sampleX + halfWidth);
                    int minZ = (int) Math.floor(sampleZ - halfWidth);
                    int maxZ = (int) Math.floor(sampleZ + halfWidth);
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            for (int y = effectiveBodyY; y <= effectiveBodyY + 1; y++) {
                                BlockPos position = new BlockPos(x, y, z);
                                BlockState state = minecraft.level.getBlockState(position);
                                if (state.isAir()
                                        || state.getCollisionShape(minecraft.level, position).isEmpty()) {
                                    continue;
                                }
                                String name = blockName(state);
                                if (state.is(Blocks.DIRT)) {
                                    dirt.putIfAbsent(position, name);
                                } else {
                                    other.putIfAbsent(position, name);
                                }
                                if (dirt.size() + other.size() > maxBlocks) {
                                    throw new IllegalStateException(
                                            "route corridor contains more than max_blocks obstacles");
                                }
                            }
                        }
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("found", !dirt.isEmpty() || !other.isEmpty());
            response.addProperty("dirt_count", dirt.size());
            response.addProperty("other_count", other.size());
            response.add("dirt_blocks", blockArray(dirt));
            response.add("other_blocks", blockArray(other));
            response.addProperty("body_y", effectiveBodyY);
            response.addProperty("requested_body_y", requestedBodyY);
            response.addProperty("player_body_y", playerBodyY);
            response.addProperty("player_x", player.getX());
            response.addProperty("player_z", player.getZ());
            response.addProperty("player_navigation_x", playerNavigationX);
            response.addProperty("player_navigation_y", playerNavigationY);
            response.addProperty("path_start_navigation_x", navigationX[0]);
            response.addProperty("path_start_navigation_y", navigationY[0]);
            response.addProperty("path_start_distance_m", originalPathStartDistance);
            response.addProperty("nearest_path_navigation_x", alignedNavigationX);
            response.addProperty("nearest_path_navigation_y", alignedNavigationY);
            response.addProperty("nearest_path_segment_index", alignedSegmentIndex);
            response.addProperty("route_alignment_distance_m", routeAlignmentDistance);
            response.addProperty(
                    "detail",
                    dirt.isEmpty() && other.isEmpty()
                            ? "no collision block occupies the requested path corridor"
                            : "route corridor inspection completed");
            return response;
        });
    }

    /** Encodes inspected native block identities while keeping coordinates local to the Mod. */
    private static JsonArray blockArray(Map<BlockPos, String> blocks) {
        JsonArray result = new JsonArray();
        for (Map.Entry<BlockPos, String> entry : blocks.entrySet()) {
            BlockPos position = entry.getKey();
            JsonObject block = new JsonObject();
            block.addProperty("block_name", entry.getValue());
            block.addProperty("x", position.getX());
            block.addProperty("y", position.getY());
            block.addProperty("z", position.getZ());
            result.add(block);
        }
        return result;
    }

    /** Begins bounded pursuit and cooldown-aware attacks against one live zombie UUID. */
    public CompletableFuture<JsonObject> attackEntity(
            String entityUuid,
            String expectedEntity
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requireSurvivalPlayer(minecraft);
                if (minecraft.screen != null) {
                    throw new IllegalStateException("close the current screen before attacking an entity");
                }
                ResourceLocation expected = ResourceLocation.tryParse(expectedEntity);
                if (expected == null || !ATTACKABLE_ENTITIES.contains(expected)) {
                    throw new IllegalArgumentException("expected_entity must be minecraft:zombie");
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(entityUuid);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("entity_uuid must be a valid UUID", exception);
                }
                Entity entity = findEntity(minecraft, uuid);
                if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                    throw new IllegalStateException("target entity is unavailable or already dead");
                }
                String actual = entityName(minecraft, entity);
                if (!actual.equals(expectedEntity)) {
                    throw new IllegalStateException(
                            "target entity is " + actual + ", not " + expectedEntity);
                }
                if (player.distanceTo(entity) > MAX_ATTACK_START_DISTANCE) {
                    throw new IllegalArgumentException("target entity is outside combat start range");
                }
                if (!player.hasLineOfSight(entity)) {
                    throw new IllegalArgumentException("target entity is not visible");
                }
                control.stopForAction();
                long now = System.nanoTime();
                activeAction = new ActiveAttack(
                        player,
                        uuid,
                        expectedEntity,
                        player.getHealth(),
                        living.getHealth(),
                        player.getX(),
                        player.getZ(),
                        entity.getX(),
                        entity.getZ(),
                        minecraft.options.keyUp.isDown(),
                        now,
                        now + ACTION_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Validates and begins an exact deposit of any registered item into a chest. */
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
                if (depositedItem == null
                        || !minecraft.level.registryAccess().registryOrThrow(Registries.ITEM)
                                .containsKey(depositedItem)) {
                    throw new IllegalArgumentException(
                            "item name is not registered: " + itemName
                                    + "; use an exact Minecraft item registry name");
                }
                if (count <= 0L || count > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "count must be positive and no greater than " + Integer.MAX_VALUE);
                }
                BlockPos target = new BlockPos(x, y, z);
                BlockHitResult hit = validateDepositTarget(minecraft, player, target);
                BlockState state = minecraft.level.getBlockState(target);
                if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) {
                    throw new IllegalStateException("target block is not a chest: " + blockName(state));
                }
                int playerBefore = inventoryCount(player, depositedItem);
                int available = mainInventoryCount(player, depositedItem);
                if (available == 0) {
                    throw new IllegalStateException("player main inventory contains no " + depositedItem);
                }
                if (available < count) {
                    throw new IllegalStateException(
                            "player main inventory contains only " + available + " " + depositedItem
                                    + "; requested " + count);
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
                        count,
                        playerBefore,
                        System.nanoTime() + ACTION_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Opens a chest and transfers exactly the requested quantity into main inventory. */
    public CompletableFuture<JsonObject> withdrawItem(
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
                ResourceLocation withdrawnItem = ResourceLocation.tryParse(itemName);
                if (withdrawnItem == null
                        || !minecraft.level.registryAccess().registryOrThrow(Registries.ITEM)
                                .containsKey(withdrawnItem)) {
                    throw new IllegalArgumentException(
                            "item name is not registered: " + itemName
                                    + "; use an exact Minecraft item registry name");
                }
                if (count <= 0L) {
                    throw new IllegalArgumentException("count must be positive");
                }
                BlockPos target = new BlockPos(x, y, z);
                BlockHitResult hit = validateDepositTarget(minecraft, player, target);
                BlockState state = minecraft.level.getBlockState(target);
                if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)) {
                    throw new IllegalStateException("target block is not a chest: " + blockName(state));
                }
                int playerBefore = inventoryCount(player, withdrawnItem);
                control.stopForAction();
                if (!minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                    throw new IllegalStateException("Minecraft rejected the chest interaction");
                }
                activeAction = new ActiveWithdraw(
                        player,
                        target,
                        player.inventoryMenu,
                        withdrawnItem,
                        count,
                        playerBefore,
                        System.nanoTime() + ACTION_TIMEOUT_NANOS,
                        result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    /** Validates and begins exact glass-bottle interactions with one water cauldron. */
    public CompletableFuture<JsonObject> fillWaterBottles(
            int x,
            int y,
            int z,
            long count
    ) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                requireNoActiveAction();
                Minecraft minecraft = Minecraft.getInstance();
                LocalPlayer player = requireSurvivalPlayer(minecraft);
                if (count < 1L || count > 3L) {
                    throw new IllegalArgumentException("count must be from 1 through 3");
                }
                BlockPos target = new BlockPos(x, y, z);
                validateCauldronTarget(minecraft, player, target);
                BlockState state = minecraft.level.getBlockState(target);
                if (!state.is(Blocks.WATER_CAULDRON)) {
                    throw new IllegalStateException(
                            "target block is not a water cauldron: " + blockName(state));
                }
                int levelBefore = state.getValue(LayeredCauldronBlock.LEVEL);
                if (levelBefore < count) {
                    throw new IllegalStateException(
                            "water cauldron contains only " + levelBefore
                                    + " level; requested " + count);
                }
                int emptyBefore = mainInventoryCount(player, itemLocation(Items.GLASS_BOTTLE));
                if (emptyBefore == 0) {
                    throw new IllegalStateException(
                            "player main inventory contains no minecraft:glass_bottle");
                }
                if (emptyBefore < count) {
                    throw new IllegalStateException(
                            "player main inventory contains only " + emptyBefore
                                    + " glass bottle; requested " + count);
                }
                int largestBottleStack = largestMainInventoryStack(player, Items.GLASS_BOTTLE);
                int requiredEmptySlots = largestBottleStack >= count
                        ? Math.max(0, (int) count - 1)
                        : (int) count;
                int emptySlots = mainInventoryEmptySlots(player);
                if (emptySlots < requiredEmptySlots) {
                    throw new IllegalStateException(
                            "player main inventory needs " + requiredEmptySlots
                                    + " empty slot for " + count + " water bottle; found "
                                    + emptySlots);
                }
                int waterBefore = mainWaterBottleCount(player);
                if (minecraft.screen != null) {
                    throw new IllegalStateException(
                            "close the current screen before filling water bottles");
                }
                control.stopForAction();
                activeAction = new ActiveFillWaterBottles(
                        player,
                        target,
                        (int) count,
                        player.getInventory().selected,
                        emptyBefore,
                        waterBefore,
                        levelBefore,
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
            } else if (action instanceof ActiveAttack attacking) {
                tickAttack(minecraft, player, attacking);
            } else if (action instanceof ActiveSelectInventoryItem selecting) {
                tickSelectInventoryItem(minecraft, player, selecting);
            } else if (action instanceof ActiveDeposit deposit) {
                tickDeposit(minecraft, player, deposit);
            } else if (action instanceof ActiveWithdraw withdraw) {
                tickWithdraw(minecraft, player, withdraw);
            } else if (action instanceof ActiveFillWaterBottles fill) {
                tickFillWaterBottles(minecraft, player, fill);
            } else if (action instanceof ActiveAim aiming) {
                tickAim(minecraft, player, aiming);
            }
        } catch (Exception exception) {
            finishActive(exception);
        }
    }

    /** Requires a stable synchronized main-hand item before reporting success. */
    private void tickSelectInventoryItem(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveSelectInventoryItem action
    ) {
        if (minecraft.screen != null) {
            throw new IllegalStateException(
                    "a screen opened before inventory item selection completed");
        }
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isEmpty() || !itemName(mainHand.getItem()).equals(action.item().toString())) {
            action.resetStableTicks();
            return;
        }
        if (action.incrementStableTicks() < REQUIRED_ITEM_SELECTION_TICKS) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("selected", true);
        response.addProperty("item", action.item().toString());
        response.addProperty("source_slot", action.sourceSlot());
        response.addProperty("selected_slot", player.getInventory().selected);
        response.addProperty("selected_count", mainHand.getCount());
        response.addProperty("moved_to_hotbar", action.movedToHotbar());
        finishActive(response);
    }

    /** Applies bounded view corrections and succeeds only after stable live crosshair hits. */
    private void tickAim(Minecraft minecraft, LocalPlayer player, ActiveAim action) {
        if (minecraft.screen != null) {
            throw new IllegalStateException("a screen opened before aiming completed");
        }
        validateExpectedTarget(minecraft, player, action.target(), action.expectedBlock());
        HitResult currentHit = minecraft.hitResult;
        if (currentHit instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(action.target())) {
            if (action.incrementStableHitTicks() >= REQUIRED_AIM_HIT_TICKS) {
                JsonObject response = new JsonObject();
                response.addProperty("aimed", true);
                response.addProperty("actual_block", action.expectedBlock());
                response.addProperty("hit_face", blockHit.getDirection().getName());
                response.addProperty("final_yaw_deg", player.getYRot());
                response.addProperty("final_pitch_deg", player.getXRot());
                finishActive(response);
            }
            return;
        }
        action.resetStableHitTicks();
        turnTowardBlock(player, action, System.nanoTime());
    }

    /** Turns toward the target centre without exceeding the configured per-second view rates. */
    private static void turnTowardBlock(LocalPlayer player, ActiveAim action, long nowNanos) {
        Vec3 eye = player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(action.target());
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.hypot(dx, dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        double elapsedSeconds = Math.min(0.1, Math.max(0.001,
                (nowNanos - action.lastStepNanos()) / 1_000_000_000.0));
        float yawError = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchError = targetPitch - player.getXRot();
        float yawStep = boundedStep(yawError, MAX_AIM_YAW_DEGREES_PER_SECOND * elapsedSeconds);
        float pitchStep = boundedStep(pitchError, MAX_AIM_PITCH_DEGREES_PER_SECOND * elapsedSeconds);
        player.setYRot(player.getYRot() + yawStep);
        player.setYHeadRot(player.getYRot());
        player.setXRot(Mth.clamp(player.getXRot() + pitchStep, -90.0F, 90.0F));
        action.setLastStepNanos(nowNanos);
    }

    /** Clamps one signed angular correction to its positive magnitude limit. */
    private static float boundedStep(float error, double maximumMagnitude) {
        float limit = (float) maximumMagnitude;
        return Mth.clamp(error, -limit, limit);
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

    /** Pursues, aims at, attacks, and verifies death of the UUID-bound target. */
    private void tickAttack(Minecraft minecraft, LocalPlayer player, ActiveAttack action) {
        if (minecraft.screen != null) {
            throw new IllegalStateException("a screen opened during entity combat");
        }
        if (player.getHealth() < MIN_COMBAT_HEALTH) {
            throw new IllegalStateException("entity combat stopped because bot health is below 4");
        }
        Entity entity = findEntity(minecraft, action.targetUuid());
        if (!(entity instanceof LivingEntity living)) {
            throw new IllegalStateException("target entity disappeared before death was confirmed");
        }
        String actual = entityName(minecraft, entity);
        if (!actual.equals(action.expectedEntity())) {
            throw new IllegalStateException(
                    "target entity changed type to " + actual);
        }
        action.observeHealth(living.getHealth());
        if (!living.isAlive() || living.isDeadOrDying() || living.getHealth() <= 0.0F) {
            JsonObject response = new JsonObject();
            response.addProperty("killed", true);
            response.addProperty("actual_entity", actual);
            response.addProperty("attacks_attempted", action.attacksAttempted());
            response.addProperty("attacks_landed", action.attacksLanded());
            response.addProperty("bot_health_before", action.botHealthBefore());
            response.addProperty("bot_health_after", player.getHealth());
            finishActive(response);
            return;
        }
        if (horizontalDistance(player.getX(), player.getZ(), action.startX(), action.startZ())
                > MAX_COMBAT_TRAVEL_DISTANCE) {
            throw new IllegalStateException("entity combat exceeded maximum bot travel distance");
        }
        if (horizontalDistance(entity.getX(), entity.getZ(), action.targetStartX(), action.targetStartZ())
                > MAX_TARGET_TRAVEL_DISTANCE) {
            throw new IllegalStateException("target entity exceeded maximum chase distance");
        }
        long now = System.nanoTime();
        turnTowardEntity(player, living, action, now);
        double horizontal = horizontalDistance(
                player.getX(), player.getZ(), entity.getX(), entity.getZ());
        if (horizontal > ATTACK_APPROACH_DISTANCE) {
            if (!player.onGround()) {
                minecraft.options.keyUp.setDown(action.forwardWasDown());
                return;
            }
            if (!hasSafeCombatStep(minecraft, player, entity)) {
                throw new IllegalStateException("entity combat stopped before an unsafe drop");
            }
            minecraft.options.keyUp.setDown(true);
        } else {
            minecraft.options.keyUp.setDown(action.forwardWasDown());
        }
        if (player.distanceTo(entity) > ATTACK_REACH_DISTANCE
                || !player.hasLineOfSight(entity)) {
            return;
        }
        HitResult currentHit = minecraft.hitResult;
        if (!(currentHit instanceof EntityHitResult entityHit)
                || entityHit.getType() != HitResult.Type.ENTITY
                || entityHit.getEntity() != entity
                || player.getAttackStrengthScale(0.0F) < FULL_ATTACK_STRENGTH
                || living.hurtTime > 0
                || !action.attackIntervalElapsed(now)) {
            return;
        }
        if (action.attacksAttempted() >= MAX_ATTACK_ATTEMPTS) {
            throw new IllegalStateException("target survived the maximum 30 attack attempts");
        }
        minecraft.gameMode.attack(player, entity);
        player.swing(InteractionHand.MAIN_HAND);
        action.recordAttack(now);
    }

    /** Applies bounded yaw and pitch correction toward a moving entity centre. */
    private static void turnTowardEntity(
            LocalPlayer player,
            LivingEntity target,
            ActiveAttack action,
            long nowNanos
    ) {
        Vec3 eye = player.getEyePosition();
        Vec3 center = target.getBoundingBox().getCenter();
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double horizontal = Math.hypot(dx, dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        double elapsedSeconds = Math.min(0.1, Math.max(0.001,
                (nowNanos - action.lastStepNanos()) / 1_000_000_000.0));
        float yawError = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchError = targetPitch - player.getXRot();
        player.setYRot(player.getYRot() + boundedStep(
                yawError, MAX_AIM_YAW_DEGREES_PER_SECOND * elapsedSeconds));
        player.setYHeadRot(player.getYRot());
        player.setXRot(Mth.clamp(player.getXRot() + boundedStep(
                pitchError, MAX_AIM_PITCH_DEGREES_PER_SECOND * elapsedSeconds),
                -90.0F, 90.0F));
        action.setLastStepNanos(nowNanos);
    }

    /** Returns planar distance between two Minecraft world positions. */
    private static double horizontalDistance(double x1, double z1, double x2, double z2) {
        return Math.hypot(x1 - x2, z1 - z2);
    }

    /** Requires solid support under the next short pursuit step. */
    private static boolean hasSafeCombatStep(
            Minecraft minecraft,
            LocalPlayer player,
            Entity target
    ) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-6) {
            return true;
        }
        double nextX = player.getX() + dx / length * 0.6;
        double nextZ = player.getZ() + dz / length * 0.6;
        BlockPos support = BlockPos.containing(nextX, player.getY() - 0.2, nextZ);
        return !minecraft.level.getBlockState(support)
                .getCollisionShape(minecraft.level, support).isEmpty();
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
            int capacity = containerCapacityForPlayerItems(
                    menu, containerSlots, action.item(), action.requestedCount());
            if (capacity < action.requestedCount()) {
                throw new IllegalStateException(
                        "chest can hold only " + capacity + " more " + action.item()
                                + "; requested " + action.requestedCount());
            }
            if (!menu.getCarried().isEmpty()) {
                throw new IllegalStateException("cannot deposit while another item is held by the cursor");
            }
            action.open(menu, containerSlots, containerBefore);
            pickNextDepositSource(minecraft, player, action);
            return;
        }
        if (menu != action.menu()) {
            throw new IllegalStateException("container menu closed before deposit completed");
        }
        if (action.phase() == DepositPhase.WAIT_SOURCE_SYNC) {
            transferFromDepositCursor(minecraft, player, action);
            return;
        }
        if (action.phase() == DepositPhase.WAIT_TARGET_SYNC) {
            ItemStack carried = menu.getCarried();
            int carriedCount = carried.isEmpty() ? 0 : carried.getCount();
            if (!carried.isEmpty() && !itemName(carried.getItem()).equals(action.item().toString())) {
                throw new IllegalStateException("target click replaced the carried item unexpectedly");
            }
            if (carriedCount != action.expectedCarriedCount()) {
                throw new IllegalStateException("deposit click did not transfer the expected item count");
            }
            action.confirmPendingTransfer();
            if (menuItemCount(menu, 0, action.containerSlots(), action.item())
                    != action.containerBefore() + action.transferredCount()) {
                throw new IllegalStateException("chest inventory did not synchronize after deposit");
            }
            if (inventoryCount(player, action.item()) + carriedCount
                    != action.playerBefore() - action.transferredCount()) {
                throw new IllegalStateException("player inventory did not synchronize after deposit");
            }
            if (action.transferredCount() == action.requestedCount()) {
                if (carried.isEmpty()) {
                    action.setPhase(DepositPhase.VERIFY);
                } else {
                    click(minecraft.gameMode, player, menu, action.sourceSlot(), 0);
                    action.setPhase(DepositPhase.WAIT_RETURN_SYNC);
                }
            } else if (carried.isEmpty()) {
                pickNextDepositSource(minecraft, player, action);
            } else {
                action.setPhase(DepositPhase.WAIT_SOURCE_SYNC);
            }
            return;
        }
        if (action.phase() == DepositPhase.WAIT_RETURN_SYNC) {
            if (!menu.getCarried().isEmpty()) {
                return;
            }
            action.setPhase(DepositPhase.VERIFY);
            return;
        }
        verifyDeposit(player, action);
    }

    /** Picks the next player stack containing the item being deposited. */
    private static void pickNextDepositSource(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveDeposit action
    ) {
        int sourceSlot = findPlayerItemSlot(
                action.menu(), action.containerSlots(), action.item());
        if (sourceSlot < 0) {
            throw new IllegalStateException(
                    "player item stacks changed before deposit completed: " + action.item());
        }
        action.setSourceSlot(sourceSlot);
        click(minecraft.gameMode, player, action.menu(), sourceSlot, 0);
        action.setPhase(DepositPhase.WAIT_SOURCE_SYNC);
    }

    /** Moves a full compatible chunk when possible, otherwise one exact item. */
    private static void transferFromDepositCursor(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveDeposit action
    ) {
        AbstractContainerMenu menu = action.menu();
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() || !itemName(carried.getItem()).equals(action.item().toString())) {
            throw new IllegalStateException("source click did not pick up " + action.item());
        }
        int targetSlot = findContainerTargetSlot(menu, action.containerSlots(), carried);
        if (targetSlot < 0) {
            throw new IllegalStateException("chest has no slot available for " + action.item());
        }
        int capacity = slotCapacityForStack(menu.getSlot(targetSlot), carried);
        long remaining = action.requestedCount() - action.transferredCount();
        int fullMove = Math.min(capacity, carried.getCount());
        int moved;
        int button;
        if (fullMove <= remaining) {
            moved = fullMove;
            button = 0;
        } else {
            moved = 1;
            button = 1;
        }
        action.setTargetSlot(targetSlot);
        action.expectTransfer(moved, carried.getCount() - moved);
        click(minecraft.gameMode, player, menu, targetSlot, button);
        action.setPhase(DepositPhase.WAIT_TARGET_SYNC);
    }

    /** Requires five synchronized ticks before reporting exact transfer counts. */
    private void verifyDeposit(LocalPlayer player, ActiveDeposit action) {
        int playerAfter = inventoryCount(player, action.item());
        int containerAfter = menuItemCount(action.menu(), 0, action.containerSlots(), action.item());
        if (playerAfter != action.playerBefore() - action.requestedCount()
                || containerAfter != action.containerBefore() + action.requestedCount()) {
            action.resetStableTicks();
            return;
        }
        if (action.incrementStableTicks() < 5) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("transferred_count", action.transferredCount());
        response.addProperty("player_count_before", action.playerBefore());
        response.addProperty("player_count_after", playerAfter);
        response.addProperty("container_count_before", action.containerBefore());
        response.addProperty("container_count_after", containerAfter);
        finishActive(response);
    }

    /** Opens and checks the chest before making the first withdrawal click. */
    private void tickWithdraw(Minecraft minecraft, LocalPlayer player, ActiveWithdraw action) {
        validateTarget(minecraft, player, action.target());
        BlockState targetState = minecraft.level.getBlockState(action.target());
        if (!targetState.is(Blocks.CHEST) && !targetState.is(Blocks.TRAPPED_CHEST)) {
            throw new IllegalStateException("target chest was removed or replaced");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (action.phase() == WithdrawPhase.WAIT_MENU) {
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
            if (containerBefore == 0) {
                throw new IllegalStateException("chest contains no " + action.item());
            }
            if (containerBefore < action.requestedCount()) {
                throw new IllegalStateException(
                        "chest contains only " + containerBefore + " " + action.item()
                                + "; requested " + action.requestedCount());
            }
            Item item = minecraft.level.registryAccess().registryOrThrow(Registries.ITEM)
                    .get(action.item());
            int capacity = playerInventoryCapacity(menu, containerSlots, item, action.item());
            if (capacity < action.requestedCount()) {
                throw new IllegalStateException(
                        "player main inventory can hold only " + capacity + " more "
                                + action.item() + "; requested " + action.requestedCount());
            }
            if (!menu.getCarried().isEmpty()) {
                throw new IllegalStateException("cannot withdraw while another item is held by the cursor");
            }
            action.open(menu, containerSlots, containerBefore);
            pickNextWithdrawSource(minecraft, player, action);
            return;
        }
        if (menu != action.menu()) {
            throw new IllegalStateException("container menu closed before withdrawal completed");
        }
        if (action.phase() == WithdrawPhase.WAIT_SOURCE_SYNC) {
            transferFromWithdrawCursor(minecraft, player, action);
            return;
        }
        if (action.phase() == WithdrawPhase.WAIT_TRANSFER_SYNC) {
            ItemStack carried = menu.getCarried();
            int carriedCount = carried.isEmpty() ? 0 : carried.getCount();
            if (!carried.isEmpty() && !itemName(carried.getItem()).equals(action.item().toString())) {
                throw new IllegalStateException("withdraw click replaced the carried item unexpectedly");
            }
            if (carriedCount != action.expectedCarriedCount()) {
                throw new IllegalStateException("withdraw click did not transfer the expected item count");
            }
            action.confirmPendingTransfer();
            int expectedPlayerCount = action.playerBefore() + action.transferredCount();
            if (inventoryCount(player, action.item()) != expectedPlayerCount) {
                throw new IllegalStateException("player inventory did not synchronize after withdrawal");
            }
            if (action.transferredCount() == action.requestedCount()) {
                if (carried.isEmpty()) {
                    action.setPhase(WithdrawPhase.VERIFY);
                } else {
                    click(minecraft.gameMode, player, menu, action.sourceSlot(), 0);
                    action.setPhase(WithdrawPhase.WAIT_RETURN_SYNC);
                }
            } else if (carried.isEmpty()) {
                pickNextWithdrawSource(minecraft, player, action);
            } else {
                action.setPhase(WithdrawPhase.WAIT_SOURCE_SYNC);
            }
            return;
        }
        if (action.phase() == WithdrawPhase.WAIT_RETURN_SYNC) {
            if (!menu.getCarried().isEmpty()) {
                return;
            }
            action.setPhase(WithdrawPhase.VERIFY);
            return;
        }
        verifyWithdraw(player, action);
    }

    /** Picks the next chest stack containing the requested item. */
    private static void pickNextWithdrawSource(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveWithdraw action
    ) {
        int sourceSlot = findContainerItemSlot(
                action.menu(), action.containerSlots(), action.item());
        if (sourceSlot < 0) {
            throw new IllegalStateException(
                    "chest item stacks changed before withdrawal completed: " + action.item());
        }
        action.setSourceSlot(sourceSlot);
        click(minecraft.gameMode, player, action.menu(), sourceSlot, 0);
        action.setPhase(WithdrawPhase.WAIT_SOURCE_SYNC);
    }

    /** Moves a full compatible chunk when possible, otherwise one exact item. */
    private static void transferFromWithdrawCursor(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveWithdraw action
    ) {
        AbstractContainerMenu menu = action.menu();
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty() || !itemName(carried.getItem()).equals(action.item().toString())) {
            throw new IllegalStateException("source click did not pick up " + action.item());
        }
        int targetSlot = findPlayerTargetSlot(
                menu, action.containerSlots(), carried.getItem(), action.item());
        if (targetSlot < 0) {
            throw new IllegalStateException("player main inventory has no slot available for " + action.item());
        }
        int capacity = slotCapacity(menu.getSlot(targetSlot), carried.getItem(), action.item());
        long remaining = action.requestedCount() - action.transferredCount();
        int moved;
        int button;
        if (capacity <= remaining && capacity <= carried.getCount()) {
            moved = capacity;
            button = 0;
        } else if (carried.getCount() <= remaining) {
            moved = carried.getCount();
            button = 0;
        } else {
            moved = 1;
            button = 1;
        }
        action.expectTransfer(moved, carried.getCount() - moved);
        click(minecraft.gameMode, player, menu, targetSlot, button);
        action.setPhase(WithdrawPhase.WAIT_TRANSFER_SYNC);
    }

    /** Requires five synchronized ticks before returning the exact withdrawal counts. */
    private void verifyWithdraw(LocalPlayer player, ActiveWithdraw action) {
        int playerAfter = inventoryCount(player, action.item());
        int containerAfter = menuItemCount(
                action.menu(), 0, action.containerSlots(), action.item());
        if (playerAfter != action.playerBefore() + action.requestedCount()
                || containerAfter != action.containerBefore() - action.requestedCount()) {
            action.resetStableTicks();
            return;
        }
        if (action.incrementStableTicks() < 5) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("transferred_count", action.transferredCount());
        response.addProperty("player_count_before", action.playerBefore());
        response.addProperty("player_count_after", playerAfter);
        response.addProperty("container_count_before", action.containerBefore());
        response.addProperty("container_count_after", containerAfter);
        finishActive(response);
    }

    /** Selects an empty bottle, performs one normal use, and awaits all three deltas. */
    private void tickFillWaterBottles(
            Minecraft minecraft,
            LocalPlayer player,
            ActiveFillWaterBottles action
    ) {
        if (minecraft.screen != null) {
            throw new IllegalStateException("a screen opened before bottle filling completed");
        }
        BlockState state = minecraft.level.getBlockState(action.target());
        if (!state.is(Blocks.WATER_CAULDRON) && !state.is(Blocks.CAULDRON)) {
            throw new IllegalStateException("target cauldron was removed or replaced");
        }
        if (action.phase() == FillPhase.PREPARE_HAND
                || action.phase() == FillPhase.WAIT_HAND_SYNC) {
            if (!player.getMainHandItem().is(Items.GLASS_BOTTLE)) {
                if (action.phase() == FillPhase.WAIT_HAND_SYNC) {
                    return;
                }
                int bottleSlot = findLargestMainInventoryItemSlot(player, Items.GLASS_BOTTLE);
                if (bottleSlot < 0) {
                    throw new IllegalStateException(
                            "player main inventory lost minecraft:glass_bottle");
                }
                if (bottleSlot < 9) {
                    player.getInventory().selected = bottleSlot;
                } else {
                    minecraft.gameMode.handlePickItem(bottleSlot);
                }
                action.setPhase(FillPhase.WAIT_HAND_SYNC);
                return;
            }
            BlockHitResult hit = validateCauldronTarget(minecraft, player, action.target());
            if (!minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                throw new IllegalStateException("Minecraft rejected the water cauldron interaction");
            }
            action.setPhase(FillPhase.WAIT_INTERACTION_SYNC);
            return;
        }
        if (action.phase() == FillPhase.WAIT_INTERACTION_SYNC) {
            int expectedFilled = action.filledCount() + 1;
            int emptyNow = mainInventoryCount(player, itemLocation(Items.GLASS_BOTTLE));
            int waterNow = mainWaterBottleCount(player);
            int levelNow = cauldronLevel(state);
            if (emptyNow != action.emptyBefore() - expectedFilled
                    || waterNow != action.waterBefore() + expectedFilled
                    || levelNow != action.levelBefore() - expectedFilled) {
                return;
            }
            action.confirmOneBottle();
            action.setPhase(action.filledCount() == action.requestedCount()
                    ? FillPhase.VERIFY
                    : FillPhase.PREPARE_HAND);
            return;
        }
        verifyFilledWaterBottles(player, state, action);
    }

    /** Requires five synchronized ticks before returning exact bottle and level counts. */
    private void verifyFilledWaterBottles(
            LocalPlayer player,
            BlockState state,
            ActiveFillWaterBottles action
    ) {
        int emptyAfter = mainInventoryCount(player, itemLocation(Items.GLASS_BOTTLE));
        int waterAfter = mainWaterBottleCount(player);
        int levelAfter = cauldronLevel(state);
        if (emptyAfter != action.emptyBefore() - action.requestedCount()
                || waterAfter != action.waterBefore() + action.requestedCount()
                || levelAfter != action.levelBefore() - action.requestedCount()) {
            action.resetStableTicks();
            return;
        }
        if (action.incrementStableTicks() < 5) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("filled_count", action.filledCount());
        response.addProperty("empty_bottles_before", action.emptyBefore());
        response.addProperty("empty_bottles_after", emptyAfter);
        response.addProperty("water_bottles_before", action.waterBefore());
        response.addProperty("water_bottles_after", waterAfter);
        response.addProperty("cauldron_level_before", action.levelBefore());
        response.addProperty("cauldron_level_after", levelAfter);
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
        if (action instanceof ActiveAttack attacking) {
            minecraft.options.keyUp.setDown(attacking.forwardWasDown());
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
        if (action instanceof ActiveWithdraw withdraw && player != null) {
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == withdraw.menu() && !menu.getCarried().isEmpty()
                    && minecraft.gameMode != null && withdraw.sourceSlot() >= 0) {
                click(minecraft.gameMode, player, menu, withdraw.sourceSlot(), 0);
            }
            if (menu != withdraw.inventoryMenu()) {
                player.closeContainer();
            }
        }
        if (action instanceof ActiveFillWaterBottles fill && player != null) {
            player.getInventory().selected = fill.originalSelectedSlot();
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

    /**
     * Separates a genuinely distant deposit target from a reachable target that
     * the live crosshair does not hit.
     */
    private static BlockHitResult validateDepositTarget(
            Minecraft minecraft,
            LocalPlayer player,
            BlockPos target
    ) {
        if (!minecraft.level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
            throw new IllegalArgumentException("target block is not loaded");
        }
        Vec3 eye = player.getEyePosition();
        Vec3 nearestTargetPoint = new Vec3(
                Mth.clamp(eye.x, target.getX(), target.getX() + 1.0),
                Mth.clamp(eye.y, target.getY(), target.getY() + 1.0),
                Mth.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        double reach = minecraft.gameMode.getPickRange();
        if (eye.distanceTo(nearestTargetPoint) > reach) {
            throw new IllegalArgumentException("target block is outside interaction reach");
        }
        HitResult currentHit = minecraft.hitResult;
        if (!(currentHit instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(target)) {
            throw new IllegalArgumentException("crosshair is not aimed at target chest");
        }
        return hit;
    }

    /** Separates a distant cauldron from a reachable one that is not under the crosshair. */
    private static BlockHitResult validateCauldronTarget(
            Minecraft minecraft,
            LocalPlayer player,
            BlockPos target
    ) {
        if (!minecraft.level.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4)) {
            throw new IllegalArgumentException("target block is not loaded");
        }
        Vec3 eye = player.getEyePosition();
        Vec3 nearestTargetPoint = new Vec3(
                Mth.clamp(eye.x, target.getX(), target.getX() + 1.0),
                Mth.clamp(eye.y, target.getY(), target.getY() + 1.0),
                Mth.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        double reach = minecraft.gameMode.getPickRange();
        if (eye.distanceTo(nearestTargetPoint) > reach) {
            throw new IllegalArgumentException("target block is outside interaction reach");
        }
        HitResult currentHit = minecraft.hitResult;
        if (!(currentHit instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(target)) {
            throw new IllegalArgumentException("crosshair is not aimed at target water cauldron");
        }
        return hit;
    }

    /** Requires a reachable visible target to retain the exact requested block type. */
    private static BlockHitResult validateExpectedTarget(
            Minecraft minecraft,
            LocalPlayer player,
            BlockPos target,
            String expectedBlock
    ) {
        BlockHitResult hit = validateTarget(minecraft, player, target);
        String actualBlock = blockName(minecraft.level.getBlockState(target));
        if (!actualBlock.equals(expectedBlock)) {
            throw new IllegalStateException(
                    "target block is " + actualBlock + ", not " + expectedBlock);
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

    /** Finds one currently loaded client entity without depending on numeric runtime IDs. */
    private static Entity findEntity(Minecraft minecraft, UUID uuid) {
        if (minecraft.level == null) {
            return null;
        }
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    /** Returns an exact registered entity type name from the active client level. */
    private static String entityName(Minecraft minecraft, Entity entity) {
        return minecraft.level.registryAccess().registryOrThrow(Registries.ENTITY_TYPE)
                .getKey(entity.getType()).toString();
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

    /** Returns the registry location for one known item. */
    private static ResourceLocation itemLocation(Item item) {
        return Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.ITEM)
                .getKey(item);
    }

    /** Counts water potions, excluding every other item sharing minecraft:potion. */
    private static int mainWaterBottleCount(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.POTION) && PotionUtils.getPotion(stack) == Potions.WATER) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Counts currently empty main-inventory slots. */
    private static int mainInventoryEmptySlots(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** Returns the largest stack size for one exact item in main inventory. */
    private static int largestMainInventoryStack(LocalPlayer player, Item item) {
        int largest = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                largest = Math.max(largest, stack.getCount());
            }
        }
        return largest;
    }

    /** Finds the largest matching stack so up to three interactions can reuse the hand. */
    private static int findLargestMainInventoryItemSlot(LocalPlayer player, Item item) {
        int found = -1;
        int largest = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item) && stack.getCount() > largest) {
                found = slot;
                largest = stack.getCount();
            }
        }
        return found;
    }

    /** Returns zero for an emptied cauldron and the live level for a water cauldron. */
    private static int cauldronLevel(BlockState state) {
        if (state.is(Blocks.CAULDRON)) {
            return 0;
        }
        if (state.is(Blocks.WATER_CAULDRON)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        throw new IllegalStateException("target cauldron was removed or replaced");
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

    /** Finds a chest slot containing the requested item. */
    private static int findContainerItemSlot(
            AbstractContainerMenu menu,
            int containerSlots,
            ResourceLocation item
    ) {
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && itemName(stack.getItem()).equals(item.toString())) {
                return slot;
            }
        }
        return -1;
    }

    /** Computes total compatible capacity in the 36 player slots exposed by a chest menu. */
    private static int playerInventoryCapacity(
            AbstractContainerMenu menu,
            int containerSlots,
            Item item,
            ResourceLocation itemId
    ) {
        int capacity = 0;
        for (int slot = containerSlots; slot < menu.slots.size(); slot++) {
            capacity += slotCapacity(menu.getSlot(slot), item, itemId);
        }
        return capacity;
    }

    /** Simulates the requested player-stack sequence without clicking the menu. */
    private static int containerCapacityForPlayerItems(
            AbstractContainerMenu menu,
            int containerSlots,
            ResourceLocation itemId,
            long requestedCount
    ) {
        ItemStack[] virtual = new ItemStack[containerSlots];
        for (int slot = 0; slot < containerSlots; slot++) {
            virtual[slot] = menu.getSlot(slot).getItem().copy();
        }
        int placed = 0;
        for (int sourceSlot = containerSlots;
                sourceSlot < menu.slots.size() && placed < requestedCount;
                sourceSlot++) {
            ItemStack source = menu.getSlot(sourceSlot).getItem();
            if (source.isEmpty() || !itemName(source.getItem()).equals(itemId.toString())) {
                continue;
            }
            int sourceRemaining = (int) Math.min(source.getCount(), requestedCount - placed);
            for (int targetSlot = 0; targetSlot < containerSlots && sourceRemaining > 0; targetSlot++) {
                ItemStack target = virtual[targetSlot];
                if (target.isEmpty() || !ItemStack.isSameItemSameTags(target, source)) {
                    continue;
                }
                int capacity = Math.max(0,
                        Math.min(target.getMaxStackSize(), menu.getSlot(targetSlot).getMaxStackSize(target))
                                - target.getCount());
                int moved = Math.min(capacity, sourceRemaining);
                target.grow(moved);
                sourceRemaining -= moved;
                placed += moved;
            }
            for (int targetSlot = 0; targetSlot < containerSlots && sourceRemaining > 0; targetSlot++) {
                if (!virtual[targetSlot].isEmpty() || !menu.getSlot(targetSlot).mayPlace(source)) {
                    continue;
                }
                int capacity = Math.min(
                        source.getMaxStackSize(), menu.getSlot(targetSlot).getMaxStackSize(source));
                int moved = Math.min(capacity, sourceRemaining);
                virtual[targetSlot] = source.copyWithCount(moved);
                sourceRemaining -= moved;
                placed += moved;
            }
        }
        return placed;
    }

    /** Finds a compatible partial player stack before selecting an empty slot. */
    private static int findPlayerTargetSlot(
            AbstractContainerMenu menu,
            int containerSlots,
            Item item,
            ResourceLocation itemId
    ) {
        int empty = -1;
        for (int slot = containerSlots; slot < menu.slots.size(); slot++) {
            Slot target = menu.getSlot(slot);
            ItemStack stack = target.getItem();
            if (stack.isEmpty()) {
                if (empty < 0 && target.mayPlace(new ItemStack(item))) {
                    empty = slot;
                }
            } else if (itemName(stack.getItem()).equals(itemId.toString())
                    && slotCapacity(target, item, itemId) > 0) {
                return slot;
            }
        }
        return empty;
    }

    /** Returns how many requested items one player slot can still accept. */
    private static int slotCapacity(Slot slot, Item item, ResourceLocation itemId) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            ItemStack candidate = new ItemStack(item);
            return slot.mayPlace(candidate)
                    ? Math.min(candidate.getMaxStackSize(), slot.getMaxStackSize(candidate))
                    : 0;
        }
        if (!itemName(stack.getItem()).equals(itemId.toString())) {
            return 0;
        }
        return Math.max(0,
                Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack)) - stack.getCount());
    }

    /** Finds a compatible partial stack before choosing the first empty container slot. */
    private static int findContainerTargetSlot(
            AbstractContainerMenu menu, int containerSlots, ItemStack carried) {
        int empty = -1;
        for (int slot = 0; slot < containerSlots; slot++) {
            Slot target = menu.getSlot(slot);
            ItemStack stack = target.getItem();
            if (stack.isEmpty()) {
                if (empty < 0 && target.mayPlace(carried)) {
                    empty = slot;
                }
            } else if (ItemStack.isSameItemSameTags(stack, carried)
                    && slotCapacityForStack(target, carried) > 0) {
                return slot;
            }
        }
        return empty;
    }

    /** Returns the remaining capacity for an exactly compatible carried stack. */
    private static int slotCapacityForStack(Slot slot, ItemStack carried) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return slot.mayPlace(carried)
                    ? Math.min(carried.getMaxStackSize(), slot.getMaxStackSize(carried))
                    : 0;
        }
        if (!ItemStack.isSameItemSameTags(stack, carried)) {
            return 0;
        }
        return Math.max(0,
                Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack)) - stack.getCount());
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

    /** Rejects overlapping native Minecraft actions. */
    private void requireNoActiveAction() {
        if (activeAction != null) {
            throw new IllegalStateException("another Minecraft action is already active");
        }
    }

    /** Returns a live survival-mode player and game-mode controller. */
    private static LocalPlayer requireSurvivalPlayer(Minecraft minecraft) {
        LocalPlayer player = requirePlayer(minecraft);
        if (minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            throw new IllegalStateException("Minecraft actions require survival mode");
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
                finishActive(new IllegalStateException("Minecraft action interrupted by shutdown")));
    }

    @FunctionalInterface
    private interface ClientRead {
        JsonObject run();
    }

    private sealed interface ActiveAction permits ActiveAim, ActiveAttack, ActiveBreak, ActiveDeposit,
            ActiveWithdraw, ActiveFillWaterBottles, ActiveSelectInventoryItem {
        LocalPlayer player();

        long deadlineNanos();

        CompletableFuture<JsonObject> result();
    }

    /** Retains native UUID combat state, safety bounds, and verification counters. */
    private static final class ActiveAttack implements ActiveAction {
        private final LocalPlayer player;
        private final UUID targetUuid;
        private final String expectedEntity;
        private final float botHealthBefore;
        private final double startX;
        private final double startZ;
        private final double targetStartX;
        private final double targetStartZ;
        private final boolean forwardWasDown;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private long lastStepNanos;
        private float lastTargetHealth;
        private long lastAttackNanos;
        private int attacksAttempted;
        private int attacksLanded;

        ActiveAttack(
                LocalPlayer player,
                UUID targetUuid,
                String expectedEntity,
                float botHealthBefore,
                float targetHealthBefore,
                double startX,
                double startZ,
                double targetStartX,
                double targetStartZ,
                boolean forwardWasDown,
                long lastStepNanos,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.targetUuid = targetUuid;
            this.expectedEntity = expectedEntity;
            this.botHealthBefore = botHealthBefore;
            this.lastTargetHealth = targetHealthBefore;
            this.startX = startX;
            this.startZ = startZ;
            this.targetStartX = targetStartX;
            this.targetStartZ = targetStartZ;
            this.forwardWasDown = forwardWasDown;
            this.lastStepNanos = lastStepNanos;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        void observeHealth(float health) {
            if (health < lastTargetHealth) {
                attacksLanded++;
            }
            lastTargetHealth = health;
        }

        boolean attackIntervalElapsed(long nowNanos) {
            return attacksAttempted == 0
                    || nowNanos - lastAttackNanos >= MIN_ATTACK_INTERVAL_NANOS;
        }

        void recordAttack(long nowNanos) {
            lastAttackNanos = nowNanos;
            attacksAttempted++;
        }
        @Override public LocalPlayer player() { return player; }
        UUID targetUuid() { return targetUuid; }
        String expectedEntity() { return expectedEntity; }
        float botHealthBefore() { return botHealthBefore; }
        double startX() { return startX; }
        double startZ() { return startZ; }
        double targetStartX() { return targetStartX; }
        double targetStartZ() { return targetStartZ; }
        boolean forwardWasDown() { return forwardWasDown; }
        long lastStepNanos() { return lastStepNanos; }
        void setLastStepNanos(long value) { lastStepNanos = value; }
        int attacksAttempted() { return attacksAttempted; }
        int attacksLanded() { return attacksLanded; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
    }

    /** Retains one requested main-hand selection until client/server state is stable. */
    private static final class ActiveSelectInventoryItem implements ActiveAction {
        private final LocalPlayer player;
        private final ResourceLocation item;
        private final int sourceSlot;
        private final boolean movedToHotbar;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private int stableTicks;

        ActiveSelectInventoryItem(
                LocalPlayer player,
                ResourceLocation item,
                int sourceSlot,
                boolean movedToHotbar,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.item = item;
            this.sourceSlot = sourceSlot;
            this.movedToHotbar = movedToHotbar;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        @Override public LocalPlayer player() { return player; }
        ResourceLocation item() { return item; }
        int sourceSlot() { return sourceSlot; }
        boolean movedToHotbar() { return movedToHotbar; }
        int incrementStableTicks() { return ++stableTicks; }
        void resetStableTicks() { stableTicks = 0; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
    }

    /** Retains one bounded automatic-aim command and its live-hit stability count. */
    private static final class ActiveAim implements ActiveAction {
        private final LocalPlayer player;
        private final BlockPos target;
        private final String expectedBlock;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private long lastStepNanos;
        private int stableHitTicks;

        ActiveAim(
                LocalPlayer player,
                BlockPos target,
                String expectedBlock,
                long lastStepNanos,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.expectedBlock = expectedBlock;
            this.lastStepNanos = lastStepNanos;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        public LocalPlayer player() { return player; }
        public BlockPos target() { return target; }
        public String expectedBlock() { return expectedBlock; }
        public long deadlineNanos() { return deadlineNanos; }
        public CompletableFuture<JsonObject> result() { return result; }
        long lastStepNanos() { return lastStepNanos; }
        void setLastStepNanos(long value) { lastStepNanos = value; }
        int incrementStableHitTicks() { return ++stableHitTicks; }
        void resetStableHitTicks() { stableHitTicks = 0; }
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

    private enum WithdrawPhase {
        WAIT_MENU,
        WAIT_SOURCE_SYNC,
        WAIT_TRANSFER_SYNC,
        WAIT_RETURN_SYNC,
        VERIFY
    }

    private enum FillPhase {
        PREPARE_HAND,
        WAIT_HAND_SYNC,
        WAIT_INTERACTION_SYNC,
        VERIFY
    }

    /** Stores the server menu and verification counters for one deposit operation. */
    private static final class ActiveDeposit implements ActiveAction {
        private final BlockPos target;
        private final LocalPlayer player;
        private final AbstractContainerMenu inventoryMenu;
        private final ResourceLocation item;
        private final long requestedCount;
        private final int playerBefore;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private AbstractContainerMenu menu;
        private int containerSlots;
        private int containerBefore;
        private int sourceSlot = -1;
        private int targetSlot = -1;
        private int transferredCount;
        private int pendingTransfer;
        private int expectedCarriedCount;
        private int stableTicks;
        private DepositPhase phase = DepositPhase.WAIT_MENU;

        ActiveDeposit(
                LocalPlayer player,
                BlockPos target,
                AbstractContainerMenu inventoryMenu,
                ResourceLocation item,
                long requestedCount,
                int playerBefore,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.inventoryMenu = inventoryMenu;
            this.item = item;
            this.requestedCount = requestedCount;
            this.playerBefore = playerBefore;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        void open(
                AbstractContainerMenu opened,
                int slots,
                int before
        ) {
            menu = opened;
            containerSlots = slots;
            containerBefore = before;
        }

        void setSourceSlot(int value) { sourceSlot = value; }
        void setTargetSlot(int value) { targetSlot = value; }
        void expectTransfer(int count, int expectedCarried) {
            pendingTransfer = count;
            expectedCarriedCount = expectedCarried;
        }
        void confirmPendingTransfer() {
            transferredCount += pendingTransfer;
            pendingTransfer = 0;
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
        long requestedCount() { return requestedCount; }
        int playerBefore() { return playerBefore; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
        AbstractContainerMenu menu() { return menu; }
        int containerSlots() { return containerSlots; }
        int containerBefore() { return containerBefore; }
        int sourceSlot() { return sourceSlot; }
        int targetSlot() { return targetSlot; }
        int transferredCount() { return transferredCount; }
        int expectedCarriedCount() { return expectedCarriedCount; }
        DepositPhase phase() { return phase; }
    }

    /** Stores menu counters and exact multi-stack progress for one withdrawal. */
    private static final class ActiveWithdraw implements ActiveAction {
        private final BlockPos target;
        private final LocalPlayer player;
        private final AbstractContainerMenu inventoryMenu;
        private final ResourceLocation item;
        private final long requestedCount;
        private final int playerBefore;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private AbstractContainerMenu menu;
        private int containerSlots;
        private int containerBefore;
        private int sourceSlot = -1;
        private int transferredCount;
        private int pendingTransfer;
        private int expectedCarriedCount;
        private int stableTicks;
        private WithdrawPhase phase = WithdrawPhase.WAIT_MENU;

        ActiveWithdraw(
                LocalPlayer player,
                BlockPos target,
                AbstractContainerMenu inventoryMenu,
                ResourceLocation item,
                long requestedCount,
                int playerBefore,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.inventoryMenu = inventoryMenu;
            this.item = item;
            this.requestedCount = requestedCount;
            this.playerBefore = playerBefore;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        void open(AbstractContainerMenu opened, int slots, int before) {
            menu = opened;
            containerSlots = slots;
            containerBefore = before;
        }

        void setSourceSlot(int value) { sourceSlot = value; }
        void expectTransfer(int count, int expectedCarried) {
            pendingTransfer = count;
            expectedCarriedCount = expectedCarried;
        }
        void confirmPendingTransfer() {
            transferredCount += pendingTransfer;
            pendingTransfer = 0;
        }
        int incrementStableTicks() { return ++stableTicks; }
        void resetStableTicks() { stableTicks = 0; }
        void setPhase(WithdrawPhase value) { phase = value; }

        BlockPos target() { return target; }
        @Override public LocalPlayer player() { return player; }
        AbstractContainerMenu inventoryMenu() { return inventoryMenu; }
        ResourceLocation item() { return item; }
        long requestedCount() { return requestedCount; }
        int playerBefore() { return playerBefore; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
        AbstractContainerMenu menu() { return menu; }
        int containerSlots() { return containerSlots; }
        int containerBefore() { return containerBefore; }
        int sourceSlot() { return sourceSlot; }
        int transferredCount() { return transferredCount; }
        int expectedCarriedCount() { return expectedCarriedCount; }
        WithdrawPhase phase() { return phase; }
    }

    /** Stores exact bottle and cauldron counters for one bounded fill operation. */
    private static final class ActiveFillWaterBottles implements ActiveAction {
        private final LocalPlayer player;
        private final BlockPos target;
        private final int requestedCount;
        private final int originalSelectedSlot;
        private final int emptyBefore;
        private final int waterBefore;
        private final int levelBefore;
        private final long deadlineNanos;
        private final CompletableFuture<JsonObject> result;
        private int filledCount;
        private int stableTicks;
        private FillPhase phase = FillPhase.PREPARE_HAND;

        ActiveFillWaterBottles(
                LocalPlayer player,
                BlockPos target,
                int requestedCount,
                int originalSelectedSlot,
                int emptyBefore,
                int waterBefore,
                int levelBefore,
                long deadlineNanos,
                CompletableFuture<JsonObject> result
        ) {
            this.player = player;
            this.target = target;
            this.requestedCount = requestedCount;
            this.originalSelectedSlot = originalSelectedSlot;
            this.emptyBefore = emptyBefore;
            this.waterBefore = waterBefore;
            this.levelBefore = levelBefore;
            this.deadlineNanos = deadlineNanos;
            this.result = result;
        }

        void confirmOneBottle() { filledCount++; }
        int incrementStableTicks() { return ++stableTicks; }
        void resetStableTicks() { stableTicks = 0; }
        void setPhase(FillPhase value) { phase = value; }

        @Override public LocalPlayer player() { return player; }
        BlockPos target() { return target; }
        int requestedCount() { return requestedCount; }
        int originalSelectedSlot() { return originalSelectedSlot; }
        int emptyBefore() { return emptyBefore; }
        int waterBefore() { return waterBefore; }
        int levelBefore() { return levelBefore; }
        int filledCount() { return filledCount; }
        FillPhase phase() { return phase; }
        @Override public long deadlineNanos() { return deadlineNanos; }
        @Override public CompletableFuture<JsonObject> result() { return result; }
    }
}
