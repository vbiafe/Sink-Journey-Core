package com.vbiafe.sjcore;

import com.mojang.logging.LogUtils;
import com.vbiafe.sjcore.command.SJCoreCommand;
import com.vbiafe.sjcore.iu.IuPatcher;
import com.vbiafe.sjcore.port.SinkPorts;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sink Journey Core —— 本整合包的 Java 侧魔改层。
 *
 * 目前只做一件事：给 Industrial Upgrade 打机器配方补丁（见 {@link IuPatcher}）。
 * IU 的机器配方全部在 Java 内存里（RecipesCore），KubeJS 只能靠桥模组在
 * ServerEvents.loaded 里刷新，每次 /reload 都会重复堆条目；本模组改为
 * “IU 初始化之后的那一 tick”统一施加一次补丁，先删后加，天然幂等。
 */
@Mod(SJCore.MODID)
public final class SJCore {

    public static final String MODID = "sinkjourneycore";
    public static final String IU_MODID = "industrialupgrade";
    public static final String MMCR_MODID = "mmcr";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** IU 的 TagsUpdatedEvent 之后才排队，下一 tick 统一施加补丁。 */
    private static boolean pendingPatch;

    /** 已经告警过的重复标签页条目，避免每次重建刷满日志。 */
    private static final Set<String> DUPLICATE_TAB_ENTRY_SEEN = ConcurrentHashMap.newKeySet();

    /**
     * 由 {@code ItemDisplayBuilderMixin} 调用：某个 stack 在同一标签页里被加了第二遍，
     * 已按无操作跳过，这里留一条告警方便定位是谁塞重复的。
     */
    public static void onDuplicateTabEntry(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()) + " " + stack.getComponents();
        if (DUPLICATE_TAB_ENTRY_SEEN.add(id)) {
            LOGGER.warn("[SJCore] 创造标签页重复条目已跳过：{}（OneEnoughItem 重建标签页时命中，非致命）", id);
        }
    }

    public SJCore(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(SJCore::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(SJCore::onServerTick);
        NeoForge.EVENT_BUS.addListener(SJCore::onRegisterCommands);

        if (ModList.get().isLoaded(MMCR_MODID)) {
            try {
                SinkPorts.registerAll();
            } catch (Throwable t) {
                LOGGER.error("[SJCore] 分级输入仓注册失败（MMCR 版本不匹配？）", t);
            }
        } else {
            LOGGER.info("[SJCore] 未安装 MMCR，分级输入仓不注册");
        }

        LOGGER.info("[SJCore] 已加载；Industrial Upgrade {}", isIuLoaded() ? "已安装，配方补丁启用" : "未安装，补丁空转");
    }

    public static boolean isIuLoaded() {
        return ModList.get().isLoaded(IU_MODID);
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (!isIuLoaded() || !event.shouldUpdateStaticData()) {
            return;
        }
        pendingPatch = true;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (!pendingPatch) {
            return;
        }
        pendingPatch = false;
        if (!isIuLoaded()) {
            return;
        }
        try {
            IuPatcher.applyAll();
        } catch (Throwable t) {
            // IU 没装或版本不匹配时不要把整个服务器 tick 打断
            LOGGER.error("[SJCore] IU 配方补丁异常", t);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SJCoreCommand.register(event.getDispatcher());
    }
}
