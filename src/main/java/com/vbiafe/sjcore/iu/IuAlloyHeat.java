package com.vbiafe.sjcore.iu;

import com.denfop.api.recipe.MachineRecipe;
import net.minecraft.nbt.CompoundTag;

/**
 * 合金炉的温度门槛：每台炉子有它熔得动的上限，配方自带的 {@code temperature} 超过上限就不出产物。
 *
 * <p>IU 自己把温度写进配方 NBT 之后没有任何地方读它——四个档位的温度区间（1000–5000 / 3000–5000 /
 * 5000–8000 / 7000–10000）只是标签，所以基础合金炉能熔 3890 ℃ 的碳化铪。这里把温度变成真门槛：
 *
 * <table>
 *   <tr><th>配方池</th><th>炉子</th><th>上限</th><th>能熔什么</th></tr>
 *   <tr><td>alloysmelter</td><td>合金炉 L1</td><td>1500 ℃</td><td>铜/铝/锌/铅/锡/金/银 基合金，铁基边缘</td></tr>
 *   <tr><td>advalloysmelter</td><td>改良合金炉 L2</td><td>2000 ℃</td><td>钢、低温镍基、钛合金</td></tr>
 *   <tr><td>impalloysmelter</td><td>高级合金炉 L3</td><td>3000 ℃</td><td>锇铱、碳化钨、镍钨重合金（电弧炉档）</td></tr>
 *   <tr><td>peralloysmelter</td><td>完美合金炉 L4</td><td>4000 ℃</td><td>硼化铪、碳化铪、难熔金属（真空/等离子档）</td></tr>
 * </table>
 *
 * <p>温度值一律按现实熔点填（见 {@code kubejs/docs/IU_ALLOY_PLAN.md} §5 的"温"列），
 * 所以这条门槛读起来就是"这台炉子最高能到多少度"。配方放错池子会被静默拒绝，
 * 所以 {@code kubejs/tools/lint_iu_alloys.py} 会检查每条补丁的温度不超过它所在池的上限。
 *
 * <p><b>只管带 {@code sj_heat} 标记的配方</b>：标记由 sjcore 补丁的 {@code meta} 写进配方 NBT，
 * 我们重写过的合金配方才有。这样 IU 自带的化学/粉料配方（温度是它自己的随手数字，不少在 4000–5000）
 * 不会被误伤，门槛只落在合金这一层。
 */
public final class IuAlloyHeat {

    public static final int BASIC = 1500;
    public static final int IMPROVED = 2000;
    public static final int ADVANCED = 3000;
    public static final int PERFECT = 4000;

    private IuAlloyHeat() {
    }

    /** 某个配方池的温度上限；不是合金炉的池子返回 0（不拦）。 */
    public static int capOf(String pool) {
        if (pool == null) {
            return 0;
        }
        return switch (pool) {
            case "alloysmelter" -> BASIC;
            case "advalloysmelter" -> IMPROVED;
            case "impalloysmelter" -> ADVANCED;
            case "peralloysmelter" -> PERFECT;
            default -> 0;
        };
    }

    /** 配方声明的熔炼温度；没写温度的配方按 0 处理（等于不设门槛）。 */
    public static int temperatureOf(MachineRecipe recipe) {
        CompoundTag metadata = metadataOf(recipe);
        return metadata == null ? 0 : metadata.getShort("temperature");
    }

    /** 这条配方是不是我们重写过、要过温度门槛的（sjcore 补丁里的 {@code meta.sj_heat}）。 */
    public static boolean isGated(MachineRecipe recipe) {
        CompoundTag metadata = metadataOf(recipe);
        return metadata != null && metadata.contains("sj_heat");
    }

    private static CompoundTag metadataOf(MachineRecipe recipe) {
        if (recipe == null || recipe.getRecipe() == null || recipe.getRecipe().getOutput() == null) {
            return null;
        }
        return recipe.getRecipe().getOutput().metadata;
    }
}
