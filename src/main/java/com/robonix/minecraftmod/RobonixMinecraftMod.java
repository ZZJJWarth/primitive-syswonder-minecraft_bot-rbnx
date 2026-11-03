// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
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
        ControllerNetwork.register();
        var serverControl = new ServerControllerLifecycle();
        MinecraftForge.EVENT_BUS.addListener(serverControl::started);
        MinecraftForge.EVENT_BUS.addListener(serverControl::stopping);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::start);
    }
}
