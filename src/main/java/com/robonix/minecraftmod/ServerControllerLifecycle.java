// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

public final class ServerControllerLifecycle {
    private ServerControllerEndpoint endpoint;

    public void started(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("RBNX_MINECRAFT_ELEVATOR_PORT", "25577"));
            if (port == 0) return;
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid elevator port");
            endpoint = new ServerControllerEndpoint(event.getServer(), port);
            endpoint.start();
        } catch (Exception exception) {
            RobonixMinecraftMod.LOGGER.error("Could not start server elevator endpoint", exception);
        }
    }

    public void stopping(ServerStoppingEvent event) {
        if (endpoint != null) { endpoint.close(); endpoint = null; }
    }
}
