// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.world.entity.player.Player;

/** Keeps opt-in player combat policy local to the Minecraft demo Mod. */
final class PlayerCombatPolicy {
    private static final String MODE_ENV = "RBNX_MINECRAFT_PLAYER_ATTACK_MODE";
    private static final String CHASE_MODE_ENV = "RBNX_MINECRAFT_PLAYER_CHASE_MODE";
    private static final String ENABLED_ENV = "RBNX_MINECRAFT_ALLOW_PLAYER_ATTACK";
    private static final String ALLOWLIST_ENV = "RBNX_MINECRAFT_ATTACKABLE_PLAYERS";

    private PlayerCombatPolicy() {}

    /** Permits all remote players in all mode, or only named targets in allowlist mode. */
    static boolean permits(Player target) {
        String mode = mode();
        if (mode.equals("all")) {
            return true;
        }
        if (!mode.equals("allowlist")) {
            return false;
        }
        Set<String> allowed = allowedPlayers();
        return allowed.contains(target.getUUID().toString().toLowerCase(Locale.ROOT))
                || allowed.contains(target.getGameProfile().getName().toLowerCase(Locale.ROOT));
    }

    /** Until-death mode removes ordinary time, distance, and attempt bounds for players. */
    static boolean chaseUntilDeath() {
        return System.getenv().getOrDefault(CHASE_MODE_ENV, "bounded").trim()
                .equalsIgnoreCase("until_death");
    }

    /** Uses the legacy boolean as allowlist mode when the explicit mode is absent. */
    private static String mode() {
        String mode = System.getenv().getOrDefault(MODE_ENV, "").trim()
                .toLowerCase(Locale.ROOT);
        if (!mode.isEmpty()) {
            return Set.of("disabled", "allowlist", "all").contains(mode)
                    ? mode
                    : "disabled";
        }
        String value = System.getenv().getOrDefault(ENABLED_ENV, "false").trim();
        return value.equalsIgnoreCase("true") || value.equals("1")
                ? "allowlist"
                : "disabled";
    }

    private static Set<String> allowedPlayers() {
        return Arrays.stream(System.getenv().getOrDefault(ALLOWLIST_ENV, "").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
