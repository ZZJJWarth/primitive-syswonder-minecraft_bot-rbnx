// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Registers common game objects and starts controls only on the client. */
@Mod(RobonixMinecraftMod.MOD_ID)
public final class RobonixMinecraftMod {
    public static final String MOD_ID = "robonix_minecraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RobonixMinecraftMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ElevatorControllerRegistry.BLOCKS.register(modBus);
        ElevatorControllerRegistry.ITEMS.register(modBus);
        RtkRegistry.BLOCKS.register(modBus);
        RtkRegistry.ITEMS.register(modBus);
        RobonixCreativeTab.TABS.register(modBus);
        modBus.addListener(RtkRegistry::addCreative);
        ControllerNetwork.register();
        RtkNetwork.register();
        var serverControl = new ServerControllerLifecycle();
        MinecraftForge.EVENT_BUS.addListener(serverControl::started);
        MinecraftForge.EVENT_BUS.addListener(serverControl::stopping);
        MinecraftForge.EVENT_BUS.addListener(RobonixMinecraftMod::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(RobonixMinecraftMod::onPlayerChangedDimension);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::start);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RtkNetwork.syncToPlayer(player);
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RtkNetwork.syncToPlayer(player);
        }
    }
}
