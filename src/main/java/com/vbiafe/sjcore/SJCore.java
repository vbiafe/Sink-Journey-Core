package com.vbiafe.sjcore;

import com.mojang.logging.LogUtils;
import com.vbiafe.sjcore.bio.BioCommand;
import com.vbiafe.sjcore.bio.SJBiology;
import com.vbiafe.sjcore.command.SJCoreCommand;
import com.vbiafe.sjcore.iu.IuPatcher;
import com.vbiafe.sjcore.port.SinkPorts;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.slf4j.Logger;

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

        // 双倍体遗传层：组件本身与 IU 无关，但读写全靠 IU 的 Genome/GenomeCrop，没装就别注册
        if (isIuLoaded()) {
            try {
                SJBiology.init(modBus);
            } catch (Throwable t) {
                LOGGER.error("[SJCore] 遗传层组件注册失败（IU 版本不匹配？）", t);
            }
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
        if (isIuLoaded()) {
            BioCommand.register(event);
        }
    }
}
