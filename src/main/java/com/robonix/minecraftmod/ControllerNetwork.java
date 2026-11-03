// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.SimpleChannel;

public final class ControllerNetwork {
    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named("robonix_minecraft:elevator").networkProtocolVersion(2).optional().simpleChannel();

    public record Request(int id, String dimension, BlockPos position, Boolean powered,
                          List<BlockPos> doors, DoorRegion doorRegion) {
        public Request(int id, String dimension, BlockPos position, Boolean powered, List<BlockPos> doors) {
            this(id, dimension, position, powered, doors, null);
        }
    }
    public record Reply(int id, String json, String error) {}

    private ControllerNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Request.class, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ControllerNetwork::encode).decoder(ControllerNetwork::decode)
                .consumerMainThread(ControllerNetwork::handle).add();
        CHANNEL.messageBuilder(Reply.class, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((reply, buf) -> {
                    buf.writeInt(reply.id()); buf.writeUtf(reply.json()); buf.writeUtf(reply.error());
                })
                .decoder(buf -> new Reply(buf.readInt(), buf.readUtf(), buf.readUtf()))
                .consumerNetworkThread((reply, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ControllerClient.accept(reply));
                    context.setPacketHandled(true);
                }).add();
    }

    private static void encode(Request request, FriendlyByteBuf buf) {
        buf.writeInt(request.id());
        buf.writeUtf(request.dimension());
        buf.writeBlockPos(request.position());
        buf.writeByte(request.powered() == null ? -1 : request.powered() ? 1 : 0);
        buf.writeCollection(request.doors(), FriendlyByteBuf::writeBlockPos);
        buf.writeBoolean(request.doorRegion() != null);
        if (request.doorRegion() != null) {
            var region = request.doorRegion();
            buf.writeUtf(region.dimension(), 128);
            buf.writeBlockPos(region.min());
            buf.writeBlockPos(region.max());
            buf.writeUtf(region.closedBlock(), 128);
        }
    }

    private static Request decode(FriendlyByteBuf buf) {
        int id = buf.readInt();
        String dimension = buf.readUtf(128);
        BlockPos position = buf.readBlockPos();
        int mode = buf.readByte();
        if (mode < -1 || mode > 1) throw new IllegalArgumentException("invalid controller operation");
        int count = buf.readVarInt();
        if (count < 0 || count > 16) throw new IllegalArgumentException("at most 16 door blocks may be observed");
        var doors = new java.util.ArrayList<BlockPos>(count);
        for (int i = 0; i < count; i++) doors.add(buf.readBlockPos());
        DoorRegion region = buf.readBoolean() ? new DoorRegion(buf.readUtf(128), buf.readBlockPos(),
                buf.readBlockPos(), buf.readUtf(128)) : null;
        return new Request(id, dimension, position, mode == -1 ? null : mode == 1, List.copyOf(doors), region);
    }

    private static void handle(Request request, CustomPayloadEvent.Context context) {
        try {
            CHANNEL.reply(new Reply(request.id(), execute(request, context.getSender()).toString(), ""), context);
        } catch (Exception exception) {
            CHANNEL.reply(new Reply(request.id(), "", String.valueOf(exception.getMessage())), context);
        }
    }

    public static JsonObject execute(Request request, net.minecraft.server.level.ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) {
            throw new IllegalArgumentException("elevator control requires operator permission level 2");
        }
        return ServerControllerAccess.execute(player.getServer(), request, player, null);
    }
}
