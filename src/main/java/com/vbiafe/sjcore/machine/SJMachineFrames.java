package com.vbiafe.sjcore.machine;

import com.nred.nuclearcraft.block.machine.MachineFrameBlock;
import com.nred.nuclearcraft.multiblock.machine.IMachinePartType;
import com.nred.nuclearcraft.multiblock.machine.MachinePartType;
import com.vbiafe.sjcore.SJCore;

import it.zerono.mods.zerocore.lib.block.multiblock.MultiblockPartBlock.MultiblockPartProperties;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 电解池的外壳档位：铜壳（核电工艺原版 {@code machine_casing}）→ 不锈钢 → 钛。
 *
 * <p>核电工艺的多方块外壳是「部件方块」而不是普通机壳：池壁必须由
 * {@code MultiblockPartBlock} 且部件种类是 {@code MachinePartType.Frame} 的方块铺成
 * （{@code Multiblock#isBlockGoodForFrame} 对所有非部件方块一律返回 false）。
 * 所以这里不新造机器，只把两种新外壳方块注册成同样的 frame 部件：
 * 方块实体沿用核电工艺的 {@code MachineFrameEntity}（{@link MachineFrameBlock} 的
 * 默认实现），组装校验、外观切换、破坏掉壳全部走它自己的逻辑。
 *
 * <p>外壳档位的实际作用在 {@code ElectrolyzerFrameTierMixin}：整圈外壳换成同一档才算升级，
 * 混着铺按最低一档算。贴图是把核电工艺机架贴图重上色得到的两档金属色。
 */
public final class SJMachineFrames {

    /** 外壳档位：id 后缀、速度倍率。铜壳（原版机壳）在混铺时按 0 算。 */
    public enum Tier {
        STAINLESS("stainless", 1.12f),
        TITANIUM("titanium", 1.28f);

        public final String id;
        public final float speedMultiplier;

        Tier(String id, float speedMultiplier) {
            this.id = id;
            this.speedMultiplier = speedMultiplier;
        }
    }

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SJCore.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SJCore.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SJCore.MODID);

    private static final DeferredBlock<Block> STAINLESS_FRAME = frame(Tier.STAINLESS);
    private static final DeferredBlock<Block> TITANIUM_FRAME = frame(Tier.TITANIUM);

    private static final DeferredItem<BlockItem> STAINLESS_FRAME_ITEM = item(STAINLESS_FRAME);
    private static final DeferredItem<BlockItem> TITANIUM_FRAME_ITEM = item(TITANIUM_FRAME);

    private static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("machines",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sinkjourneycore.machines"))
                    .icon(() -> new ItemStack(STAINLESS_FRAME_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(STAINLESS_FRAME_ITEM.get());
                        output.accept(TITANIUM_FRAME_ITEM.get());
                    })
                    .build());

    private SJMachineFrames() {
    }

    public static void registerAll(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        SJCore.LOGGER.info("[SJCore] 电解池外壳档位已注册：不锈钢 ×{} / 钛 ×{}",
                Tier.STAINLESS.speedMultiplier, Tier.TITANIUM.speedMultiplier);
    }

    /** 方块属于哪一档外壳；核电工艺自己的机壳返回 {@link Tier#STAINLESS} 之外的 null（按 0 档算）。 */
    public static Tier tierOf(Block block) {
        if (block == STAINLESS_FRAME.get()) {
            return Tier.STAINLESS;
        }
        if (block == TITANIUM_FRAME.get()) {
            return Tier.TITANIUM;
        }
        return null;
    }

    private static DeferredBlock<Block> frame(Tier tier) {
        return BLOCKS.register("electrolyzer_frame_" + tier.id, () -> new MachineFrameBlock(partProperties()));
    }

    private static DeferredItem<BlockItem> item(DeferredBlock<Block> block) {
        return ITEMS.registerSimpleBlockItem(block);
    }

    /**
     * 部件属性：外壳是实心金属方块，和核电工艺的 {@code machine_casing} 同档硬度，
     * 部件种类必须是 {@link MachinePartType#Frame}，否则组池时会被判成非法方块。
     */
    private static MultiblockPartProperties<IMachinePartType> partProperties() {
        return MultiblockPartProperties.<IMachinePartType>create(
                MachinePartType.Frame,
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.METAL)
                        .strength(3.5f, 6.0f)
                        .sound(SoundType.METAL)
                        .requiresCorrectToolForDrops());
    }
}
