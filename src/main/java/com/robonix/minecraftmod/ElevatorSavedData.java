// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists all reusable native moving-cabin elevators in one Minecraft dimension.
 *
 * Stations placed in the same vertical (anchorX, anchorZ) column are automatically
 * grouped into a single elevator shaft, sorted bottom-to-top as 1F, 2F, 3F, ...
 */
public final class ElevatorSavedData extends SavedData {
    private static final String DATA_NAME = "robonix_elevators";

    public static final int DEFAULT_WIDTH = 3;
    public static final int DEFAULT_DEPTH = 3;
    public static final int DEFAULT_HEIGHT = 3;

    public static final class FloorStop {
        public final int anchorY;
        public String label;
        public boolean customLabel;

        public FloorStop(int anchorY, String label, boolean customLabel) {
            this.anchorY = anchorY;
            this.label = label;
            this.customLabel = customLabel;
        }

        /** Feet elevation Y of an entity standing on this floor. */
        public int floorY() {
            return anchorY + 1;
        }
    }

    public static final class ElevatorRecord {
        public final String id;
        public final int anchorX;
        public final int anchorZ;
        public Direction facing; // Horizontal direction pointing INTO the elevator shaft
        public int width = DEFAULT_WIDTH;
        public int depth = DEFAULT_DEPTH;
        public int height = DEFAULT_HEIGHT;
        public final List<FloorStop> floors = new ArrayList<>();

        public int currentY;
        public int targetY;
        public boolean moving;
        public boolean doorOpen;
        public boolean openOnArrival;
        public int stepCooldownTicks;

        public ElevatorRecord(String id, int anchorX, int anchorZ, Direction facing, int initialFloorY) {
            this.id = id;
            this.anchorX = anchorX;
            this.anchorZ = anchorZ;
            this.facing = facing.getAxis().isHorizontal() ? facing : Direction.EAST;
            this.currentY = initialFloorY;
            this.targetY = initialFloorY;
        }

        public int halfWidth() {
            return width / 2;
        }

        public int minLateral() {
            return -halfWidth();
        }

        public int maxLateral() {
            return width - 1 - halfWidth();
        }

        /** Convert local (depthOffset d into shaft, lateral l to right of facing, world y) to BlockPos. */
        public BlockPos localPos(int d, int l, int y) {
            Direction right = facing.getClockWise();
            int x = anchorX + facing.getStepX() * d + right.getStepX() * l;
            int z = anchorZ + facing.getStepZ() * d + right.getStepZ() * l;
            return new BlockPos(x, y, z);
        }

        public void sortAndAssignLabels() {
            floors.sort(Comparator.comparingInt(f -> f.anchorY));
            int autoIdx = 1;
            for (FloorStop stop : floors) {
                if (!stop.customLabel || stop.label == null || stop.label.isBlank()) {
                    stop.label = autoIdx + "F";
                    stop.customLabel = false;
                }
                autoIdx++;
            }
        }

        public FloorStop findFloorByAnchorY(int anchorY) {
            for (FloorStop stop : floors) {
                if (stop.anchorY == anchorY) {
                    return stop;
                }
            }
            return null;
        }

        public FloorStop findFloorByFloorY(int floorY) {
            for (FloorStop stop : floors) {
                if (stop.floorY() == floorY) {
                    return stop;
                }
            }
            return null;
        }

        public FloorStop resolveFloor(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String norm = normalizeToken(raw);
            for (FloorStop stop : floors) {
                String labelNorm = normalizeToken(stop.label);
                if (labelNorm.equals(norm)) {
                    return stop;
                }
                // Match numeric aliases like "2", "2楼", "2层", "lab_2f" against "2F"
                if (labelNorm.endsWith("f")) {
                    String num = labelNorm.substring(0, labelNorm.length() - 1);
                    if (norm.equals(num) || norm.equals(num + "楼") || norm.equals(num + "层")
                            || norm.endsWith(labelNorm)) {
                        return stop;
                    }
                }
            }
            return null;
        }

        public String currentFloorLabel() {
            FloorStop stop = findFloorByFloorY(currentY);
            return stop != null ? stop.label : "";
        }

        public String targetFloorLabel() {
            FloorStop stop = findFloorByFloorY(targetY);
            return stop != null ? stop.label : "";
        }

        public int minFloorY() {
            return floors.isEmpty() ? currentY : floors.get(0).floorY();
        }

        public int maxFloorY() {
            return floors.isEmpty() ? currentY : floors.get(floors.size() - 1).floorY();
        }

        /**
         * Pure IoT runtime status JSON: contains ONLY elevator ID, state, floor labels,
         * door open flag, and doorway light-curtain obstacle flag.
         * Strictly ZERO world coordinates, cabin bounds, or elevation_y at runtime!
         */
        public JsonObject toRuntimeJson(ServerLevel level) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("state", moving ? "MOVING" : "IDLE");
            obj.addProperty("current_floor", currentFloorLabel());
            obj.addProperty("target_floor", targetFloorLabel());
            obj.addProperty("door_open", doorOpen);
            obj.addProperty("door_blocked", level != null && ElevatorEngine.isCurrentDoorBlocked(level, this));
            JsonArray floorArr = new JsonArray();
            for (FloorStop stop : floors) {
                floorArr.add(stop.label);
            }
            obj.add("floors", floorArr);
            return obj;
        }

