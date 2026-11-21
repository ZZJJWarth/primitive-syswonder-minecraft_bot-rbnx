// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Persists placed RTK base station block coordinates per dimension. */
public final class RtkStationSavedData extends SavedData {
    private static final String DATA_NAME = "robonix_rtk_stations";

    public record Station(String id, int x, int y, int z) {
        public double east() {
            return x + 0.5;
        }

        public double north() {
            return -(z + 0.5);
        }

        public double up() {
            return y;
        }
    }

    private final Map<BlockPos, String> stations = new LinkedHashMap<>();
    private int nextId = 1;

    public RtkStationSavedData() {}

    public static RtkStationSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RtkStationSavedData::new, RtkStationSavedData::load, null),
                DATA_NAME);
    }

    private static RtkStationSavedData load(CompoundTag tag) {
        RtkStationSavedData data = new RtkStationSavedData();
        data.nextId = Math.max(1, tag.getInt("next_id"));
        ListTag list = tag.getList("stations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("id");
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            if (id == null || id.isBlank()) {
                id = "rtk_" + (data.nextId++);
            }
            data.stations.put(pos, id);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("next_id", nextId);
        ListTag list = new ListTag();
        for (var entry : stations.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.getValue());
            item.putInt("x", entry.getKey().getX());
            item.putInt("y", entry.getKey().getY());
            item.putInt("z", entry.getKey().getZ());
            list.add(item);
        }
        tag.put("stations", list);
        return tag;
    }

    public String addStation(BlockPos pos) {
        BlockPos key = pos.immutable();
        String existing = stations.get(key);
        if (existing != null) {
            return existing;
        }
        int candidate = 1;
        while (stations.containsValue("rtk_" + candidate)) {
            candidate++;
        }
        String id = "rtk_" + candidate;
        nextId = Math.max(nextId, candidate + 1);
        stations.put(key, id);
        setDirty();
        return id;
    }

    public boolean removeStation(BlockPos pos) {
        String removed = stations.remove(pos.immutable());
        if (removed != null) {
            setDirty();
            return true;
        }
        return false;
    }

    /** Drops any recorded station in a loaded chunk whose block is no longer an RTK base station. */
    public boolean pruneMissing(ServerLevel level) {
        boolean changed = false;
        var it = stations.entrySet().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next().getKey();
            if (level.hasChunkAt(pos) && !level.getBlockState(pos).is(RtkRegistry.RTK_BASE_STATION.get())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public List<Station> listStations() {
        List<Station> out = new ArrayList<>(stations.size());
        for (var entry : stations.entrySet()) {
            BlockPos p = entry.getKey();
            out.add(new Station(entry.getValue(), p.getX(), p.getY(), p.getZ()));
        }
        return out;
    }
}
