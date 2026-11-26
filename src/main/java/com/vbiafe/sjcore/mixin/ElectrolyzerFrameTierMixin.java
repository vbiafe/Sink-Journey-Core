package com.vbiafe.sjcore.mixin;

import com.nred.nuclearcraft.block_entity.machine.MachineFrameEntity;
import com.nred.nuclearcraft.multiblock.MultiblockLogic;
import com.nred.nuclearcraft.multiblock.machine.ElectrolyzerLogic;
import com.nred.nuclearcraft.multiblock.machine.Machine;
import com.nred.nuclearcraft.multiblock.machine.MachineLogic;
import com.vbiafe.sjcore.machine.SJMachineFrames;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 电解池外壳档位的实际作用：整圈外壳换到哪一档，速度乘数就乘到哪一档。
 *
 * <p>核电工艺的电解池本身不认外壳材质——{@code machine_casing} 只是"能铺满池壁"的部件，
 * 速度只由电极、隔膜、电解液和尺寸决定。这里挂在
 * {@code ElectrolyzerLogic#getSpeedMultiplier()} 的返回值上，按已连接部件里的机架方块取档位：
 * 全池壁都是不锈钢壳 ×1.12，全池壁都是钛壳 ×1.28；混着铺、或者还有铜壳，按最低一档算
 * （即不升级）。速度乘数同时是"每锭能耗"的倒数，所以这一档就等于原来的「整流提速」。
 *
 * <p>injector 的 {@code require} 在 {@code sinkjourneycore.mixins.json} 里默认是 0：
 * 核电工艺换版本、方法名对不上时这里静默跳过，机器照常能组装，只是外壳不带加成。
 */
@Mixin(value = ElectrolyzerLogic.class, remap = false)
public abstract class ElectrolyzerFrameTierMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "getSpeedMultiplier", at = @At("RETURN"), cancellable = true, remap = false)
    private void sjcore$shellTierSpeed(CallbackInfoReturnable<Double> cir) {
        try {
            MultiblockLogic<Machine, MachineLogic> logic = (MultiblockLogic<Machine, MachineLogic>) (Object) this;
            var frames = logic.getParts(MachineFrameEntity.class);
            if (frames.isEmpty()) {
                return;
            }

            int lowest = Integer.MAX_VALUE;
            for (MachineFrameEntity frame : frames) {
                SJMachineFrames.Tier tier = SJMachineFrames.tierOf(frame.getBlockState().getBlock());
                lowest = Math.min(lowest, tier == null ? 0 : tier.ordinal() + 1);
            }

            SJMachineFrames.Tier[] tiers = SJMachineFrames.Tier.values();
            if (lowest <= 0 || lowest > tiers.length) {
                return;
            }

            float multiplier = tiers[lowest - 1].speedMultiplier;
            if (multiplier > 1.0f) {
                cir.setReturnValue(cir.getReturnValue() * multiplier);
            }
        } catch (Throwable ignored) {
            // 拿不到部件就按原速跑，不要让外壳逻辑把机器 tick 打断
        }
    }
}
