package com.vbiafe.sjcore.port;

import cn.howxu.mmcr.api.port.PortDefinition;
import cn.howxu.mmcr.api.port.PortDefinitionRegistry;
import cn.howxu.mmcr.internal.item.InterfaceBlockItem;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.tile.FluidInputHatchBlockEntity;
import cn.howxu.mmcr.internal.tile.FluidOutputHatchBlockEntity;
import cn.howxu.mmcr.internal.tile.ItemInputBusBlockEntity;
import cn.howxu.mmcr.internal.tile.ItemOutputBusBlockEntity;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.registry.ModItems;
import cn.howxu.mmcr.registry.PortKinds;
import cn.howxu.mmcr.util.IOType;
import com.vbiafe.sjcore.SJCore;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 分级输入/输出仓：按整合包时代各注册一套物品总线与流体仓（进、出都有）。
 *
 * <p>实现方式不是新写一套端口，而是复用 MMCR 自己的端口机制：
 * <ul>
 *   <li>每个时代每类仓一套 {@link PortKinds.ItemBusKind} / {@link PortKinds.FluidHatchKind}，
 *       容量沿用 MMCR 的档位（槽数 / 流体容量），只是 id、名字、配方按时代区分；</li>
 *   <li>方块、方块实体、物品都注册进 MMCR 的注册表（namespace 仍是 {@code mmcr}），
 *       所以动态模型、GUI、能力、JEI 预览、创造模式标签页全都和原版仓一致，
 *       不需要额外贴图或模型资源。</li>
 * </ul>
 *
 * <p><b>id 必须以 MMCR 的仓名开头</b>（{@code item_input_bus_<时代>} 这种形式）：
 * MMCR 是按 id 前缀决定打开哪个界面、查哪套能力的，前缀对不上就会出现
 * 「右键点不开、界面没标题」。
 *
 * <p>时代锁在结构脚本里做：把端口位的符号直接写成方块 id，
 * 例如 {@code .set('I', 'mmcr:item_input_bus_primitive')}，
 * 这样只有该时代的仓能组成机器，原版普通仓和其它时代的仓都不行。
 *
 * <p>注意：本类只在 MMCR 已安装时调用，且必须晚于 MMCR 的构造阶段
 * （neoforge.mods.toml 里对 mmcr 声明 {@code ordering="AFTER"}）。
 */
public final class SinkPorts {

    /** 单个时代的仓规格。 */
    public record Era(String id, String zh, String en, ItemBusSize item, FluidHatchSize fluid) {}

    /**
     * 时代表。容量是 MMCR 的原始档位，改这里等于改该时代仓的槽数与流体容量。
     * 时代顺序按整合包主线：原始 → 根源 → 余烬 → 机动 → 蒸汽 → 气动 → 末地 → 星辉。
     */
    public static final List<Era> ERAS = List.of(
            new Era("primitive", "原始", "Primitive", ItemBusSize.SMALL, FluidHatchSize.SMALL),
            new Era("root", "根源", "Root", ItemBusSize.NORMAL, FluidHatchSize.NORMAL),
            new Era("ember", "余烬", "Ember", ItemBusSize.REINFORCED, FluidHatchSize.REINFORCED),
            new Era("kinetic", "机动", "Kinetic", ItemBusSize.REINFORCED, FluidHatchSize.REINFORCED),
            new Era("steam", "蒸汽", "Steam", ItemBusSize.BIG, FluidHatchSize.BIG),
            new Era("pneumatic", "气动", "Pneumatic", ItemBusSize.HUGE, FluidHatchSize.HUGE),
            new Era("end", "末地", "End", ItemBusSize.LUDICROUS, FluidHatchSize.LUDICROUS),
            new Era("radiance", "星辉", "Radiance", ItemBusSize.LUDICROUS, FluidHatchSize.VACUUM));

    private SinkPorts() {}

