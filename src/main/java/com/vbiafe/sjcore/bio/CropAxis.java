package com.vbiafe.sjcore.bio;

import com.denfop.api.crop.genetics.EnumGenetic;
import com.denfop.api.crop.genetics.GeneticTraits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * IU 的 19 条遗传轴：中文名、分组、档数，以及"档位 ↔ GeneticTraits"的换算。
 *
 * 档位表不是写死的，是从 {@link GeneticTraits} 的枚举名推出来的（{@code SOIL_II} → 土壤 2 档），
 * 这样 IU 更新加档时这里不用跟着改。
 *
 * 轴分两类：阶梯轴（越高越好，能升档）与类别轴（选哪个，不升档）——
 * IU 的生物群系轴是「地狱/温暖/温和/寒冷/积雪」五种并列选项，把它当阶梯算会得出无意义的 "+1"。
 */
public final class CropAxis {

    public enum Kind {
        /** 越高越好，能升档。 */
        LADDER,
        /** 并列选项，只做继承不做升档。 */
        FLAG
    }

    public enum Group {
        YIELD("产量", 0),
        ENV("环境", 1),
        RESIST("抗性", 2);

        public final String label;
        /** 预算裁剪顺序：数值越大越先被砍。 */
        public final int trimPriority;

        Group(String label, int trimPriority) {
            this.label = label;
            this.trimPriority = trimPriority;
        }
    }

    public record Axis(EnumGenetic genetic, String nameZh, Kind kind, Group group, List<GeneticTraits> levels) {

        public int cap() {
            return levels.size();
        }

        /** 该基因在这条轴上的档位，0 表示没有这个基因。 */
        public int tier(GeneticTraits trait) {
            int index = levels.indexOf(trait);
            return index < 0 ? 0 : index + 1;
        }

        /** 档位换回基因；超出范围时夹到边界，0 档返回 null。 */
        public GeneticTraits traitAt(int tier) {
            if (tier <= 0 || levels.isEmpty()) {
                return null;
            }
            return levels.get(Math.min(levels.size(), tier) - 1);
        }
    }

    private static final Map<EnumGenetic, Axis> AXES = build();

    private CropAxis() {
    }

    public static Map<EnumGenetic, Axis> all() {
        return AXES;
    }

    public static Axis of(EnumGenetic genetic) {
        return AXES.get(genetic);
    }

    public static int cap(EnumGenetic genetic) {
        Axis axis = AXES.get(genetic);
        return axis == null ? 0 : axis.cap();
    }

    private static Map<EnumGenetic, Axis> build() {
        Map<EnumGenetic, List<GeneticTraits>> levels = new EnumMap<>(EnumGenetic.class);
        for (EnumGenetic genetic : EnumGenetic.values()) {
            levels.put(genetic, new ArrayList<>());
        }

        // 轴名按长度倒序，保证 SOIL_BLOCK 不会被 SOIL 的 "SOIL_" 前缀抢走
        List<EnumGenetic> byLength = new ArrayList<>(List.of(EnumGenetic.values()));
        byLength.sort(Comparator.comparingInt((EnumGenetic g) -> g.name().length()).reversed());

        for (GeneticTraits trait : GeneticTraits.values()) {
            EnumGenetic owner = null;
            for (EnumGenetic candidate : byLength) {
                if (trait.name().equals(candidate.name()) || trait.name().startsWith(candidate.name() + "_")) {
                    owner = candidate;
                    break;
                }
            }
            if (owner != null) {
                levels.get(owner).add(trait);
            }
        }
        // BEECOMBINE 是 BEE 轴唯一的一档，名字不带前缀
        levels.get(EnumGenetic.BEE).add(GeneticTraits.BEECOMBINE);

        for (List<GeneticTraits> list : levels.values()) {
            list.sort(Comparator.comparingInt(CropAxis::rank).thenComparing(Enum::name));
        }

        Map<EnumGenetic, Axis> axes = new EnumMap<>(EnumGenetic.class);
        for (EnumGenetic genetic : EnumGenetic.values()) {
            axes.put(genetic, new Axis(genetic, nameZh(genetic), kind(genetic), group(genetic), List.copyOf(levels.get(genetic))));
        }
        return axes;
    }

    /** 枚举名尾部的罗马数字，没有则算 1 档。 */
    private static int rank(GeneticTraits trait) {
        String name = trait.name();
        int cut = name.lastIndexOf('_');
        if (cut < 0) {
            return 1;
        }
        return switch (name.substring(cut + 1)) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            default -> 1;
        };
    }

    private static String nameZh(EnumGenetic genetic) {
        return switch (genetic) {
            case SOIL -> "土壤";
            case CHANCE -> "收获概率";
            case BEE -> "蜜蜂授粉";
            case YIELD -> "产量";
            case WEATHER -> "天气抗性";
            case WATER -> "水分";
            case LIGHT -> "光照";
            case BIOME -> "生物群系";
            case GROW_SPEED -> "生长速度";
            case PEST -> "虫害抗性";
            case SEED -> "种子";
            case WEED -> "杂草抗性";
            case SOIL_BLOCK -> "任意土壤";
            case SUN -> "阳光";
            case NIGHT_GROW -> "夜间生长";
            case AIR -> "空气";
            case RADIATION -> "辐射";
            case GENOME_RESISTANCE -> "遗传抗性";
            case GENOME_ADAPTIVE -> "适应性";
        };
    }

    private static Kind kind(EnumGenetic genetic) {
        return switch (genetic) {
            case BEE, WATER, BIOME, SOIL_BLOCK, SUN, NIGHT_GROW -> Kind.FLAG;
            default -> Kind.LADDER;
        };
    }

    private static Group group(EnumGenetic genetic) {
        return switch (genetic) {
            case YIELD, CHANCE, SEED, GROW_SPEED, SUN, NIGHT_GROW -> Group.YIELD;
            case SOIL, WATER, LIGHT, BIOME, AIR, WEATHER, SOIL_BLOCK -> Group.ENV;
            case PEST, WEED, RADIATION, GENOME_RESISTANCE, GENOME_ADAPTIVE, BEE -> Group.RESIST;
        };
    }
}
