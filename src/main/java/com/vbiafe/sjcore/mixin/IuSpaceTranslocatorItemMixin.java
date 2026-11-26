package com.vbiafe.sjcore.mixin;

import com.vbiafe.sjcore.SJCore;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 行星跃迁导航器（{@code industrialupgrade:space/planetary_translocator}）右键不再打开太空界面。
 *
 * <p>这是 IU 里唯一能把玩家送进太空的**物品**——之前的封禁删错了对象
 * （删的是 {@code basemachine3/teleporter_iu} 方块），导航器本身仍然可以合成。
 * 配方侧已由 {@code kubejs/server_scripts/bans/iu_space.js} 删掉，这里再兜一层：
 * 创造模式拿出来、或老存档里已有的导航器也点不开。
 *
 * <p>例外：玩家已经站在 IU 维度里时放行，这样界面还能打开点「返航」，不至于把人困死。
 */
@Mixin(targets = "com.denfop.items.space.ItemPlanetaryTranslocator")
public abstract class IuSpaceTranslocatorItemMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true, require = 1)
    private void sjcore$blockUse(Level level, Player player, InteractionHand hand,
                                 CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (SJCore.IU_MODID.equals(player.level().dimension().location().getNamespace())) {
            return;
        }
        SJCore.onSpaceTravelBlocked(player, null);
        cir.setReturnValue(InteractionResultHolder.pass(player.getItemInHand(hand)));
    }
}
