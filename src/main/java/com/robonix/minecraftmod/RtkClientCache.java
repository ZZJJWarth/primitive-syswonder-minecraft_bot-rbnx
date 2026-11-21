// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side cache of RTK base stations synchronized from the server. */
public final class RtkClientCache {
    private static final Map<String, List<RtkStationSavedData.Station>> BY_DIMENSION = new ConcurrentHashMap<>();

    private RtkClientCache() {}

    public static void update(String dimension, List<RtkStationSavedData.Station> stations) {
        BY_DIMENSION.put(dimension, List.copyOf(stations));
    }

    public static List<RtkStationSavedData.Station> stationsFor(String dimension) {
        return BY_DIMENSION.getOrDefault(dimension, List.of());
    }

    public static void clear() {
        BY_DIMENSION.clear();
    }
}
