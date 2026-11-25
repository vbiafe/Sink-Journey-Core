package com.vbiafe.sjcore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MMCR 0.1.7 的植物魔法桥接（{@code cn.howxu.mmcr.compat.botania.loaded.LoadedBotaniaBridge}）
 * 是按 1.20.1 Forge 版 Botania 编译的，硬引用 {@code vazkii.botania.api.*}（Wandable、
 * ManaReceiver、BlockApiWithContext、BotaniaNeoForgeCapabilities 等）。1.21.1 上唯一的植物魔法
 * 是 Botania Revived，包名整体在 {@code velrondevs.botania.*}，而且能力查询已经改成
 * {@code BotaniaCapabilities} 里的 neoForge capability，旧类不存在也没有对应物。
 *
 * <p>于是只要 mods 里有 botania，MMCR 构造时 {@code BotaniaBridgeBootstrap.get()} →
 * {@code Class.forName("...LoadedBotaniaBridge")} 就会抛 {@code NoClassDefFoundError}
 * （方法里只 catch 了反射异常，接不住 Error），MMCR 构造失败 → NeoForge 进入 broken mod state →
 * 之后所有 mod 的配置加载被整段跳过 → 工业升级在第一个 client tick 读配置时背锅崩溃。
 *
 * <p>这里在 {@code get()} 入口探测旧 API：不存在就返回 MMCR 自己的
 * {@code selectForTesting(false)}（即 {@code UnavailableBotaniaBridge}），让 MMCR 把植物魔法当作
 * 没装处理，机器照常注册，只是没有法力端口。等 MMCR 上游按 Revived 的 API 重写桥接后，
 * 这个探测自然失效，不需要改这里。
 */
@Mixin(targets = "cn.howxu.mmcr.compat.botania.BotaniaBridgeBootstrap", remap = false)
public abstract class MMCRBotaniaBridgeMixin {

    private static final String LEGACY_WANDABLE = "vazkii.botania.api.block.Wandable";
    private static final String BOOTSTRAP = "cn.howxu.mmcr.compat.botania.BotaniaBridgeBootstrap";

    @Inject(method = "get()Lcn/howxu/mmcr/compat/botania/BotaniaBridge;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sjcore$fallbackWhenLegacyBotaniaApiMissing(CallbackInfoReturnable<Object> cir) {
        try {
            Class.forName(LEGACY_WANDABLE, false, MMCRBotaniaBridgeMixin.class.getClassLoader());
            return;
        } catch (Throwable missing) {
        }

        try {
            Class<?> bootstrap = Class.forName(BOOTSTRAP, false, MMCRBotaniaBridgeMixin.class.getClassLoader());
            cir.setReturnValue(bootstrap.getMethod("selectForTesting", boolean.class).invoke(null, false));
        } catch (Throwable unavailable) {
        }
    }
}
