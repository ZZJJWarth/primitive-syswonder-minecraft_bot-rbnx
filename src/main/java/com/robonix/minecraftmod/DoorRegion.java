// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;

/** Inclusive block bounds, matching vanilla fill. Observation never changes the world. */
public record DoorRegion(String dimension, BlockPos min, BlockPos max, String closedBlock) {
    public DoorRegion {
        for (BlockPos corner : java.util.List.of(min, max)) {
            if (corner.getX() < -30000000 || corner.getX() >= 30000000
                    || corner.getZ() < -30000000 || corner.getZ() >= 30000000
                    || corner.getY() < -2048 || corner.getY() > 2047) {
                throw new IllegalArgumentException("door region coordinates are outside encodable world bounds");
            }
        }
        long volume = 1;
        for (int axis = 0; axis < 3; axis++) {
            long extent = (long) coordinate(max, axis) - coordinate(min, axis) + 1;
            if (extent <= 0 || extent > 4096) throw new IllegalArgumentException("invalid door region bounds");
            volume *= extent;
            if (volume > 4096) throw new IllegalArgumentException("door region exceeds 4096 blocks");
        }
    }

    private static int coordinate(BlockPos pos, int axis) {
        return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
    }

    public static DoorRegion parse(JsonObject value) {
        return new DoorRegion(string(value, "dimension"), corner(value, "min"), corner(value, "max"),
                string(value, "closed_block"));
    }

    private static String string(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive()
                || !value.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("door_region requires string field: " + name);
        }
        return value.get(name).getAsString();
    }

    private static BlockPos corner(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonArray() || value.getAsJsonArray(name).size() != 3) {
            throw new IllegalArgumentException("door_region requires three integer coordinates: " + name);
        }
        int[] coordinates = new int[3];
        for (int i = 0; i < 3; i++) {
            var number = value.getAsJsonArray(name).get(i);
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("door region coordinates must be integers");
            }
            try {
                coordinates[i] = number.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("door region coordinates must be int32 integers", exception);
            }
        }
        return new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
    }

    public JsonObject observe(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("door observation requires server thread");
        if (!level.dimension().location().toString().equals(dimension)) {
            throw new IllegalArgumentException("door region and controller must share a dimension");
        }
        var id = new ResourceLocation(closedBlock);
        if (!ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("unknown closed_block: " + closedBlock);
        var block = ForgeRegistries.BLOCKS.getValue(id);
        if (block.defaultBlockState().isAir()) throw new IllegalArgumentException("closed_block must not be air");
        int air = 0, closed = 0, total = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
                throw new IllegalArgumentException("door region is outside world bounds or unloaded");
            }
            var state = level.getBlockState(pos);
            total++;
            if (state.isAir()) air++;
            else if (state.is(block)) closed++;
        }
        JsonObject result = new JsonObject();
        result.addProperty("state", air == total ? "open" : closed == total ? "closed" : "unknown");
        result.addProperty("total_blocks", total);
        result.addProperty("air_blocks", air);
        result.addProperty("closed_blocks", closed);
        result.addProperty("other_blocks", total - air - closed);
        return result;
    }
}
