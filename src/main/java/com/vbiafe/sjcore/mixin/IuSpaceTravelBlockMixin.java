package com.vbiafe.sjcore.mixin;

import com.denfop.items.space.teleport.SpaceTeleportController;
import com.denfop.items.space.teleport.SpaceTeleportSession;
import com.vbiafe.sjcore.SJCore;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 封死 IU 太空的「玩家亲自进入」入口。
 *
 * <p>IU 3.4.0.11 里所有载人太空传送都汇到 {@code SpaceTeleportController}：界面请求
 * （{@code PacketPlanetaryTeleportRequest}）和非手持设备的调用最终都走
 * {@link SpaceTeleportController#requestTeleport}，真正换维度发生在 {@code finishOutbound}。
 * 这里两头都拦：
 *
 * <ul>
 *   <li>{@code requestTeleport} 头部取消 —— 连会话都不会创建，能量也不扣。</li>
 *   <li>{@code finishOutbound} 头部取消 + 清会话 —— 兜住「加模组之前已经在途」的存档，
 *       玩家会留在出发维度，不会掉进被 sj-oregen 改成虚空的太空维度。</li>
 * </ul>
 *
 * <p>返航（{@code requestReturn} / {@code finishReturn}）故意不拦：万一有玩家或管理员
 * 已经在 IU 维度里，得留一条回主世界的路。
 *
 * <p>IU 的无人远征（发射台 + 探测器/卫星/漫游车/火箭）与殖民地不经过这里，不受影响。
 */
@Mixin(targets = "com.denfop.items.space.teleport.SpaceTeleportController")
public abstract class IuSpaceTravelBlockMixin {

    @Inject(method = "requestTeleport", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sjcore$blockOutbound(ServerPlayer player, String bodyName, CallbackInfo ci) {
        ci.cancel();
        SJCore.onSpaceTravelBlocked(player, bodyName);
    }

    @Inject(method = "finishOutbound", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sjcore$blockLanding(ServerPlayer player, SpaceTeleportSession session, CallbackInfo ci) {
        ci.cancel();
        SpaceTeleportController.clearSession(player);
        SJCore.onSpaceTravelBlocked(player, null);
    }
}