        /**
         * Offline survey geometry emitted ONLY when a builder right-clicks this floor's
         * elevator_station while holding `robonix_minecraft:indoor_mapper`.
         * Saved into the offline IndoorFloorMap JSON so robot runtime needs zero coord queries.
         */
        public JsonObject floorSurveyExtraJson(FloorStop stop) {
            int fy = stop.floorY();
            // Usable cabin interior: d in [2 .. 1 + depth] (strictly excluding d=1 glass door)
            double[] cabinBox = enuBox(2, 1 + depth, minLateral(), maxLateral(), fy);
            // Doorway region: d in [0 .. 1] (landing door + cabin glass door)
            double[] doorBox = enuBox(0, 1, minLateral(), maxLateral(), fy);
            // Safe cabin center in ENU
            double cabinX = 0.5 * (cabinBox[0] + cabinBox[1]);
            double cabinY = 0.5 * (cabinBox[2] + cabinBox[3]);
            // Waiting point outside the landing door (~1.3 m clear of outer door face)
            double waitMcX = anchorX + 0.5 - 1.8 * facing.getStepX();
            double waitMcZ = anchorZ + 0.5 - 1.8 * facing.getStepZ();
            double doorX = waitMcX;
            double doorY = -waitMcZ;

            JsonObject obj = new JsonObject();
            obj.addProperty("elevator_id", id);
            obj.addProperty("floor_label", stop.label);
            obj.addProperty("facing", facing.getName());
            obj.addProperty("elevation_y", fy);
            obj.addProperty("door_x", Math.round(doorX * 1000.0) / 1000.0);
            obj.addProperty("door_y", Math.round(doorY * 1000.0) / 1000.0);
            obj.addProperty("cabin_x", Math.round(cabinX * 1000.0) / 1000.0);
            obj.addProperty("cabin_y", Math.round(cabinY * 1000.0) / 1000.0);

            JsonArray cArr = new JsonArray();
            for (double v : cabinBox) {
                cArr.add(Math.round(v * 1000.0) / 1000.0);
            }
            obj.add("cabin_bounds_enu", cArr);

            JsonArray dArr = new JsonArray();
            for (double v : doorBox) {
                dArr.add(Math.round(v * 1000.0) / 1000.0);
            }
            obj.add("door_bounds_enu", dArr);

            JsonArray floorArr = new JsonArray();
            for (FloorStop f : floors) {
                floorArr.add(f.label);
            }
            obj.add("floors", floorArr);
            return obj;
        }

        private double[] enuBox(int dMin, int dMax, int lMin, int lMax, int y) {
            BlockPos a = localPos(dMin, lMin, y);
            BlockPos b = localPos(dMax, lMax, y);
            int minX = Math.min(a.getX(), b.getX());
            int maxX = Math.max(a.getX(), b.getX());
            int minZ = Math.min(a.getZ(), b.getZ());
            int maxZ = Math.max(a.getZ(), b.getZ());
            // Inclusive MC blocks [minX..maxX], [minZ..maxZ] -> continuous ENU [minX, maxX+1], [-(maxZ+1), -minZ]
            return new double[] {minX, maxX + 1.0, -(maxZ + 1.0), -minZ};
        }

