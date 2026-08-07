// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Builds the structured observation consumed by the existing map service. */
public final class ObservationFactory {
    private static final Set<String> SEMANTIC_BLOCK_WHITELIST = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:crafting_table",
            "minecraft:furnace",
            "minecraft:cauldron",
            "minecraft:water_cauldron");
    private static final Set<String> SEMANTIC_ENTITY_WHITELIST = Set.of(
            "minecraft:zombie");
    private static final Map<String, DiscoveredBlock> DISCOVERED_BLOCKS = new LinkedHashMap<>();
    private static ClientLevel discoveredLevel;

    private ObservationFactory() {}

    /** Captures player state and a bounded surface-block scan on the client thread. */
    public static JsonObject create(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            throw new IllegalStateException("Minecraft client is not in a world");
        }

        Instant capturedAt = Instant.now();
        JsonObject observation = new JsonObject();
        observation.addProperty("schemaVersion", 1);
        observation.addProperty("timestamp", capturedAt.toString());
        observation.addProperty("coordinateFrame", "minecraft_world");
        observation.add("units", units());
        observation.addProperty("username", player.getGameProfile().getName());
        observation.addProperty("health", round(player.getHealth()));
        observation.addProperty("food", player.getFoodData().getFoodLevel());
        observation.addProperty("gameMode", gameMode(minecraft, player));
        observation.addProperty("dimension", level.dimension().location().toString());
        observation.add("position", position(player.getX(), player.getY(), player.getZ()));
        observation.addProperty("yaw", round(observationYaw(player.getYRot())));
        observation.addProperty("pitch", round(Math.toRadians(player.getXRot())));
        observation.addProperty("onGround", player.onGround());
        observation.add("held", held(level, player.getMainHandItem()));
        observation.add("nearbyEntities", nearbyEntities(level, player, capturedAt));
        observation.add("nearbyBlocks", nearbyBlocks(level, player.blockPosition()));
        observation.add("semanticBlocks", semanticBlocks(level, player, capturedAt));
        return observation;
    }

    /** Discovers visible whitelisted blocks locally and returns the persistent snapshot. */
    private static JsonArray semanticBlocks(ClientLevel level, LocalPlayer player, Instant capturedAt) {
        if (discoveredLevel != level) {
            DISCOVERED_BLOCKS.clear();
            discoveredLevel = level;
        }
        int radius = envInt("RBNX_MINECRAFT_BLOCK_RADIUS", 5);
        int verticalRadius = Math.min(radius, 4);
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        String dimension = level.dimension().location().toString();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    String id = blockId(dimension, cursor);
                    String name = level.registryAccess().registryOrThrow(Registries.BLOCK)
                            .getKey(level.getBlockState(cursor).getBlock()).toString();
                    if (!SEMANTIC_BLOCK_WHITELIST.contains(name)) {
                        DISCOVERED_BLOCKS.remove(id);
                    } else if (isVisible(level, player, cursor)) {
                        DISCOVERED_BLOCKS.put(id, new DiscoveredBlock(
                                id, name, cursor.immutable(), capturedAt.toString()));
                    }
                }
            }
        }
        JsonArray snapshot = new JsonArray();
        for (DiscoveredBlock block : DISCOVERED_BLOCKS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", block.id());
            entry.addProperty("name", block.name());
            entry.add("position", position(
                    block.position().getX(), block.position().getY(), block.position().getZ()));
            entry.addProperty("lastSeen", block.lastSeen());
            snapshot.add(entry);
        }
        return snapshot;
    }

    /** Returns true when the first collidable block on the eye ray is the candidate. */
    private static boolean isVisible(ClientLevel level, LocalPlayer player, BlockPos position) {
        Vec3 target = Vec3.atCenterOf(position);
        BlockHitResult hit = level.clip(new ClipContext(
                player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position);
    }

    /** Returns visible live semantic entities with stable UUID identity and geometry. */
    private static JsonArray nearbyEntities(
            ClientLevel level,
            LocalPlayer player,
            Instant capturedAt
    ) {
        double radius = envInt("RBNX_MINECRAFT_ENTITY_RADIUS", 16);
        double radiusSquared = radius * radius;
        String dimension = level.dimension().location().toString();
        JsonArray entities = new JsonArray();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)
                    || entity == player
                    || !living.isAlive()
                    || player.distanceToSqr(entity) > radiusSquared) {
                continue;
            }
            String name = level.registryAccess().registryOrThrow(Registries.ENTITY_TYPE)
                    .getKey(entity.getType()).toString();
            if (!SEMANTIC_ENTITY_WHITELIST.contains(name) || !player.hasLineOfSight(entity)) {
                continue;
            }
            Vec3 center = entity.getBoundingBox().getCenter();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "entity:" + dimension + "@" + entity.getUUID());
            entry.addProperty("uuid", entity.getUUID().toString());
            entry.addProperty("name", name);
            entry.add("position", position(center.x, center.y, center.z));
            entry.addProperty("yaw", round(observationYaw(entity.getYRot())));
            entry.addProperty("width", round(entity.getBoundingBox().getXsize()));
            entry.addProperty("height", round(entity.getBoundingBox().getYsize()));
            entry.addProperty("depth", round(entity.getBoundingBox().getZsize()));
            entry.addProperty("health", round(living.getHealth()));
            entry.addProperty("lastSeen", capturedAt.toString());
            entities.add(entry);
        }
        return entities;
    }

    /** Builds a stable identifier from the dimension and integer block coordinates. */
    private static String blockId(String dimension, BlockPos position) {
        return dimension + "@" + position.getX() + "," + position.getY() + "," + position.getZ();
    }

    /** Describes the coordinate units used by the compatibility observation. */
    private static JsonObject units() {
        JsonObject units = new JsonObject();
        units.addProperty("position", "block");
        units.addProperty("yaw", "radian");
        units.addProperty("pitch", "radian");
        return units;
    }

    /** Returns a compact position object rounded for model input. */
    private static JsonObject position(double x, double y, double z) {
        JsonObject position = new JsonObject();
        position.addProperty("x", round(x));
        position.addProperty("y", round(y));
        position.addProperty("z", round(z));
        return position;
    }

    /** Returns the held item using the same fields as the Mineflayer example. */
    private static com.google.gson.JsonElement held(ClientLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject held = new JsonObject();
        held.addProperty("name", level.registryAccess().registryOrThrow(Registries.ITEM)
                .getKey(stack.getItem()).getPath());
        held.addProperty("count", stack.getCount());
        return held;
    }

    /** Reads the current server-reported game mode without deprecated controller state. */
    private static String gameMode(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.getConnection() == null) {
            return "unknown";
        }
        PlayerInfo info = minecraft.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? "unknown" : info.getGameMode().getName();
    }

    /** Samples one non-air block per nearby horizontal column for map compatibility. */
    private static JsonArray nearbyBlocks(ClientLevel level, BlockPos origin) {
        int radius = envInt("RBNX_MINECRAFT_BLOCK_RADIUS", 5);
        JsonArray blocks = new JsonArray();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 1; dy >= -2; dy--) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.isAir()) {
                        JsonObject block = new JsonObject();
                        block.addProperty("name", level.registryAccess().registryOrThrow(Registries.BLOCK)
                                .getKey(state.getBlock()).getPath());
                        block.add("position", position(cursor.getX(), cursor.getY(), cursor.getZ()));
                        JsonObject relative = new JsonObject();
                        relative.addProperty("x", dx);
                        relative.addProperty("y", dy);
                        relative.addProperty("z", dz);
                        block.add("relative", relative);
                        blocks.add(block);
                        break;
                    }
                }
            }
        }
        return blocks;
    }

    /** Reads a non-negative integer environment setting with a safe default. */
    private static int envInt(String name, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback))));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Rounds noisy client values without changing their JSON number type. */
    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Converts Minecraft clockwise yaw to the established north-zero CCW convention. */
    private static double observationYaw(float minecraftYawDegrees) {
        double yaw = Math.PI - Math.toRadians(minecraftYawDegrees);
        return Math.atan2(Math.sin(yaw), Math.cos(yaw));
    }

    /** Persistent exact state for one block discovered by a visible local scan. */
    private record DiscoveredBlock(String id, String name, BlockPos position, String lastSeen) {}
}
