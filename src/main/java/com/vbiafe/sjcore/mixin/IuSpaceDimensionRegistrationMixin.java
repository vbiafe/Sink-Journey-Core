package com.vbiafe.sjcore.mixin;

import com.vbiafe.sjcore.SJCore;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/**
 * 在注册表加载阶段直接把 IU 的太空维度丢掉——不是"覆盖成虚空"，是压根不注册。
 *
 * <p>1.21.1 的维度是数据包注册表（{@code data/<ns>/dimension/*.json} → {@code minecraft:dimension}），
 * 数据包**删不掉**别的包提供的条目；但条目是 {@code RegistryDataLoader.loadContentsFromManager}
 * 逐个文件读出来的，把文件列表里的这几个摘掉，条目就不会出现。
 *
 * <p>值得做，因为原版 {@code MinecraftServer#createLevels} 会给注册表里的**每一个** LevelStem
 * 建一个 {@code ServerLevel}：34 个空的太空维度也要各自占一份 chunk 源、levelData 和 tick 钩子，
 * 只是没人去而已。不注册就没有这些开销，`/execute in industrialupgrade:mars` 也会直接报维度不存在。
 *
 * <p>{@code industrialupgrade:earth} 例外保留：蓄意留着的"母星"维度，删了怕回程逻辑踩空。
 *
 * <p>只作用于前缀为 {@code dimension} 的那次扫描；其它注册表（包括同样存在扁平 id 的
 * {@code dimension_type}、{@code recipe}）一律不动。
 */
@Mixin(targets = "net.minecraft.resources.RegistryDataLoader")
public abstract class IuSpaceDimensionRegistrationMixin {

    @Redirect(
        method = "loadContentsFromManager",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/resources/FileToIdConverter;listMatchingResources(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;"
        ),
        require = 1
    )
    private static Map<ResourceLocation, Resource> sjcore$skipIuSpaceDimensions(FileToIdConverter converter, ResourceManager manager) {
        Map<ResourceLocation, Resource> found = converter.listMatchingResources(manager);
        if (!"dimension".equals(((FileToIdConverterPrefixAccessor) (Object) converter).sjcore$prefix())) {
            return found;
        }

        Map<ResourceLocation, Resource> kept = new HashMap<>(found);
        int before = kept.size();
        kept.keySet().removeIf(file -> {
            ResourceLocation id = converter.fileToId(file);
            return SJCore.IU_MODID.equals(id.getNamespace()) && !"earth".equals(id.getPath());
        });
        if (kept.size() != before) {
            SJCore.onSpaceDimensionsDropped(before - kept.size());
        }
        return kept;
    }
}
