// SPDX-License-Identifier: MulanPSL-2.0
package com.robonix.minecraftmod;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ElevatorControllerRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(
            ForgeRegistries.BLOCKS, RobonixMinecraftMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            ForgeRegistries.ITEMS, RobonixMinecraftMod.MOD_ID);
    public static final RegistryObject<ElevatorControllerBlock> CONTROLLER = BLOCKS.register(
            "elevator_controller", () -> new ElevatorControllerBlock(
                    BlockBehaviour.Properties.of().strength(3.5F)
                            .isRedstoneConductor((state, level, pos) -> false)));
    public static final RegistryObject<Item> CONTROLLER_ITEM = ITEMS.register(
            "elevator_controller", () -> new BlockItem(CONTROLLER.get(), new Item.Properties()));

    private ElevatorControllerRegistry() {}
}
