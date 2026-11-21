// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registers the RTK base station block and handheld RTK mapper item. */
public final class RtkRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(
            ForgeRegistries.BLOCKS, RobonixMinecraftMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            ForgeRegistries.ITEMS, RobonixMinecraftMod.MOD_ID);

    public static final RegistryObject<RtkBaseBlock> RTK_BASE_STATION = BLOCKS.register(
            "rtk_base_station", () -> new RtkBaseBlock(
                    BlockBehaviour.Properties.of().strength(3.5F).lightLevel(s -> 7)));

    public static final RegistryObject<Item> RTK_BASE_STATION_ITEM = ITEMS.register(
            "rtk_base_station", () -> new BlockItem(RTK_BASE_STATION.get(), new Item.Properties()));

    public static final RegistryObject<RtkMapperItem> RTK_MAPPER = ITEMS.register(
            "rtk_mapper", () -> new RtkMapperItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<IndoorMapperItem> INDOOR_MAPPER = ITEMS.register(
            "indoor_mapper", () -> new IndoorMapperItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<FiducialMarkerBlock> FIDUCIAL_MARKER = BLOCKS.register(
            "fiducial_marker", () -> new FiducialMarkerBlock(
                    BlockBehaviour.Properties.of().strength(1.5F).noOcclusion().lightLevel(s -> 9)));

    public static final RegistryObject<Item> FIDUCIAL_MARKER_ITEM = ITEMS.register(
            "fiducial_marker", () -> new BlockItem(FIDUCIAL_MARKER.get(), new Item.Properties()));

    private RtkRegistry() {}

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES
                || event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(RTK_BASE_STATION_ITEM);
            event.accept(RTK_MAPPER);
            event.accept(INDOOR_MAPPER);
            event.accept(FIDUCIAL_MARKER_ITEM);
        }
    }
}
