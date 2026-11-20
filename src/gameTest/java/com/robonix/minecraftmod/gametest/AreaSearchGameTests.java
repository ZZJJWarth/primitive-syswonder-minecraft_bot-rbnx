// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod.gametest;

import com.robonix.minecraftmod.AreaSearch;
import com.robonix.minecraftmod.RobonixMinecraftMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

@GameTestHolder(RobonixMinecraftMod.MOD_ID)
public final class AreaSearchGameTests {
    private static final double[] SQUARE = {0, 0, 10, 0, 10, 10, 0, 10};

    @GameTest(template = "robonix_minecraft:empty")
    public static void squareInsideAndOutside(GameTestHelper helper) {
        AreaSearch.validate(SQUARE);
        double[] nativePoints = AreaSearch.toNative(SQUARE);
        helper.assertTrue(AreaSearch.inside(5.5, -5.5, nativePoints), "square center must be inside");
        helper.assertTrue(!AreaSearch.inside(10.5, -5.5, nativePoints), "point past east edge must be outside");
        helper.assertTrue(!AreaSearch.inside(5.5, -10.5, nativePoints), "point past north edge must be outside");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void concavePolygonRespectsNotch(GameTestHelper helper) {
        double[] polygon = {0, 0, 10, 0, 10, 4, 4, 4, 4, 10, 0, 10};
        AreaSearch.validate(polygon);
        double[] nativePoints = AreaSearch.toNative(polygon);
        helper.assertTrue(AreaSearch.inside(2.5, -2.5, nativePoints), "west arm must be inside");
        helper.assertTrue(AreaSearch.inside(8.5, -2.5, nativePoints), "south arm must be inside");
        helper.assertTrue(AreaSearch.inside(2.5, -8.5, nativePoints), "north arm must be inside");
        helper.assertTrue(!AreaSearch.inside(8.5, -8.5, nativePoints), "notch must be outside");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void northIsNegativeNativeZ(GameTestHelper helper) {
        double[] nativePoints = AreaSearch.toNative(new double[]{4, 7, 4, 9, 6, 9});
        helper.assertTrue(nativePoints[1] == -7 && nativePoints[3] == -9,
                "north coordinates must map to negative native z");
        helper.assertTrue(nativePoints[0] == 4 && nativePoints[2] == 4,
                "east coordinates must map unchanged to native x");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void boundsExpandOneBlock(GameTestHelper helper) {
        int[] bounds = AreaSearch.bounds(AreaSearch.toNative(
                new double[]{0, 0, 10, 0, 10, 10, 0, 10}));
        helper.assertTrue(bounds[0] == -1 && bounds[1] == 11, "x bounds must expand by one block");
        helper.assertTrue(bounds[2] == -11 && bounds[3] == 1, "z bounds must expand by one block");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void columnsAreNearestFirst(GameTestHelper helper) {
        double[] nativePoints = AreaSearch.toNative(SQUARE);
        int[] bounds = AreaSearch.bounds(nativePoints);
        long[] columns = AreaSearch.columns(nativePoints, bounds, 0.5, -0.5, 1000);
        helper.assertTrue(columns.length == 100, "square must contain one hundred block centers");
        helper.assertTrue(AreaSearch.unpackX(columns[0]) == 0 && AreaSearch.unpackZ(columns[0]) == -1,
                "nearest column must be the player's south-west block");
        double previous = -1.0;
        for (long column : columns) {
            double deltaX = AreaSearch.unpackX(column) + 0.5 - 0.5;
            double deltaZ = AreaSearch.unpackZ(column) + 0.5 + 0.5;
            double distance = deltaX * deltaX + deltaZ * deltaZ;
            helper.assertTrue(distance >= previous, "columns must be ordered by distance");
            previous = distance;
        }
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void degeneratePolygonIsRejected(GameTestHelper helper) {
        assertRejected(helper, new double[]{0, 0, 1, 0}, "two vertices");
        assertRejected(helper, new double[]{0, 0, 1, 0, 2, 0}, "collinear vertices");
        assertRejected(helper, new double[]{0, 0, 1, Double.NaN, 1, 1}, "non-finite vertex");
        assertRejected(helper, new double[]{0, 0, 1}, "unpaired coordinates");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void columnCapIsEnforced(GameTestHelper helper) {
        double[] nativePoints = AreaSearch.toNative(SQUARE);
        int[] bounds = AreaSearch.bounds(nativePoints);
        try {
            AreaSearch.columns(nativePoints, bounds, 0.5, -0.5, 99);
            helper.fail("column cap was not enforced");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains("candidate columns"), "wrong cap error");
        }
        helper.succeed();
    }

    private static void assertRejected(GameTestHelper helper, double[] polygon, String label) {
        try {
            AreaSearch.validate(polygon);
            helper.fail("degenerate polygon was accepted: " + label);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}