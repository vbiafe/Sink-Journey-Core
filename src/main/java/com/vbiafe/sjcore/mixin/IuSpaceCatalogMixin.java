package com.vbiafe.sjcore.mixin;

import com.vbiafe.sjcore.SJCore;
import com.vbiafe.sjcore.space.SpaceOreTable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 IU 的太空目录建好之后，把远征出矿表换成 {@link SpaceOreTable} 的那一份。
 *
 * <p>资源表在 {@code SpaceInit.init()} 里逐条 {@code new BaseResource(...)} 写死，构造完就挂到
 * 天体对象上，没有配置项也没有数据包入口（{@code body_resource} 配方只能加、不能删）。
 * {@code SpaceBootstrap.ensureSpaceCatalogInitialized()} 是这套目录唯一的建立入口，
 * 且只在第一次调用时真正跑 {@code init()}，所以在它返回时改一次就够，之后所有读取
 * （远征界面、JEI 文档、殖民地）看到的都是新表。
 */
@Mixin(targets = "com.denfop.api.space.dimension.SpaceBootstrap")
public abstract class IuSpaceCatalogMixin {

    @Inject(method = "ensureSpaceCatalogInitialized", at = @At("RETURN"), require = 1)
    private static void sjcore$rewriteSpaceOreTable(CallbackInfo ci) {
        try {
            SpaceOreTable.apply();
        } catch (Throwable t) {
            SJCore.LOGGER.error("[SJCore] IU 太空矿表重排失败（IU 版本不匹配？）", t);
        }
    }
}
