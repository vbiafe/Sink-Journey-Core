package com.vbiafe.sjcore.mixin;

import com.denfop.api.recipe.IBaseRecipe;
import com.denfop.api.recipe.InventoryRecipes;
import com.denfop.api.recipe.MachineRecipe;
import com.vbiafe.sjcore.iu.IuAlloyHeat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 合金炉的温度门槛（{@link IuAlloyHeat}）。
 *
 * <p>挂在 {@code InventoryRecipes#getOutputFor()} 的返回值上：方法是所有机器的配方匹配唯一出口，
 * 而 {@code this.recipe} 就是配方池名（{@code alloysmelter} / {@code advalloysmelter} / …），
 * 所以一个注入点就覆盖四台炉子，不用逐个方块实体去改。
 *
 * <p>超过炉子上限时返回 {@code null}，对机器来说等价于"没有可用配方"：进度条不走、不耗电、不吐产物，
 * JEI 里那条配方仍然带着温度显示，玩家能看出是炉子不够热。
 *
 * <p>只拦带 {@code sj_heat} 标记的配方（sjcore 补丁写的），IU 自带的粉料/化学反应配方不受影响，
 * 详见 {@link IuAlloyHeat}。
 *
 * <p>{@code require} 在 {@code sinkjourneycore.mixins.json} 里默认 0：IU 换版本、方法名对不上时
 * 这里静默跳过，机器照原样能熔一切，退化成只删不改。
 */
@Mixin(value = InventoryRecipes.class, remap = false)
public abstract class IuAlloyHeatGateMixin {

    @Shadow(remap = false)
    private IBaseRecipe recipe;

    @Inject(method = "getOutputFor", at = @At("RETURN"), cancellable = true, remap = false)
    private void sjcore$alloyHeatGate(CallbackInfoReturnable<MachineRecipe> cir) {
        try {
            MachineRecipe output = cir.getReturnValue();
            if (output == null) {
                return;
            }
            int cap = IuAlloyHeat.capOf(this.recipe == null ? null : this.recipe.getName());
            if (cap <= 0) {
                return;
            }
            if (!IuAlloyHeat.isGated(output)) {
                return;
            }
            if (IuAlloyHeat.temperatureOf(output) > cap) {
                cir.setReturnValue(null);
            }
        } catch (Throwable ignored) {
            // 读不到温度就按原样放行，别让门槛把机器 tick 打断
        }
    }
}
