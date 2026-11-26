package com.vbiafe.sjcore.mixin;

import net.minecraft.resources.FileToIdConverter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只为 {@link IuSpaceDimensionRegistrationMixin} 读 {@code FileToIdConverter} 的前缀：
 * 注册表加载时靠它区分「当前扫的是 dimension 还是别的注册表」。
 */
@Mixin(FileToIdConverter.class)
public interface FileToIdConverterPrefixAccessor {

    @Accessor("prefix")
    String sjcore$prefix();
}
