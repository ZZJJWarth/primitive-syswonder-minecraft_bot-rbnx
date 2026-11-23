// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Groups every Robonix-owned item in a dedicated creative-inventory tab. */
public final class RobonixCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, RobonixMinecraftMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register(
            "robonix",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable(
                            "itemGroup." + RobonixMinecraftMod.MOD_ID + ".robonix"))
                    .icon(() -> RtkRegistry.RTK_MAPPER.get().getDefaultInstance())
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .displayItems((parameters, output) -> {
                        output.accept(ElevatorControllerRegistry.ELEVATOR_STATION_ITEM.get());
                        output.accept(ElevatorControllerRegistry.CONTROLLER_ITEM.get());
                        output.accept(RtkRegistry.RTK_BASE_STATION_ITEM.get());
                        output.accept(RtkRegistry.RTK_MAPPER.get());
                        output.accept(RtkRegistry.INDOOR_MAPPER.get());
                        output.accept(RtkRegistry.FIDUCIAL_MARKER_ITEM.get());
                    })
                    .build());

    private RobonixCreativeTab() {}
}