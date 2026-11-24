package com.vbiafe.sjcore.bio;

import com.denfop.api.crop.genetics.EnumGenetic;
import com.denfop.api.crop.genetics.GeneticTraits;
import com.denfop.api.crop.genetics.Genome;
import com.denfop.datacomponent.GenomeCrop;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * 双倍体遗传层：显性用 IU 自己的 {@code GENOME_CROP} 组件（原样，IU 的生长逻辑直接读它），
 * 隐性另存一个本模组的组件 {@code sinkjourneycore:crop_alleles}，两边互不覆盖。
 *
 * 为什么能这么干：IU 的 {@code Genome.writeNBT} 只是
 * {@code stack.set(GENOME_CROP, new GenomeCrop(map))}，只替换自己那一个组件。
 */
public final class SJBiology {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, "sinkjourneycore");

    /** 隐性等位基因：每个轴一个 IU 的原生基因值。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GenomeCrop>> ALLELES =
            COMPONENTS.register("crop_alleles", () -> DataComponentType.<GenomeCrop>builder()
                    .persistent(GenomeCrop.CODEC)
                    .networkSynchronized(GenomeCrop.STREAM_CODEC)
                    .build());

    private SJBiology() {
    }

    public static void init(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    /** 显性层：IU 自己认的那份。 */
    public static Map<EnumGenetic, GeneticTraits> dominant(ItemStack stack) {
        return new Genome(stack).getGeneticTraitsMap();
    }

    /** 隐性层：没有记录时按「显 = 隐」回填，老存档自然兼容。 */
    public static Map<EnumGenetic, GeneticTraits> recessive(ItemStack stack) {
        GenomeCrop stored = stack.get(ALLELES.get());
        if (stored != null && !stored.isEmpty()) {
            return stored.geneticTraitsMap();
        }
        return Map.copyOf(dominant(stack));
    }

    public static boolean hasAlleles(ItemStack stack) {
        GenomeCrop stored = stack.get(ALLELES.get());
        return stored != null && !stored.isEmpty();
    }

    public static void write(ItemStack stack, Map<EnumGenetic, GeneticTraits> dominant, Map<EnumGenetic, GeneticTraits> recessive) {
        new Genome(dominant).writeNBT(stack);
        stack.set(ALLELES, new GenomeCrop(recessive));
    }

    /** 一次交配的结果，按轴给出新的显性与隐性。 */
    public record Offspring(Map<EnumGenetic, GeneticTraits> dominant, Map<EnumGenetic, GeneticTraits> recessive) {
    }

    /**
     * 两个亲本交配。阶梯轴按 {@link AlleleMath#crossLadder} 升档，类别轴只做继承。
     * 该轴上双方都没有基因时跳过，不会凭空造基因。
     */
    public static Offspring cross(ItemStack parentA, ItemStack parentB, RandomSource random) {
        return cross(dominant(parentA), recessive(parentA), dominant(parentB), recessive(parentB), McRng.of(random));
    }

    public static Offspring cross(Map<EnumGenetic, GeneticTraits> domA, Map<EnumGenetic, GeneticTraits> recA,
                                  Map<EnumGenetic, GeneticTraits> domB, Map<EnumGenetic, GeneticTraits> recB,
                                  Rng rng) {
        Map<EnumGenetic, GeneticTraits> outDominant = new EnumMap<>(EnumGenetic.class);
        Map<EnumGenetic, GeneticTraits> outRecessive = new EnumMap<>(EnumGenetic.class);

        for (CropAxis.Axis axis : CropAxis.all().values()) {
            int cap = axis.cap();
            if (cap <= 0) {
                continue;
            }
            int aDominant = axis.tier(domA.get(axis.genetic()));
            int aRecessive = axis.tier(recA.get(axis.genetic()));
            int bDominant = axis.tier(domB.get(axis.genetic()));
            int bRecessive = axis.tier(recB.get(axis.genetic()));
            if (aDominant == 0 && bDominant == 0) {
                continue;
            }
            // 亲本只有显性记录时，隐性按显性算
            AlleleMath.Pair a = new AlleleMath.Pair(aDominant, aRecessive == 0 ? aDominant : aRecessive);
            AlleleMath.Pair b = new AlleleMath.Pair(bDominant, bRecessive == 0 ? bDominant : bRecessive);

            AlleleMath.Pair child = axis.kind() == CropAxis.Kind.LADDER
                    ? AlleleMath.crossLadder(a, b, cap, rng)
                    : AlleleMath.crossFlag(a, b, cap, rng);

            GeneticTraits dominantTrait = axis.traitAt(child.dominant());
            GeneticTraits recessiveTrait = axis.traitAt(child.recessive());
            if (dominantTrait != null) {
                outDominant.put(axis.genetic(), dominantTrait);
            }
            if (recessiveTrait != null) {
                outRecessive.put(axis.genetic(), recessiveTrait);
            }
        }
        return new Offspring(outDominant, outRecessive);
    }

    /** 总档位（显性 + 隐性），用来对预算做体检。 */
    public static int totalTiers(Map<EnumGenetic, GeneticTraits> dominant, Map<EnumGenetic, GeneticTraits> recessive) {
        int total = 0;
        for (CropAxis.Axis axis : CropAxis.all().values()) {
            total += axis.tier(dominant.get(axis.genetic()));
            total += axis.tier(recessive.get(axis.genetic()));
        }
        return total;
    }
}