        private static String normalizeToken(String raw) {
            return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-·]+", "");
        }
    }

    private final Map<String, ElevatorRecord> elevators = new LinkedHashMap<>();

    public ElevatorSavedData() {}

    public static String columnId(int x, int z) {
        return "elev_" + x + "_" + z;
    }

    public static ElevatorSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ElevatorSavedData::new, ElevatorSavedData::load, null),
                DATA_NAME);
    }

    private static ElevatorSavedData load(CompoundTag tag) {
        ElevatorSavedData data = new ElevatorSavedData();
        ListTag list = tag.getList("elevators", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            int ax = item.getInt("anchor_x");
            int az = item.getInt("anchor_z");
            String id = item.getString("id");
            if (id == null || id.isBlank()) {
                id = columnId(ax, az);
            }
            Direction facing = Direction.byName(item.getString("facing"));
            if (facing == null || !facing.getAxis().isHorizontal()) {
                facing = Direction.EAST;
            }
            int currentY = item.getInt("current_y");
            ElevatorRecord rec = new ElevatorRecord(id, ax, az, facing, currentY);
            rec.width = clamp(item.contains("width") ? item.getInt("width") : DEFAULT_WIDTH, 2, 7);
            rec.depth = clamp(item.contains("depth") ? item.getInt("depth") : DEFAULT_DEPTH, 2, 7);
            rec.height = clamp(item.contains("height") ? item.getInt("height") : DEFAULT_HEIGHT, 3, 6);
            rec.targetY = item.contains("target_y") ? item.getInt("target_y") : currentY;
            rec.moving = item.getBoolean("moving");
            rec.doorOpen = item.getBoolean("door_open");
            rec.openOnArrival = item.getBoolean("open_on_arrival");

            ListTag flist = item.getList("floors", Tag.TAG_COMPOUND);
            for (int j = 0; j < flist.size(); j++) {
                CompoundTag ftag = flist.getCompound(j);
                rec.floors.add(new FloorStop(
                        ftag.getInt("anchor_y"),
                        ftag.getString("label"),
                        ftag.getBoolean("custom_label")));
            }
            rec.sortAndAssignLabels();
            if (!rec.floors.isEmpty() && rec.findFloorByFloorY(rec.currentY) == null && !rec.moving) {
                rec.currentY = rec.floors.get(0).floorY();
                rec.targetY = rec.currentY;
            }
            data.elevators.put(rec.id, rec);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ElevatorRecord rec : elevators.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("id", rec.id);
            item.putInt("anchor_x", rec.anchorX);
            item.putInt("anchor_z", rec.anchorZ);
            item.putString("facing", rec.facing.getName());
            item.putInt("width", rec.width);
            item.putInt("depth", rec.depth);
            item.putInt("height", rec.height);
            item.putInt("current_y", rec.currentY);
            item.putInt("target_y", rec.targetY);
            item.putBoolean("moving", rec.moving);
            item.putBoolean("door_open", rec.doorOpen);
            item.putBoolean("open_on_arrival", rec.openOnArrival);
            ListTag flist = new ListTag();
            for (FloorStop stop : rec.floors) {
                CompoundTag ftag = new CompoundTag();
                ftag.putInt("anchor_y", stop.anchorY);
                ftag.putString("label", stop.label == null ? "" : stop.label);
                ftag.putBoolean("custom_label", stop.customLabel);
                flist.add(ftag);
            }
            item.put("floors", flist);
            list.add(item);
        }
        tag.put("elevators", list);
        return tag;
    }

    public ElevatorRecord registerStation(BlockPos pos, Direction facing) {
        String id = columnId(pos.getX(), pos.getZ());
        ElevatorRecord rec = elevators.get(id);
        if (rec == null) {
            rec = new ElevatorRecord(id, pos.getX(), pos.getZ(), facing, pos.getY() + 1);
            elevators.put(id, rec);
        } else if (rec.floors.isEmpty() && facing.getAxis().isHorizontal()) {
            rec.facing = facing;
        }
        if (rec.findFloorByAnchorY(pos.getY()) == null) {
            rec.floors.add(new FloorStop(pos.getY(), "", false));
            rec.sortAndAssignLabels();
            if (rec.floors.size() == 1) {
                rec.currentY = rec.floors.get(0).floorY();
                rec.targetY = rec.currentY;
            }
        }
        setDirty();
        return rec;
    }

    public ElevatorRecord removeStation(BlockPos pos) {
        String id = columnId(pos.getX(), pos.getZ());
        ElevatorRecord rec = elevators.get(id);
        if (rec == null) {
            return null;
        }
        boolean removed = rec.floors.removeIf(stop -> stop.anchorY == pos.getY());
        if (!removed) {
            return rec;
        }
        if (rec.floors.isEmpty()) {
            elevators.remove(id);
            setDirty();
            return null;
        }
        rec.sortAndAssignLabels();
        if (rec.findFloorByFloorY(rec.currentY) == null && !rec.moving) {
            rec.currentY = rec.floors.get(0).floorY();
            rec.targetY = rec.currentY;
        }
        setDirty();
        return rec;
    }

    public ElevatorRecord getById(String id) {
        return id == null ? null : elevators.get(id.trim());
    }

    public ElevatorRecord getByColumn(int x, int z) {
        return elevators.get(columnId(x, z));
    }

    public List<ElevatorRecord> listElevators() {
        return new ArrayList<>(elevators.values());
    }

    /** Removes any recorded stations in loaded chunks whose block is no longer an elevator_station. */
    public boolean pruneMissing(ServerLevel level) {
        boolean changed = false;
        var it = elevators.entrySet().iterator();
        while (it.hasNext()) {
            ElevatorRecord rec = it.next().getValue();
            boolean floorChanged = rec.floors.removeIf(stop -> {
                BlockPos p = new BlockPos(rec.anchorX, stop.anchorY, rec.anchorZ);
                return level.hasChunkAt(p)
                        && !level.getBlockState(p).is(ElevatorControllerRegistry.ELEVATOR_STATION.get());
            });
            if (floorChanged) {
                changed = true;
                if (rec.floors.isEmpty()) {
                    it.remove();
                } else {
                    rec.sortAndAssignLabels();
                }
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public void markDirty() {
        setDirty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