    public static void registerAll() {
        int count = 0;
        for (Era era : ERAS) {
            registerPort("item_input_bus_" + era.id(), new PortKinds.ItemBusKind(
                    "item_input_bus_" + era.id(), IOType.INPUT, era.item(), ItemInputBusBlockEntity::new));
            registerPort("item_output_bus_" + era.id(), new PortKinds.ItemBusKind(
                    "item_output_bus_" + era.id(), IOType.OUTPUT, era.item(), ItemOutputBusBlockEntity::new));
            registerPort("fluid_input_hatch_" + era.id(), new PortKinds.FluidHatchKind(
                    "fluid_input_hatch_" + era.id(), IOType.INPUT, era.fluid(), FluidInputHatchBlockEntity::new));
            registerPort("fluid_output_hatch_" + era.id(), new PortKinds.FluidHatchKind(
                    "fluid_output_hatch_" + era.id(), IOType.OUTPUT, era.fluid(), FluidOutputHatchBlockEntity::new));
            count += 4;
        }
        SJCore.LOGGER.info("[SJCore] 分级仓已注册：{} 个时代 / {} 个方块", ERAS.size(), count);
    }

    /**
     * 把一个端口种类接进 MMCR。MMCR 自己的静态注册阶段已经跑完（并且已经
     * {@code PortDefinitionRegistry.freeze()}），所以这里分两步走：
     * <ol>
     *   <li>块 / 方块实体 / 物品：直接往 MMCR 的三张表里补条目（顺序不能换，
     *       方块先占名字，方块实体引用方块，物品引用方块）；</li>
     *   <li>端口种类与端口定义：反射塞进 {@code PortKinds} / {@code PortDefinitionRegistry}
     *       的内部表。必须塞，否则 {@code PortKinds.all()} 里没有它们，
     *       端口 GUI 的网络同步（按 id 查种类）和 NeoForge 能力注册都会漏掉这批仓。</li>
     * </ol>
     */
    private static void registerPort(String name, IOPortKind kind) {
        Supplier<BlockEntityType<?>> beType = () -> ModBlockEntities.BES.get(name).get();

        DeferredHolder<Block, Block> block = ModBlocks.REGISTER.registerBlock(
                name,
                props -> kind.createBlock(props, beType),
                BlockBehaviour.Properties.of().mapColor(MapColor.METAL));
        ModBlocks.BLOCKS.put(name, block);

        DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> beTypeHolder = ModBlockEntities.REGISTER.register(
                name, () -> buildBlockEntityType(kind, block.get()));
        ModBlockEntities.BES.put(name, beTypeHolder);

        ModItems.ITEMS.put(name, ModItems.REGISTER.<Item>registerItem(
                name, props -> new InterfaceBlockItem(block.get(), props)));

        addToKindRegistry(kind);
    }

    /**
     * MMCR 在静态初始化时就 freeze 了端口定义，官方 {@code PortKinds.register()} 会抛
     * 「Port definitions are frozen」。这里直接改它内部那张 CopyOnWriteArrayList 和定义表，
     * 效果与先注册完全一致（都发生在 RegisterCapabilitiesEvent 之前）。
     */
    private static void addToKindRegistry(IOPortKind kind) {
        try {
            List<IOPortKind> kinds = privateValue(PortKinds.class, "REGISTRY");
            if (!kinds.contains(kind)) {
                kinds.add(kind);
            }

            PortDefinition definition = kind.definition();
            Map<ResourceLocation, PortDefinition> definitions = privateValue(PortDefinitionRegistry.class, "DEFINITIONS");
            definitions.putIfAbsent(definition.id(), definition);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法把分级仓接入 MMCR 端口表（MMCR 版本不匹配？）", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateValue(Class<?> owner, String fieldName) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockEntityType<?> buildBlockEntityType(IOPortKind kind, Block block) {
        return BlockEntityType.Builder
                .of((BlockEntityType.BlockEntitySupplier) kind.entityFactory(), block)
                .build(null);
    }
}
