package com.vbiafe.sjcore.mixin;

import com.denfop.events.IUEventHandler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关掉 IU 的「水里做充能红石」。
 *
 * <p>IU 在 {@code IUEventHandler#onLevelTick} 里每 tick 扫描水里的物品实体，
 * 凑齐 {@code minecraft:redstone} ×4 + {@code industrialupgrade:itemdust/polonium_dust} ×1 +
 * {@code c:ingots/electrum} ×1 就消耗掉、在同一个位置生成一块充能红石。
 * 这条逻辑写死在事件处理器里，不是数据包配方，KubeJS 的 {@code event.remove} 碰不到它。
 *
 * <p>本包要的正路是 IU 的晶体充能器（红石 → 充能红石），第一块由末影龙掉落，
 * 所以这里把 {@code checkAndTransform} 整个短路：
 * 调用方只在返回非 null 时才 {@code addFreshEntity}，返回 null 就等于"这次不转换"，
 * 输入的红石 / 钋粉 / 琥珀金锭原样留在水里。
 *
 * <p>{@code require} 在 {@code sinkjourneycore.mixins.json} 里默认 0：IU 换版本、方法名对不上时
 * 这里静默跳过，游戏照常能进，只是水里那条路又回来了。
 */
@Mixin(value = IUEventHandler.class, remap = false)
public abstract class IuChargedRedstoneWaterMixin {

    private static final Logger SJ_LOGGER = LoggerFactory.getLogger("sinkjourneycore");
    private static boolean sjLogged;

    @Inject(method = "checkAndTransform", at = @At("HEAD"), cancellable = true, remap = false)
    private void sjcore$disableWaterChargedRedstone(ServerLevel level, ItemEntity entity,
                                                     CallbackInfoReturnable<ItemEntity> cir) {
        if (!sjLogged) {
            sjLogged = true;
            SJ_LOGGER.info("[SJCore] IU 的「水里做充能红石」已禁用：正路是晶体充能器，第一块由末影龙掉落");
        }
        cir.setReturnValue(null);
    }
}
