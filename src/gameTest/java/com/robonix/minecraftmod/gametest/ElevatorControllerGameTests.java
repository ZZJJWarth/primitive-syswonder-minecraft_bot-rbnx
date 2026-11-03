// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod.gametest;

import com.robonix.minecraftmod.ElevatorControllerAccess;
import com.robonix.minecraftmod.ElevatorControllerBlock;
import com.robonix.minecraftmod.ElevatorControllerRegistry;
import com.robonix.minecraftmod.RobonixMinecraftMod;
import com.robonix.minecraftmod.ControllerNetwork;
import com.robonix.minecraftmod.DoorRegion;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;

@net.minecraftforge.fml.common.Mod("robonix_minecraft_tests")
@GameTestHolder(RobonixMinecraftMod.MOD_ID)
public final class ElevatorControllerGameTests {
    @GameTest(template = "robonix_minecraft:empty")
    public static void serverControllerRejectsNonOperatorAndReadsRealDoor(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos controller = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos door = controller.east();
        level.setBlock(controller, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState()
                .setValue(ElevatorControllerBlock.FACING, Direction.EAST), 2);
        level.setBlock(door.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(door, Blocks.IRON_DOOR.defaultBlockState(), 2);
        level.setBlock(door.above(), Blocks.IRON_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        var player = helper.makeMockServerPlayerInLevel();
        var request = new ControllerNetwork.Request(1, level.dimension().location().toString(),
                controller, true, java.util.List.of(door));
        try {
            ControllerNetwork.execute(request, player);
            helper.fail("non-operator controlled elevator");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(!level.getBlockState(controller).getValue(ElevatorControllerBlock.POWERED),
                    "rejected request changed block state");
        }
        level.getServer().getPlayerList().getOps().add(
                new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
        helper.addCleanup(success -> {
            level.getServer().getPlayerList().deop(player.getGameProfile());
            player.discard();
        });
        var response = ControllerNetwork.execute(request, player);
        helper.assertTrue(response.getAsJsonArray("doors").get(0).getAsJsonObject()
                .get("open").getAsBoolean(), "server did not observe the real open door");
        helper.assertTrue(response.has("player"), "missing server player observation");
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void directionalOutputAndReset(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var level = helper.getLevel();
        // Check the neighbours' real signal queries for all six possible orientations.
        for (Direction facing : Direction.values()) {
            level.setBlockAndUpdate(pos, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState()
                    .setValue(ElevatorControllerBlock.FACING, facing));
            ElevatorControllerAccess.set(level, pos, true);
            for (Direction neighbour : Direction.values()) {
                int actual = level.getSignal(pos, neighbour.getOpposite());
                helper.assertTrue(actual == (neighbour == facing ? 15 : 0), "wrong output face: " + facing);
            }
            ElevatorControllerAccess.set(level, pos, false);
            helper.assertTrue(level.getSignal(pos, facing.getOpposite()) == 0, "reset left output powered");
        }
        // Invalid targets must fail without replacing ordinary blocks or loading chunks.
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        try {
            ElevatorControllerAccess.set(level, pos, true);
            helper.fail("ordinary block was accepted as a controller");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "ordinary block was changed");
        }
        helper.succeed();
    }

    @GameTest(template = "robonix_minecraft:empty", timeoutTicks = 60)
    public static void commandBlockTeleportsPlayerAndRetriggers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos controller = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos command = controller.east();
        BlockPos source = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos target = source.above(3);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        player.teleportTo(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
        helper.addCleanup(success -> player.discard());
        level.setBlockAndUpdate(controller, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState()
                .setValue(ElevatorControllerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(command, Blocks.COMMAND_BLOCK.defaultBlockState());
        var entity = (CommandBlockEntity) level.getBlockEntity(command);
        entity.getCommandBlock().setCommand("execute as " + player.getUUID()
                + " if entity @s[x=" + source.getX() + ",y=" + source.getY() + ",z=" + source.getZ()
                + ",dx=0,dy=1,dz=0] run tp @s "
                + String.format(Locale.ROOT, "%.1f %d %.1f", target.getX() + 0.5,
                        target.getY(), target.getZ() + 0.5));
        ElevatorControllerAccess.set(level, controller, true);
        helper.runAtTickTime(4, () -> {
            helper.assertTrue(player.blockPosition().equals(target), "command block did not teleport the player; actual="
                    + player.position() + ", expected=" + target + ", powered=" + entity.isPowered()
                    + ", output=" + entity.getCommandBlock().getLastOutput().getString()
                    + ", success=" + entity.getCommandBlock().getSuccessCount());
            ElevatorControllerAccess.set(level, controller, false);
        });
        helper.runAtTickTime(6, () -> {
            player.teleportTo(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
            ElevatorControllerAccess.set(level, controller, true);
        });
        helper.runAtTickTime(10, () -> {
            helper.assertTrue(player.blockPosition().equals(target), "reset did not allow a second teleport");
            ElevatorControllerAccess.set(level, controller, false);
            helper.succeed();
        });
    }

    private static ServerPlayer operator(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setNoGravity(true);
        helper.getLevel().getServer().getPlayerList().getOps().add(
                new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
        helper.addCleanup(success -> {
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            player.discard();
        });
        return player;
    }

    private static com.google.gson.JsonObject regionState(GameTestHelper helper, ServerPlayer player,
                                                           BlockPos controller, DoorRegion region, Boolean powered) {
        return ControllerNetwork.execute(new ControllerNetwork.Request(1,
                helper.getLevel().dimension().location().toString(), controller, powered,
                java.util.List.of(), region), player);
    }

    private static void assertDoor(GameTestHelper helper, ServerPlayer player, BlockPos controller,
                                   DoorRegion region, String expected) {
        var result = regionState(helper, player, controller, region, null);
        helper.assertTrue(result.getAsJsonObject("door_region").get("state").getAsString().equals(expected),
                "expected door state " + expected + ": " + result);
    }

    @GameTest(template = "robonix_minecraft:empty")
    public static void fillRegionRejectsPartialUnexpectedAndUnloadedBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = operator(helper);
        BlockPos controller = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(controller, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState());
        BlockPos min = helper.absolutePos(new BlockPos(4, 1, 1));
        BlockPos max = min.above();
        var region = new DoorRegion(level.dimension().location().toString(), min, max, "minecraft:glass");
        assertDoor(helper, player, controller, region, "open");
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
        assertDoor(helper, player, controller, region, "closed");
        level.setBlockAndUpdate(min, Blocks.AIR.defaultBlockState());
        assertDoor(helper, player, controller, region, "unknown");
        level.setBlockAndUpdate(min, Blocks.STONE.defaultBlockState());
        var partial = regionState(helper, player, controller, region, null).getAsJsonObject("door_region");
        helper.assertTrue(partial.get("other_blocks").getAsInt() == 1, "unexpected material not counted");
        try {
            var outside = new DoorRegion(region.dimension(), new BlockPos(30000000, 64, 0),
                    new BlockPos(30000000, 64, 0), "minecraft:glass");
            regionState(helper, player, controller, outside, true);
            helper.fail("out-of-bounds region was accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(!level.getBlockState(controller).getValue(ElevatorControllerBlock.POWERED),
                    "invalid observation changed controller output");
        }
        BlockPos unloaded = new BlockPos(29000000, 64, 29000000);
        helper.assertFalse(level.hasChunkAt(unloaded), "test requires an unloaded chunk");
        try {
            regionState(helper, player, controller,
                    new DoorRegion(region.dimension(), unloaded, unloaded, "minecraft:glass"), true);
            helper.fail("unloaded region was accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertFalse(level.hasChunkAt(unloaded), "observation generated a chunk");
            helper.assertTrue(!level.getBlockState(controller).getValue(ElevatorControllerBlock.POWERED),
                    "unloaded observation changed controller output");
        }
        try {
            new DoorRegion(region.dimension(), BlockPos.ZERO, new BlockPos(4096, 0, 0), "minecraft:glass");
            helper.fail("oversized region was accepted");
        } catch (IllegalArgumentException expected) { }
        helper.succeed();
    }

    private static String fill(DoorRegion region, String material, String replaced) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s replace %s",
                region.min().getX(), region.min().getY(), region.min().getZ(),
                region.max().getX(), region.max().getY(), region.max().getZ(), material, replaced);
    }

    private static void circuit(GameTestHelper helper, BlockPos controller, String command) {
        var level = helper.getLevel();
        level.setBlockAndUpdate(controller, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState()
                .setValue(ElevatorControllerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(controller.east(), Blocks.COMMAND_BLOCK.defaultBlockState());
        ((CommandBlockEntity) level.getBlockEntity(controller.east())).getCommandBlock().setCommand(command);
    }

    @GameTest(template = "robonix_minecraft:empty", timeoutTicks = 80)
    public static void sixControllersFillDoorsAndRoundTrip(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = operator(helper);
        String dimension = level.dimension().location().toString();
        BlockPos[] controllers = new BlockPos[6];
        for (int i = 0; i < 6; i++) controllers[i] = helper.absolutePos(new BlockPos(1, i < 3 ? 1 : 4, 1 + 2 * (i % 3)));
        BlockPos lower = helper.absolutePos(new BlockPos(4, 1, 1));
        DoorRegion first = new DoorRegion(dimension, lower, lower.east().above(), "minecraft:glass");
        DoorRegion second = new DoorRegion(dimension, lower.above(3), lower.east().above(4), "minecraft:glass");
        for (DoorRegion region : java.util.List.of(first, second)) {
            for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) {
                level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
            }
        }
        BlockPos source = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos target = source.above(3);
        player.teleportTo(source.getX() + 0.5, source.getY(), source.getZ() + 0.5);
        circuit(helper, controllers[0], fill(first, "minecraft:air", "minecraft:glass"));
        circuit(helper, controllers[1], fill(first, "minecraft:glass", "minecraft:air"));
        circuit(helper, controllers[3], fill(second, "minecraft:air", "minecraft:glass"));
        circuit(helper, controllers[4], fill(second, "minecraft:glass", "minecraft:air"));
        circuit(helper, controllers[2], String.format(Locale.ROOT,
                "execute as %s if entity @s[x=%d,y=%d,z=%d,dx=1,dy=1,dz=1] run tp @s %.1f %d %.1f",
                player.getUUID(), source.getX(), source.getY(), source.getZ(),
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
        circuit(helper, controllers[5], String.format(Locale.ROOT,
                "execute as %s if entity @s[x=%d,y=%d,z=%d,dx=1,dy=1,dz=1] run tp @s %.1f %d %.1f",
                player.getUUID(), target.getX(), target.getY(), target.getZ(),
                source.getX() + 0.5, source.getY(), source.getZ() + 0.5));
        regionState(helper, player, controllers[0], first, true);
        helper.runAtTickTime(4, () -> {
            assertDoor(helper, player, controllers[0], first, "open");
            regionState(helper, player, controllers[0], first, false);
            regionState(helper, player, controllers[1], first, true);
        });
        helper.runAtTickTime(8, () -> {
            assertDoor(helper, player, controllers[1], first, "closed");
            regionState(helper, player, controllers[1], first, false);
            regionState(helper, player, controllers[2], first, true);
        });
        helper.runAtTickTime(12, () -> {
            helper.assertTrue(player.blockPosition().equals(target), "upward cabin transport failed");
            var response = regionState(helper, player, controllers[2], first, false);
            helper.assertTrue(response.get("command_success").getAsInt() > 0, "missing real command execution");
            helper.assertTrue(response.getAsJsonObject("player").has("bounds"), "missing server player footprint");
            regionState(helper, player, controllers[3], second, true);
        });
        helper.runAtTickTime(16, () -> {
            assertDoor(helper, player, controllers[3], second, "open");
            regionState(helper, player, controllers[3], second, false);
            regionState(helper, player, controllers[4], second, true);
        });
        helper.runAtTickTime(20, () -> {
            assertDoor(helper, player, controllers[4], second, "closed");
            regionState(helper, player, controllers[4], second, false);
            regionState(helper, player, controllers[5], second, true);
        });
        helper.runAtTickTime(24, () -> {
            helper.assertTrue(player.blockPosition().equals(source), "downward cabin transport failed");
            regionState(helper, player, controllers[5], second, false);
            assertDoor(helper, player, controllers[0], first, "closed");
            helper.succeed();
        });
    }

    private static com.google.gson.JsonObject request(int port, com.google.gson.JsonObject request) {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            var writer = new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
            writer.write(request.toString() + "\n"); writer.flush();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            return com.google.gson.JsonParser.parseString(reader.readLine()).getAsJsonObject();
        } catch (java.io.IOException exception) { throw new RuntimeException(exception); }
    }

    @GameTest(template = "robonix_minecraft:empty", timeoutTicks = 200)
    public static void serverSocketControlsWiredDoorWithoutPlayer(GameTestHelper helper) throws java.io.IOException {
        var level = helper.getLevel();
        BlockPos controller = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos command = controller.east(3);
        var region = new DoorRegion(level.dimension().location().toString(),
                helper.absolutePos(new BlockPos(5, 1, 4)), helper.absolutePos(new BlockPos(5, 2, 4)), "minecraft:glass");
        for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
        for (int i = 0; i < 4; i++) level.setBlockAndUpdate(controller.east(i).below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(controller, ElevatorControllerRegistry.CONTROLLER.get().defaultBlockState()
                .setValue(ElevatorControllerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(controller.east(), Blocks.REDSTONE_WIRE.defaultBlockState());
        level.setBlockAndUpdate(controller.east(2), Blocks.REDSTONE_WIRE.defaultBlockState());
        level.setBlockAndUpdate(command, Blocks.COMMAND_BLOCK.defaultBlockState());
        ((CommandBlockEntity) level.getBlockEntity(command)).getCommandBlock()
                .setCommand(fill(region, "minecraft:air", "minecraft:glass"));
        var endpoint = new com.robonix.minecraftmod.ServerControllerEndpoint(level.getServer(), 0);
        endpoint.start();
        helper.addCleanup(success -> endpoint.close());
        var job = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            var json = new com.google.gson.JsonObject();
            json.addProperty("id", "socket-test");
            json.addProperty("cmd", "elevator_controller_get");
            json.addProperty("dimension", region.dimension());
            json.addProperty("x", controller.getX()); json.addProperty("y", controller.getY()); json.addProperty("z", controller.getZ());
            json.addProperty("player", "offline_elevator_probe");
            var commandJson = new com.google.gson.JsonObject();
            commandJson.addProperty("x", command.getX()); commandJson.addProperty("y", command.getY()); commandJson.addProperty("z", command.getZ());
            json.add("command_block", commandJson);
            json.add("door_region", com.google.gson.JsonParser.parseString(String.format(Locale.ROOT,
                    "{\"dimension\":\"%s\",\"min\":[%d,%d,%d],\"max\":[%d,%d,%d],\"closed_block\":\"minecraft:glass\"}",
                    region.dimension(), region.min().getX(), region.min().getY(), region.min().getZ(),
                    region.max().getX(), region.max().getY(), region.max().getZ())));
            var before = request(endpoint.port(), json);
            if (!before.get("ok").getAsBoolean()) throw new AssertionError(before.toString());
            var beforeState = before.getAsJsonObject("result");
            if (beforeState.get("player_online").getAsBoolean() || beforeState.has("player")) throw new AssertionError("offline player required");
            if (!beforeState.has("command_tick")) throw new AssertionError("wired command feedback missing");
            json.addProperty("cmd", "elevator_controller_set"); json.addProperty("powered", true);
            if (!request(endpoint.port(), json).get("ok").getAsBoolean()) throw new AssertionError("set failed");
            json.addProperty("cmd", "elevator_controller_get");
            boolean opened = false;
            for (int i = 0; i < 30; i++) {
                var result = request(endpoint.port(), json).getAsJsonObject("result");
                if (result.getAsJsonObject("door_region").get("state").getAsString().equals("open")) {
                    if (result.get("command_success").getAsInt() <= 0) throw new AssertionError("command failed");
                    opened = true; break;
                }
                try { Thread.sleep(20); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
            }
            if (!opened) throw new AssertionError("wired fill did not open door");
            json.addProperty("cmd", "elevator_controller_set"); json.addProperty("powered", false);
            request(endpoint.port(), json);
            json.addProperty("powered", "true");
            if (request(endpoint.port(), json).get("ok").getAsBoolean()) throw new AssertionError("string boolean accepted");
            json.addProperty("cmd", "observe");
            if (request(endpoint.port(), json).get("ok").getAsBoolean()) throw new AssertionError("client command accepted by server");
            json.addProperty("cmd", "elevator_controller_get");
            if (request(endpoint.port(), json).getAsJsonObject("result").get("powered").getAsBoolean()) throw new AssertionError("reset failed");
            return true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.isDone(), "waiting for server socket requests");
            try { helper.assertTrue(job.join(), "server socket test failed"); }
            catch (java.util.concurrent.CompletionException exception) { helper.fail(exception.getCause().toString()); }
        });
    }
}
