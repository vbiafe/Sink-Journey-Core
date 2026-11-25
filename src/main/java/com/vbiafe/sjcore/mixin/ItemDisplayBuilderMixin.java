package com.vbiafe.sjcore.mixin;

import com.vbiafe.sjcore.SJCore;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Set;

/**
 * OneEnoughItem 1.0.10 把 {@code EventHooks.onCreativeModeTabBuildContents} 整个换成了自己的
 * “先收集、去重、再回填”实现。原版 {@code ItemDisplayBuilder.accept} 撞到重复 stack 会抛
 * {@code IllegalStateException}；OEI 回填时（CC:Tweaked 在 server started 触发的那次全量重建，
 * IU 的“流体胶囊”页必中）这个异常直接抛进服务器 tick，存档加载中断。
 *
 * <p>这里把“重复项”降级成无操作：父页里已经有这个 stack 时不要再加一遍，同时补进搜索页
 * （避免本来只走搜索页的条目丢失），并记一条告警留痕。正常情况下不该出现重复，日志能定位来源。
 */
@Mixin(targets = "net.minecraft.world.item.CreativeModeTab$ItemDisplayBuilder")
public abstract class ItemDisplayBuilderMixin {

    @Shadow
    @Final
    private Collection<ItemStack> tabContents;

    @Shadow
    @Final
    private Set<ItemStack> searchTabContents;

    @Inject(method = "accept", at = @At("HEAD"), cancellable = true)
    private void sjcore$skipDuplicateEntry(ItemStack stack, CreativeModeTab.TabVisibility visibility, CallbackInfo ci) {
        if (visibility == CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY || !this.tabContents.contains(stack)) {
            return;
        }
        this.searchTabContents.add(stack);
        SJCore.onDuplicateTabEntry(stack);
        ci.cancel();
    }
}
