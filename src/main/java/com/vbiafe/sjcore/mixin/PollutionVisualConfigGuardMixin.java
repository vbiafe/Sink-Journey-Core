package com.vbiafe.sjcore.mixin;

import com.denfop.config.ModConfig;
import com.vbiafe.sjcore.SJCore;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Industrial Upgrade 3.4.0.11 的污染视觉有四个处理器，进门第一行都读
 * {@code ModConfig.COMMON.radiationRenderEnabled.get()}。IU 在自己的构造函数里注册配置，
 * 而 NeoForge 要稍后才把 spec 置成 loaded；客户端 tick 有机会先跑起来，
 * 那一帧就会抛 {@code IllegalStateException: Cannot get config value before config is loaded}
 * （2026-11-25 17:33 与 18:08 两次客户端崩溃都是它）。
 *
 * <p>配置本身没有问题，晚一两 tick 自然就绪，所以这里只拦读取：配置没加载就跳过这一帧的视觉更新，
 * 加载后行为完全不变。
 */
@Mixin(targets = "com.denfop.api.pollution.client.PollutionVisualForgeEvents")
public abstract class PollutionVisualConfigGuardMixin {

    @Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sjcore$skipTickBeforeConfig(ClientTickEvent.Post event, CallbackInfo ci) {
        if (sjcore$configNotLoaded()) {
            ci.cancel();
        }
    }

    @Inject(method = "onComputeFogColor", at = @At("HEAD"), cancellable = true)
    private static void sjcore$skipFogColorBeforeConfig(ViewportEvent.ComputeFogColor event, CallbackInfo ci) {
        if (sjcore$configNotLoaded()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRenderFog", at = @At("HEAD"), cancellable = true)
    private static void sjcore$skipFogBeforeConfig(ViewportEvent.RenderFog event, CallbackInfo ci) {
        if (sjcore$configNotLoaded()) {
            ci.cancel();
        }
    }

    @Inject(method = "onRenderLevelStage", at = @At("HEAD"), cancellable = true)
    private static void sjcore$skipStageBeforeConfig(RenderLevelStageEvent event, CallbackInfo ci) {
        if (sjcore$configNotLoaded()) {
            ci.cancel();
        }
    }

    private static boolean sjcore$configNotLoaded() {
        if (ModConfig.COMMON_SPEC.isLoaded()) {
            return false;
        }
        SJCore.onPollutionVisualSkipped();
        return true;
    }
}
