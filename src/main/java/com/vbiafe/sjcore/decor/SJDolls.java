package com.vbiafe.sjcore.decor;

import com.vbiafe.sjcore.SJCore;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Decorative player dolls, kept in the pack's own creative tab so they never
 * mix with another mod's doll collection. Add one entry here for every player
 * that should receive a plushie block.
 */
public final class SJDolls {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SJCore.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SJCore.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SJCore.MODID);

    public static final DeferredBlock<Block> GUSTSING = doll("gustsing");
    public static final DeferredBlock<Block> ANGELICA22 = doll("angelica22");
    public static final DeferredBlock<Block> BJDHHH = doll("bjdhhh");

    public static final DeferredItem<BlockItem> GUSTSING_ITEM = ITEMS.registerSimpleBlockItem(GUSTSING);
    public static final DeferredItem<BlockItem> ANGELICA22_ITEM = ITEMS.registerSimpleBlockItem(ANGELICA22);
    public static final DeferredItem<BlockItem> BJDHHH_ITEM = ITEMS.registerSimpleBlockItem(BJDHHH);

    private static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("dolls",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sinkjourneycore.dolls"))
                    .icon(() -> new ItemStack(GUSTSING_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(GUSTSING_ITEM.get());
                        output.accept(ANGELICA22_ITEM.get());
                        output.accept(BJDHHH_ITEM.get());
                    })
                    .build());

    private SJDolls() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        SJCore.LOGGER.info("[SJCore] Player dolls registered: GusTsing, Angelica22, bjdhhh");
    }

    private static DeferredBlock<Block> doll(String id) {
        return BLOCKS.register(id,
                () -> new DollBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_WOOL).noOcclusion()));
    }
}
