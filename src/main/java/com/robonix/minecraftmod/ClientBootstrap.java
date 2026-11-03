// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraftforge.common.MinecraftForge;

/** Keeps client-only classes out of dedicated-server class loading. */
public final class ClientBootstrap {
    private ClientBootstrap() {}

    public static void start() {
        SimulationClock simulationClock = new SimulationClock();
        RgbdFrameCoordinator rgbdCoordinator = new RgbdFrameCoordinator(simulationClock);
        FrameCapture frameCapture = new FrameCapture(simulationClock, rgbdCoordinator);
        DepthCapture depthCapture = new DepthCapture(rgbdCoordinator);
        MinecraftControl control = new MinecraftControl();
        MinecraftActions actions = new MinecraftActions(control);
        MinecraftChatBridge chatBridge = new MinecraftChatBridge();
        LocalCommandServer server = new LocalCommandServer(
                simulationClock, frameCapture, depthCapture, control, actions, chatBridge);

        MinecraftForge.EVENT_BUS.addListener(frameCapture::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(frameCapture::onRenderScreen);
        MinecraftForge.EVENT_BUS.addListener(depthCapture::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(control::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(control::onMovementInput);
        MinecraftForge.EVENT_BUS.addListener(actions::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(chatBridge::onPlayerChat);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            frameCapture.close();
            depthCapture.close();
            actions.close();
        }, "robonix-minecraft-shutdown"));
    }
}
