// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/** Synchronizes RTK base station lists from server to clients for local RTK fix simulation. */
public final class RtkNetwork {
    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named("robonix_minecraft:rtk").networkProtocolVersion(1).optional().simpleChannel();

    public record SyncStations(String dimension, List<RtkStationSavedData.Station> stations) {}

    private RtkNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(SyncStations.class, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RtkNetwork::encode)
                .decoder(RtkNetwork::decode)
                .consumerNetworkThread((msg, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                            RtkClientCache.update(msg.dimension(), msg.stations()));
                    context.setPacketHandled(true);
                }).add();
    }

    private static void encode(SyncStations msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension(), 128);
        buf.writeVarInt(msg.stations().size());
        for (var st : msg.stations()) {
            buf.writeUtf(st.id(), 64);
            buf.writeInt(st.x());
            buf.writeInt(st.y());
            buf.writeInt(st.z());
        }
    }

    private static SyncStations decode(FriendlyByteBuf buf) {
        String dim = buf.readUtf(128);
        int count = Math.min(Math.max(0, buf.readVarInt()), 256);
        List<RtkStationSavedData.Station> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new RtkStationSavedData.Station(
                    buf.readUtf(64), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return new SyncStations(dim, List.copyOf(list));
    }

    public static void syncToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        String dim = level.dimension().location().toString();
        var stations = RtkStationSavedData.get(level).listStations();
        CHANNEL.send(new SyncStations(dim, stations), PacketDistributor.PLAYER.with(player));
    }

    public static void broadcastStations(ServerLevel level) {
        String dim = level.dimension().location().toString();
        var stations = RtkStationSavedData.get(level).listStations();
        CHANNEL.send(new SyncStations(dim, stations), PacketDistributor.DIMENSION.with(level.dimension()));
    }
}
