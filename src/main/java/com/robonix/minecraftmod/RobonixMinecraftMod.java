// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** Starts the client-side camera and chassis primitive implementation. */
@Mod(RobonixMinecraftMod.MOD_ID)
public final class RobonixMinecraftMod {
    public static final String MOD_ID = "robonix_minecraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Registers render/tick callbacks and starts the loopback command server. */
    public RobonixMinecraftMod() {
        FrameCapture frameCapture = new FrameCapture();
        DepthCapture depthCapture = new DepthCapture();
        MinecraftControl control = new MinecraftControl();
        MinecraftActions actions = new MinecraftActions(control);
        LocalCommandServer server = new LocalCommandServer(frameCapture, depthCapture, control, actions);

        MinecraftForge.EVENT_BUS.addListener(frameCapture::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(frameCapture::onRenderScreen);
        MinecraftForge.EVENT_BUS.addListener(depthCapture::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(control::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(control::onMovementInput);
        MinecraftForge.EVENT_BUS.addListener(actions::onClientTick);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            frameCapture.close();
            depthCapture.close();
            actions.close();
        }, "robonix-minecraft-shutdown"));
    }
}
