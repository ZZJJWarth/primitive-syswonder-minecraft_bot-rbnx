// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Wall-mounted ArUco 5x5 visual fiducial marker plaque (IDs 0..31).
 *
 * - Normal right-click: cycle marker_id (+1; Shift+right-click: -1).
 * - Right-click while holding robonix_minecraft:indoor_mapper: emits a
 *   `bind_fiducial` survey event so 8093 records the marker's ID, exact wall
 *   center ENU coordinates, and outward wall normal yaw into the active indoor
 *   floor map. Runtime robot relocalization NEVER queries this block; it only
 *   decodes the RGB camera image via OpenCV ArUco.
 */
public final class FiducialMarkerBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<FiducialMarkerBlock> CODEC = simpleCodec(FiducialMarkerBlock::new);
    public static final int MARKER_COUNT = 32;
    public static final double PHYSICAL_MARKER_SIZE_M = 98.0 / 128.0; // 0.765625 m crisp 14px/cell ArUco
    public static final IntegerProperty MARKER_ID = IntegerProperty.create("marker_id", 0, MARKER_COUNT - 1);

    private static final VoxelShape SHAPE_NORTH = Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0);
    private static final VoxelShape SHAPE_EAST = Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0);
    private static final VoxelShape SHAPE_WEST = Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public FiducialMarkerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(FACING, Direction.NORTH)
                        .setValue(MARKER_ID, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MARKER_ID);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction clicked = ctx.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal()
                ? clicked
                : ctx.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, facing).setValue(MARKER_ID, 0);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public InteractionResult use(
            BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        int curId = state.getValue(MARKER_ID);
        Direction facing = state.getValue(FACING);

        // Holding indoor_mapper: bind this marker into the active 8093 indoor floor map!
        boolean holdingIndoor = player.getMainHandItem().is(RtkRegistry.INDOOR_MAPPER.get())
                || player.getOffhandItem().is(RtkRegistry.INDOOR_MAPPER.get());
        if (holdingIndoor) {
            if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
                double ts = serverLevel.getGameTime() / 20.0;
                var odom = IndoorMapperTracker.computePose(sp, ts);
                double[] enu = plaqueCenterEnu(pos, facing);
                double wallYaw = facingToEnuYaw(facing);
                JsonObject extra = new JsonObject();
                extra.addProperty("marker_id", curId);
                extra.addProperty("x", enu[0]);
                extra.addProperty("y", enu[1]);
                extra.addProperty("z", enu[2]);
                extra.addProperty("wall_yaw", wallYaw);
                extra.addProperty("size_m", PHYSICAL_MARKER_SIZE_M);
                extra.addProperty("facing", facing.getName());
                IndoorMapperTracker.recordMark(sp, odom, "bind_fiducial", extra);
                sp.displayClientMessage(
                        Component.literal(String.format(
                                "室内建图仪: 已绑定视觉标牌 #%d (E %.2f, N %.2f, 朝向 %s)",
                                curId, enu[0], enu[1], facingLabel(facing))),
                        true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Otherwise cycle marker_id (+1 normal click, -1 shift-click).
        if (!level.isClientSide) {
            int nextId = player.isShiftKeyDown()
                    ? (curId - 1 + MARKER_COUNT) % MARKER_COUNT
                    : (curId + 1) % MARKER_COUNT;
            level.setBlock(pos, state.setValue(MARKER_ID, nextId), Block.UPDATE_ALL);
            player.displayClientMessage(
                    Component.literal(String.format(
                            "视觉定位标牌: ID #%d (朝向 %s · 潜行右键-1 · 拿室内建图仪右键绑定)",
                            nextId, facingLabel(facing))),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Exact ENU coordinates (east=mcX, north=-mcZ, up=mcY) of the plaque's visible front surface center. */
    public static double[] plaqueCenterEnu(BlockPos pos, Direction facing) {
        double mcX = pos.getX() + 0.5;
        double mcY = pos.getY() + 0.5;
        double mcZ = pos.getZ() + 0.5;
        switch (facing) {
            case NORTH -> mcZ = pos.getZ() + 15.0 / 16.0;
            case SOUTH -> mcZ = pos.getZ() + 1.0 / 16.0;
            case EAST -> mcX = pos.getX() + 1.0 / 16.0;
            case WEST -> mcX = pos.getX() + 15.0 / 16.0;
            default -> {}
        }
        return new double[] {mcX, -mcZ, mcY};
    }

    /** Outward wall normal yaw in ENU (0=+East, +pi/2=+North, -pi/2=-North, pi=-East). */
    public static double facingToEnuYaw(Direction facing) {
        return switch (facing) {
            case EAST -> 0.0;
            case NORTH -> Math.PI / 2.0;
            case SOUTH -> -Math.PI / 2.0;
            case WEST -> Math.PI;
            default -> 0.0;
        };
    }

    private static String facingLabel(Direction facing) {
        return switch (facing) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
            default -> facing.getName();
        };
    }
}
