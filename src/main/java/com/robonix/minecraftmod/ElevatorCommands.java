// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * In-game management & player control commands for reusable native elevators:
 * - /robonix_elevator list
 * - /robonix_elevator build <id>
 * - /robonix_elevator call <id> <floor>
 * - /robonix_elevator door <id> <open|close>
 * - /robonix_elevator rename_floor <id> <anchorY> <label>
 * - /robonix_elevator resize <id> <width> <depth> <height>
 */
public final class ElevatorCommands {
    private ElevatorCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("robonix_elevator")
                .then(Commands.literal("list").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    ElevatorSavedData data = ElevatorSavedData.get(level);
                    data.pruneMissing(level);
                    var list = data.listElevators();
                    if (list.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal("当前维度暂无可复用电梯（请放置 电梯层站基座）"), false);
                        return 0;
                    }
                    for (var rec : list) {
                        StringBuilder floors = new StringBuilder();
                        for (var f : rec.floors) {
                            if (floors.length() > 0) floors.append(", ");
                            floors.append(f.label).append("(y=").append(f.floorY()).append(")");
                        }
                        String line = String.format("%s | 朝向=%s | %dx%dx%d | 当前=%s(%s) | 楼层=[%s]",
                                rec.id, rec.facing.getName(), rec.width, rec.depth, rec.height,
                                rec.currentFloorLabel(), rec.moving ? "运行中" : (rec.doorOpen ? "开门" : "关门"),
                                floors);
                        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
                    }
                    return list.size();
                }))
                .then(Commands.literal("build")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            ElevatorSavedData data = ElevatorSavedData.get(level);
                            var rec = requireElevator(data, StringArgumentType.getString(ctx, "id"));
                            ElevatorEngine.buildShaftAndCabin(level, data, rec);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "已构建电梯井道与轿厢: " + rec.id).withStyle(ChatFormatting.GREEN), false);
                            return 1;
                        })))
                .then(Commands.literal("call")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("floor", StringArgumentType.word()).executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    ElevatorSavedData data = ElevatorSavedData.get(level);
                                    var rec = requireElevator(data, StringArgumentType.getString(ctx, "id"));
                                    String fl = StringArgumentType.getString(ctx, "floor");
                                    var stop = rec.resolveFloor(fl);
                                    if (stop == null) {
                                        ctx.getSource().sendFailure(Component.literal("未找到楼层: " + fl));
                                        return 0;
                                    }
                                    boolean ok = ElevatorEngine.dispatchToFloor(level, data, rec, stop, true);
                                    if (!ok) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                String.format("电梯 %s 门口防夹传感器检测到障碍物，无法关门发车", rec.id)));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            String.format("电梯 %s 正前往 %s", rec.id, stop.label)), false);
                                    return 1;
                                }))))
                .then(Commands.literal("door")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("action", StringArgumentType.word()).executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    ElevatorSavedData data = ElevatorSavedData.get(level);
                                    var rec = requireElevator(data, StringArgumentType.getString(ctx, "id"));
                                    String act = StringArgumentType.getString(ctx, "action").toLowerCase();
                                    boolean open = act.equals("open");
                                    if (!open && !act.equals("close")) {
                                        ctx.getSource().sendFailure(Component.literal("action 须为 open 或 close"));
                                        return 0;
                                    }
                                    boolean ok = ElevatorEngine.setDoorAtCurrentFloor(level, data, rec, open);
                                    if (!ok) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                String.format("电梯 %s 门口检测到障碍物，防夹保护已阻止关门", rec.id)));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            String.format("电梯 %s 已%s", rec.id, open ? "开门" : "关门")), false);
                                    return 1;
                                }))))
                .then(Commands.literal("rename_floor")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("anchorY", IntegerArgumentType.integer())
                                        .then(Commands.argument("label", StringArgumentType.word()).executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            ElevatorSavedData data = ElevatorSavedData.get(level);
                                            var rec = requireElevator(data, StringArgumentType.getString(ctx, "id"));
                                            int ay = IntegerArgumentType.getInteger(ctx, "anchorY");
                                            String label = StringArgumentType.getString(ctx, "label");
                                            var stop = rec.findFloorByAnchorY(ay);
                                            if (stop == null) {
                                                stop = rec.findFloorByFloorY(ay);
                                            }
                                            if (stop == null) {
                                                ctx.getSource().sendFailure(Component.literal("未找到该高度楼层: " + ay));
                                                return 0;
                                            }
                                            stop.label = label;
                                            stop.customLabel = true;
                                            data.markDirty();
                                            final int fy = stop.floorY();
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    String.format("已重命名 %s (y=%d) -> %s", rec.id, fy, label)), false);
                                            return 1;
                                        })))))
                .then(Commands.literal("resize")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("width", IntegerArgumentType.integer(2, 7))
                                        .then(Commands.argument("depth", IntegerArgumentType.integer(2, 7))
                                                .then(Commands.argument("height", IntegerArgumentType.integer(3, 6))
                                                        .executes(ctx -> {
                                                            ServerLevel level = ctx.getSource().getLevel();
                                                            ElevatorSavedData data = ElevatorSavedData.get(level);
                                                            var rec = requireElevator(data, StringArgumentType.getString(ctx, "id"));
                                                            rec.width = IntegerArgumentType.getInteger(ctx, "width");
                                                            rec.depth = IntegerArgumentType.getInteger(ctx, "depth");
                                                            rec.height = IntegerArgumentType.getInteger(ctx, "height");
                                                            ElevatorEngine.buildShaftAndCabin(level, data, rec);
                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                    String.format("已调整 %s 净空尺寸为 %dx%dx%d 并重建井道",
                                                                            rec.id, rec.width, rec.depth, rec.height)), false);
                                                            return 1;
                                                        })))))));
    }

    private static ElevatorSavedData.ElevatorRecord requireElevator(ElevatorSavedData data, String id) {
        var rec = data.getById(id);
        if (rec == null) {
            throw new IllegalArgumentException("未找到电梯: " + id);
        }
        return rec;
    }
}
