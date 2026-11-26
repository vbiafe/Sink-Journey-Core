package com.vbiafe.sjcore.mixin;

import com.vbiafe.sjcore.SJCore;

import net.minecraft.world.level.Level;

import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 IU 的太空环境判定锁回它自己的维度。
 *
 * <p>IU 的 {@code SpaceRuntimeEvents} 用 {@code level.dimension().location().getPath()}
 * 去天体表里找同名天体——只看 path，命名空间早被丢掉了。于是 {@code ad_astra:moon} 被当成
 * IU 的月球：缺氧每 20 tick 掉 2 点、Ad Astra 的白天被 IU 按月长改写、金星还会命中
 * {@code pressure=true} 的 1000 点通用伤害。结果是穿整套 Ad Astra 宇航服上 Ad Astra 的月球
 * 也会被 IU 憋死。
 *
 * <p>这里改成只认 {@code industrialupgrade} 命名空间：Ad Astra 的行星环境交回 Ad Astra
 * 自己的氧气/温度/重力判定，IU 自己的 34 个维度（已被 sj-oregen 改成虚空）行为不变。
 *
 * <p>注意不能拦在 {@code SpaceBodyProfiles.byDimensionPath}：IU 传进去的只有 path，
 * 在那一层已经拿不到命名空间，拦下去会把 IU 自己的维度一起废掉。
 */
@Mixin(targets = "com.denfop.api.space.dimension.SpaceRuntimeEvents")
public abstract class IuSpaceEnvironmentScopeMixin {

    @Inject(method = "onLivingTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sjcore$skipForeignLiving(LivingBreatheEvent event, CallbackInfo ci) {
        if (!sjcore$isIuSpace(event.getEntity().level())) {
            ci.cancel();
        }
    }

    @Inject(method = "onLevelTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sjcore$skipForeignLevel(LevelTickEvent.Post event, CallbackInfo ci) {
        if (!sjcore$isIuSpace(event.getLevel())) {
            ci.cancel();
        }
    }

    private static boolean sjcore$isIuSpace(Level level) {
        return level != null && SJCore.IU_MODID.equals(level.dimension().location().getNamespace());
    }
}
