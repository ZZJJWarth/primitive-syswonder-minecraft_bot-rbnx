// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;

/**
 * Native Forge server-tick moving-cabin elevator engine (zero command blocks / zero redstone).
 *
 * Drives all registered elevators in every dimension:
 * - Smooth vertical step-by-step movement (1 block every STEP_INTERVAL_TICKS = 4 ticks = 5 blocks/s)
 * - Safe entity/player lifting & lowering with zero clipping and zero fall damage
 * - Clean landing & cabin door open/close operations
 * - Optional one-click shaft shell builder
 */
public final class ElevatorEngine {
    public static final int STEP_INTERVAL_TICKS = 4; // 5 blocks/second

    private static final BlockState LANDING_DOOR_CLOSED = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CABIN_DOOR_CLOSED = Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
    private static final BlockState CABIN_FLOOR = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState CABIN_CEILING = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState CABIN_LIGHT = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState SHAFT_WALL = Blocks.QUARTZ_BRICKS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private ElevatorEngine() {}

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ElevatorSavedData data = ElevatorSavedData.get(level);
            List<ElevatorSavedData.ElevatorRecord> list = data.listElevators();
            if (list.isEmpty()) {
                continue;
            }
            for (ElevatorSavedData.ElevatorRecord rec : list) {
                if (rec.moving) {
                    tickElevator(level, data, rec);
                }
            }
        }
    }

    private static void tickElevator(
            ServerLevel level, ElevatorSavedData data, ElevatorSavedData.ElevatorRecord rec) {
        if (rec.floors.isEmpty()) {
            rec.moving = false;
            data.markDirty();
            return;
        }
        if (rec.currentY == rec.targetY) {
            finishArrival(level, data, rec);
            return;
        }
        if (rec.stepCooldownTicks > 0) {
            rec.stepCooldownTicks--;
            return;
        }
        // Safety: always ensure all doors are closed (and doorway is clear) before stepping
        if (rec.doorOpen) {
            if (!closeAllDoors(level, rec)) {
                rec.moving = false;
                rec.targetY = rec.currentY;
                data.markDirty();
                return;
            }
        }
        int dir = rec.targetY > rec.currentY ? 1 : -1;
        stepCabin(level, rec, dir);
        rec.currentY += dir;
        rec.stepCooldownTicks = STEP_INTERVAL_TICKS - 1;
        data.markDirty();

        if (rec.currentY == rec.targetY) {
            finishArrival(level, data, rec);
        }
    }

    private static void finishArrival(
            ServerLevel level, ElevatorSavedData data, ElevatorSavedData.ElevatorRecord rec) {
        rec.moving = false;
        rec.stepCooldownTicks = 0;
        // Re-ensure cabin integrity on arrival
        renderCabin(level, rec, rec.currentY, false);
        playSound(level, rec, rec.currentY, SoundEvents.NOTE_BLOCK_PLING.get(), 0.9F, 1.35F);
        if (rec.openOnArrival) {
            rec.openOnArrival = false;
            setDoorAtCurrentFloor(level, data, rec, true);
        } else {
            data.markDirty();
        }
    }

    /** Moves the physical cabin by exactly +1 (up) or -1 (down), carrying all entities inside safely. */
    private static void stepCabin(ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int dir) {
        int oldY = rec.currentY;
        int newY = oldY + dir;
        int lMin = rec.minLateral();
        int lMax = rec.maxLateral();
        int dMax = 1 + rec.depth;
        int h = rec.height;

        AABB cabinBox = cabinEntityAabb(rec, oldY);
        List<Entity> riders = level.getEntities((Entity) null, cabinBox, e -> e.isAlive() && !e.isSpectator());

        if (dir > 0) {
            // Going UP:
            // 1. Place new ceiling and clear old ceiling (keep d=1 glass at oldY+h = newY+h-1)
            placeCeilingSlice(level, rec, newY + h);
            for (int l = lMin; l <= lMax; l++) {
                setBlock(level, rec.localPos(1, l, oldY + h), CABIN_DOOR_CLOSED);
                for (int d = 2; d <= dMax; d++) {
                    setBlock(level, rec.localPos(d, l, oldY + h), AIR);
                }
            }
            // 2. Lift riders BEFORE placing the new floor at oldY so feet never clip!
            for (Entity rider : riders) {
                shiftEntityVertical(rider, 1.0, newY);
            }
            // 3. Place new floor at newY-1 (= oldY) and clear old floor at oldY-1
            placeFloorSlice(level, rec, newY - 1);
            clearSlice(level, rec, oldY - 1);
        } else {
            // Going DOWN:
            // 1. Place new floor below first, then open old floor into interior + cabin glass door
            placeFloorSlice(level, rec, newY - 1);
            for (int l = lMin; l <= lMax; l++) {
                setBlock(level, rec.localPos(1, l, newY), CABIN_DOOR_CLOSED);
                for (int d = 2; d <= dMax; d++) {
                    setBlock(level, rec.localPos(d, l, newY), AIR);
                }
            }
            // 2. Smoothly lower riders with zero fall damage
            for (Entity rider : riders) {
                shiftEntityVertical(rider, -1.0, newY);
            }
            // 3. Place new ceiling at newY+h (= oldY+h-1) and clear old ceiling at oldY+h
            placeCeilingSlice(level, rec, newY + h);
            clearSlice(level, rec, oldY + h);
        }
    }

    private static void shiftEntityVertical(Entity entity, double deltaY, int minFloorY) {
        double targetY = Math.max(minFloorY + 0.01, entity.getY() + deltaY);
        entity.resetFallDistance();
        entity.setDeltaMovement(entity.getDeltaMovement().x, 0.0, entity.getDeltaMovement().z);
        if (entity instanceof ServerPlayer sp) {
            sp.connection.teleport(sp.getX(), targetY, sp.getZ(), sp.getYRot(), sp.getXRot());
        } else {
            entity.teleportTo(entity.getX(), targetY, entity.getZ());
        }
    }

    private static AABB cabinEntityAabb(ElevatorSavedData.ElevatorRecord rec, int cabinY) {
        BlockPos a = rec.localPos(1, rec.minLateral(), cabinY);
        BlockPos b = rec.localPos(1 + rec.depth, rec.maxLateral(), cabinY + rec.height);
        double minX = Math.min(a.getX(), b.getX()) - 0.1;
        double maxX = Math.max(a.getX(), b.getX()) + 1.1;
        double minY = cabinY - 0.5;
        double maxY = cabinY + rec.height + 0.2;
        double minZ = Math.min(a.getZ(), b.getZ()) - 0.1;
        double maxZ = Math.max(a.getZ(), b.getZ()) + 1.1;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Renders the complete cabin at `cabinY` (floor, ceiling, interior air, and open/closed cabin door). */
    public static void renderCabin(
            ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int cabinY, boolean doorOpen) {
        placeFloorSlice(level, rec, cabinY - 1);
        placeCeilingSlice(level, rec, cabinY + rec.height);
        int lMin = rec.minLateral();
        int lMax = rec.maxLateral();
        int dMax = 1 + rec.depth;
        BlockState doorState = doorOpen ? AIR : CABIN_DOOR_CLOSED;
        for (int y = cabinY; y < cabinY + rec.height; y++) {
            for (int l = lMin; l <= lMax; l++) {
                setBlock(level, rec.localPos(1, l, y), doorState);
                for (int d = 2; d <= dMax; d++) {
                    setBlock(level, rec.localPos(d, l, y), AIR);
                }
            }
        }
    }

    /** Light-curtain safety AABB spanning landing door (d=0) and cabin glass door (d=1). */
    public static AABB doorwaySensorAabb(ElevatorSavedData.ElevatorRecord rec, int floorY) {
        BlockPos a = rec.localPos(0, rec.minLateral(), floorY);
        BlockPos b = rec.localPos(1, rec.maxLateral(), floorY + rec.height - 1);
        double minX = Math.min(a.getX(), b.getX()) - 0.05;
        double maxX = Math.max(a.getX(), b.getX()) + 1.05;
        double minY = floorY;
        double maxY = floorY + rec.height;
        double minZ = Math.min(a.getZ(), b.getZ()) - 0.05;
        double maxZ = Math.max(a.getZ(), b.getZ()) + 1.05;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Returns all alive non-spectator entities currently obstructing the doorway light curtain. */
    public static List<Entity> doorwayObstacles(
            ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int floorY) {
        AABB box = doorwaySensorAabb(rec, floorY);
        return level.getEntities(
                (Entity) null,
                box,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator());
    }

    public static boolean isDoorBlockedAtFloor(
            ServerLevel level, ElevatorSavedData.ElevatorRecord rec, ElevatorSavedData.FloorStop stop) {
        return !doorwayObstacles(level, rec, stop.floorY()).isEmpty();
    }

    public static boolean isCurrentDoorBlocked(ServerLevel level, ElevatorSavedData.ElevatorRecord rec) {
        if (rec.moving) {
            return false;
        }
        return !doorwayObstacles(level, rec, rec.currentY).isEmpty();
    }

    private static void notifyDoorBlocked(
            ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int floorY) {
        playSound(level, rec, floorY, SoundEvents.NOTE_BLOCK_BASS.get(), 0.9F, 0.7F);
        AABB notifyBox = doorwaySensorAabb(rec, floorY).inflate(6.0, 2.0, 6.0);
        Component warn = Component.literal("电梯门防夹传感器检测到障碍物，已保持开门！").withStyle(ChatFormatting.RED);
        for (ServerPlayer sp : level.getEntitiesOfClass(ServerPlayer.class, notifyBox)) {
            sp.displayClientMessage(warn, true);
        }
    }

    /**
     * Opens or closes the landing door at a specific floor (and cabin door if the cabin is there).
     * Returns false if closing was refused by doorway light-curtain obstacle detection.
     */
    public static boolean setDoorAtFloor(
            ServerLevel level,
            ElevatorSavedData data,
            ElevatorSavedData.ElevatorRecord rec,
            ElevatorSavedData.FloorStop stop,
            boolean open) {
        if (open && (rec.moving || rec.currentY != stop.floorY())) {
            throw new IllegalStateException(
                    "cannot open door at " + stop.label + " while cabin is not stopped at that floor");
        }
        int fy = stop.floorY();
        if (!open && !doorwayObstacles(level, rec, fy).isEmpty()) {
            notifyDoorBlocked(level, rec, fy);
            return false;
        }
        BlockState hallState = open ? AIR : LANDING_DOOR_CLOSED;
        for (int y = fy; y < fy + rec.height; y++) {
            for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                setBlock(level, rec.localPos(0, l, y), hallState);
            }
        }
        if (!rec.moving && rec.currentY == fy) {
            BlockState cabinState = open ? AIR : CABIN_DOOR_CLOSED;
            for (int y = fy; y < fy + rec.height; y++) {
                for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                    setBlock(level, rec.localPos(1, l, y), cabinState);
                }
            }
            rec.doorOpen = open;
            playSound(level, rec, fy,
                    open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, 0.85F, 1.0F);
        }
        data.markDirty();
        return true;
    }

    public static boolean setDoorAtCurrentFloor(
            ServerLevel level, ElevatorSavedData data, ElevatorSavedData.ElevatorRecord rec, boolean open) {
        ElevatorSavedData.FloorStop stop = rec.findFloorByFloorY(rec.currentY);
        if (stop == null) {
            throw new IllegalStateException("cabin is not aligned with any registered floor");
        }
        return setDoorAtFloor(level, data, rec, stop, open);
    }

    /** Closes all doors after checking light-curtain safety at the currently open floor. */
    public static boolean closeAllDoors(ServerLevel level, ElevatorSavedData.ElevatorRecord rec) {
        if (!rec.moving && rec.doorOpen && !doorwayObstacles(level, rec, rec.currentY).isEmpty()) {
            notifyDoorBlocked(level, rec, rec.currentY);
            return false;
        }
        forceCloseAllDoors(level, rec);
        return true;
    }

    /** Unconditionally renders all landing & cabin doors closed (used during shaft build/init). */
    public static void forceCloseAllDoors(ServerLevel level, ElevatorSavedData.ElevatorRecord rec) {
        for (ElevatorSavedData.FloorStop stop : rec.floors) {
            int fy = stop.floorY();
            for (int y = fy; y < fy + rec.height; y++) {
                for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                    setBlock(level, rec.localPos(0, l, y), LANDING_DOOR_CLOSED);
                }
            }
        }
        if (!rec.moving) {
            for (int y = rec.currentY; y < rec.currentY + rec.height; y++) {
                for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                    setBlock(level, rec.localPos(1, l, y), CABIN_DOOR_CLOSED);
                }
            }
        }
        rec.doorOpen = false;
    }

    /**
     * Commands the elevator to move to `targetStop`, closing all doors first.
     * Returns false if closing was refused due to a doorway obstacle.
     */
    public static boolean dispatchToFloor(
            ServerLevel level,
            ElevatorSavedData data,
            ElevatorSavedData.ElevatorRecord rec,
            ElevatorSavedData.FloorStop targetStop,
            boolean openOnArrival) {
        int destY = targetStop.floorY();
        if (!rec.moving && rec.currentY == destY) {
            renderCabin(level, rec, rec.currentY, rec.doorOpen);
            if (openOnArrival && !rec.doorOpen) {
                setDoorAtFloor(level, data, rec, targetStop, true);
            }
            return true;
        }
        if (!closeAllDoors(level, rec)) {
            return false;
        }
        renderCabin(level, rec, rec.currentY, false);
        rec.targetY = destY;
        rec.moving = true;
        rec.openOnArrival = openOnArrival;
        rec.stepCooldownTicks = 2;
        data.markDirty();
        return true;
    }

    /**
     * One-click shaft & enclosure builder: carves the shaft interior between lowest & highest floor,
     * builds clean quartz-brick shaft outer walls (leaving landing doorways open at every floor),
     * closes all landing doors, and renders the cabin cleanly at `currentY`.
     */
    public static void buildShaftAndCabin(
            ServerLevel level, ElevatorSavedData data, ElevatorSavedData.ElevatorRecord rec) {
        if (rec.floors.isEmpty()) {
            return;
        }
        rec.moving = false;
        rec.openOnArrival = false;
        int minY = rec.minFloorY();
        int maxY = rec.maxFloorY();
        int yPit = minY - 2;
        int yCap = maxY + rec.height + 1;
        int lMin = rec.minLateral();
        int lMax = rec.maxLateral();
        int dMax = 1 + rec.depth;

        // 1. Clear interior shaft air column
        for (int y = minY - 1; y <= maxY + rec.height; y++) {
            for (int d = 1; d <= dMax; d++) {
                for (int l = lMin; l <= lMax; l++) {
                    setBlock(level, rec.localPos(d, l, y), AIR);
                }
            }
        }

        // 2. Pit floor and top shaft roof
        for (int d = 0; d <= dMax + 1; d++) {
            for (int l = lMin - 1; l <= lMax + 1; l++) {
                setBlock(level, rec.localPos(d, l, yPit), SHAFT_WALL);
                setBlock(level, rec.localPos(d, l, yCap), SHAFT_WALL);
            }
        }

        // 3. Outer shaft walls (left, right, back, and front wall outside doorways & station anchors)
        for (int y = minY - 1; y <= maxY + rec.height; y++) {
            // Left & right outer walls
            for (int d = 0; d <= dMax + 1; d++) {
                setBlock(level, rec.localPos(d, lMin - 1, y), SHAFT_WALL);
                setBlock(level, rec.localPos(d, lMax + 1, y), SHAFT_WALL);
            }
            // Back outer wall
            for (int l = lMin; l <= lMax; l++) {
                setBlock(level, rec.localPos(dMax + 1, l, y), SHAFT_WALL);
            }
            // Front wall (d = 0): preserve elevator_station blocks and landing doorway openings
            for (int l = lMin; l <= lMax; l++) {
                BlockPos p = rec.localPos(0, l, y);
                if (level.getBlockState(p).is(ElevatorControllerRegistry.ELEVATOR_STATION.get())) {
                    continue;
                }
                boolean isDoorOpening = false;
                for (ElevatorSavedData.FloorStop stop : rec.floors) {
                    if (y >= stop.floorY() && y < stop.floorY() + rec.height) {
                        isDoorOpening = true;
                        break;
                    }
                }
                if (!isDoorOpening) {
                    setBlock(level, p, SHAFT_WALL);
                }
            }
        }

        // 4. Snap cabin to a valid floor, close all landing doors, and render cabin
        if (rec.findFloorByFloorY(rec.currentY) == null) {
            rec.currentY = rec.floors.get(0).floorY();
        }
        rec.targetY = rec.currentY;
        forceCloseAllDoors(level, rec);
        renderCabin(level, rec, rec.currentY, false);
        data.markDirty();
    }

    /** Lightweight initialization when a new floor station is placed (does not overwrite surrounding building). */
    public static void ensureCabinAndLandingDoors(
            ServerLevel level, ElevatorSavedData data, ElevatorSavedData.ElevatorRecord rec) {
        if (rec.floors.isEmpty() || rec.moving) {
            return;
        }
        if (rec.findFloorByFloorY(rec.currentY) == null) {
            rec.currentY = rec.floors.get(0).floorY();
            rec.targetY = rec.currentY;
        }
        // Only render cabin + doors once at least 2 floors exist (or on single floor for immediate preview)
        forceCloseAllDoors(level, rec);
        renderCabin(level, rec, rec.currentY, false);
        data.markDirty();
    }

    private static void placeFloorSlice(ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int y) {
        int dMax = 1 + rec.depth;
        for (int d = 1; d <= dMax; d++) {
            for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                setBlock(level, rec.localPos(d, l, y), CABIN_FLOOR);
            }
        }
    }

    private static void placeCeilingSlice(ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int y) {
        int dMax = 1 + rec.depth;
        int midD = 1 + (rec.depth + 1) / 2;
        for (int d = 1; d <= dMax; d++) {
            for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                boolean isLight = (d == midD && l == 0);
                setBlock(level, rec.localPos(d, l, y), isLight ? CABIN_LIGHT : CABIN_CEILING);
            }
        }
    }

    private static void clearSlice(ServerLevel level, ElevatorSavedData.ElevatorRecord rec, int y) {
        int dMax = 1 + rec.depth;
        for (int d = 1; d <= dMax; d++) {
            for (int l = rec.minLateral(); l <= rec.maxLateral(); l++) {
                setBlock(level, rec.localPos(d, l, y), AIR);
            }
        }
    }

    private static void setBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.isInWorldBounds(pos)) {
            return;
        }
        // Ensure chunk is loaded before writing
        level.getChunk(pos);
        if (!level.getBlockState(pos).equals(state)) {
            level.setBlock(pos, state, 3);
        }
    }

    private static void playSound(
            ServerLevel level,
            ElevatorSavedData.ElevatorRecord rec,
            int y,
            net.minecraft.sounds.SoundEvent sound,
            float volume,
            float pitch) {
        BlockPos p = rec.localPos(1, 0, y + 1);
        level.playSound(null, p, sound, SoundSource.BLOCKS, volume, pitch);
    }
}
