// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure horizontal geometry for bounded area searches.
 *
 * <p>Callers supply a flat minecraft_odom polygon as east/north pairs. Block
 * centers are converted with {@code x = east + 0.5} and {@code z = -north + 0.5}.
 */
public final class AreaSearch {
    public static final int MIN_VERTICES = 3;
    public static final int MAX_VERTICES = 64;
    public static final int MAX_BOUNDING_CELLS = 1_000_000;
    public static final double MAX_WORLD_COORDINATE = 30_000_000.0;

    private AreaSearch() {}

    /** Requires a flat, finite, non-degenerate polygon with 3-64 vertices. */
    public static void validate(double[] pointsXy) {
        if (pointsXy == null || pointsXy.length % 2 != 0) {
            throw new IllegalArgumentException("points_xy must contain paired east/north coordinates");
        }
        int vertices = pointsXy.length / 2;
        if (vertices < MIN_VERTICES || vertices > MAX_VERTICES) {
            throw new IllegalArgumentException("points_xy must contain from 3 through 64 vertices");
        }
        for (double value : pointsXy) {
            if (!Double.isFinite(value) || Math.abs(value) > MAX_WORLD_COORDINATE) {
                throw new IllegalArgumentException("points_xy coordinates must be finite world coordinates");
            }
        }
        double minEast = Double.POSITIVE_INFINITY;
        double maxEast = Double.NEGATIVE_INFINITY;
        double minNorth = Double.POSITIVE_INFINITY;
        double maxNorth = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < pointsXy.length; index += 2) {
            minEast = Math.min(minEast, pointsXy[index]);
            maxEast = Math.max(maxEast, pointsXy[index]);
            minNorth = Math.min(minNorth, pointsXy[index + 1]);
            maxNorth = Math.max(maxNorth, pointsXy[index + 1]);
        }
        if (!(maxEast > minEast) || !(maxNorth > minNorth)) {
            throw new IllegalArgumentException("points_xy must enclose a non-zero area");
        }
        double twiceArea = 0.0;
        for (int index = 0; index < pointsXy.length; index += 2) {
            int next = (index + 2) % pointsXy.length;
            twiceArea += pointsXy[index] * pointsXy[next + 1]
                    - pointsXy[next] * pointsXy[index + 1];
        }
        if (!(Math.abs(twiceArea) > 1.0e-6)) {
            throw new IllegalArgumentException("points_xy must enclose a non-zero area");
        }
        double boundingCells = (Math.ceil(maxEast) - Math.floor(minEast) + 3.0)
                * (Math.ceil(maxNorth) - Math.floor(minNorth) + 3.0);
        if (boundingCells > MAX_BOUNDING_CELLS) {
            throw new IllegalArgumentException(
                    "search area bounding box is too large; split it into smaller regions");
        }
    }

    /** Converts interleaved east/north vertices to native x/z vertices. */
    public static double[] toNative(double[] pointsXy) {
        double[] nativePoints = new double[pointsXy.length];
        for (int index = 0; index < pointsXy.length; index += 2) {
            nativePoints[index] = pointsXy[index];
            nativePoints[index + 1] = -pointsXy[index + 1];
        }
        return nativePoints;
    }

    /** Even-odd point-in-polygon test for an interleaved native x/z polygon. */
    public static boolean inside(double px, double pz, double[] nativePoints) {
        boolean inside = false;
        int count = nativePoints.length / 2;
        for (int index = 0, previous = count - 1; index < count; previous = index++) {
            double xi = nativePoints[index * 2];
            double zi = nativePoints[index * 2 + 1];
            double xj = nativePoints[previous * 2];
            double zj = nativePoints[previous * 2 + 1];
            if ((zi > pz) != (zj > pz)
                    && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Returns floor(min)-1 through ceil(max)+1 integer bounds for native x/z. */
    public static int[] bounds(double[] nativePoints) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < nativePoints.length; index += 2) {
            minX = Math.min(minX, nativePoints[index]);
            maxX = Math.max(maxX, nativePoints[index]);
            minZ = Math.min(minZ, nativePoints[index + 1]);
            maxZ = Math.max(maxZ, nativePoints[index + 1]);
        }
        return new int[]{
                (int) Math.floor(minX) - 1,
                (int) Math.ceil(maxX) + 1,
                (int) Math.floor(minZ) - 1,
                (int) Math.ceil(maxZ) + 1};
    }

    /**
     * Returns packed x/z columns whose block centers lie inside the polygon,
     * ordered by squared horizontal distance from the player.
     */
    public static long[] columns(
            double[] nativePoints,
            int[] bounds,
            double playerX,
            double playerZ,
            int maxColumns
    ) {
        List<Long> candidates = new ArrayList<>();
        for (int x = bounds[0]; x <= bounds[1]; x++) {
            double centerX = x + 0.5;
            for (int z = bounds[2]; z <= bounds[3]; z++) {
                if (!inside(centerX, z + 0.5, nativePoints)) {
                    continue;
                }
                if (candidates.size() >= maxColumns) {
                    throw new IllegalArgumentException(
                            "search area contains more than " + maxColumns + " candidate columns");
                }
                candidates.add(pack(x, z));
            }
        }
        Long[] sorted = candidates.toArray(new Long[0]);
        Arrays.sort(sorted, (left, right) -> {
            int comparison = Double.compare(
                    distanceSquared(left, playerX, playerZ),
                    distanceSquared(right, playerX, playerZ));
            return comparison != 0 ? comparison : Long.compare(left, right);
        });
        long[] result = new long[sorted.length];
        for (int index = 0; index < sorted.length; index++) {
            result[index] = sorted[index];
        }
        return result;
    }

    /** Packs integer block x/z into one stable sort key. */
    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Extracts the integer block x from a packed column key. */
    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    /** Extracts the integer block z from a packed column key. */
    public static int unpackZ(long packed) {
        return (int) packed;
    }

    private static double distanceSquared(long packed, double playerX, double playerZ) {
        double deltaX = unpackX(packed) + 0.5 - playerX;
        double deltaZ = unpackZ(packed) + 0.5 - playerZ;
        return deltaX * deltaX + deltaZ * deltaZ;
    }
}