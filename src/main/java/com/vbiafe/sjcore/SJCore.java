package com.vbiafe.sjcore;

import com.mojang.logging.LogUtils;
import com.vbiafe.sjcore.command.SJCoreCommand;
import com.vbiafe.sjcore.iu.IuPatcher;
import com.vbiafe.sjcore.machine.SJMachineFrames;
import com.vbiafe.sjcore.port.SinkPorts;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
 * Sink Journey Core —— 本整合包的 Java 侧魔改层。当前三件事：
 *
 * <ol>
 *   <li>给 Industrial Upgrade 打机器配方补丁（见 {@link IuPatcher}）。IU 的机器配方全部在 Java
 *       内存里（RecipesCore），KubeJS 只能靠桥模组在 ServerEvents.loaded 里刷新，每次 /reload
 *       都会重复堆条目；本模组改为「IU 初始化之后的那一 tick」统一施加一次补丁，先删后加，天然幂等。</li>
 *   <li>给 MMCR 注册按时代 / IU 机器等级分级的输入输出仓（见 {@link SinkPorts}）。</li>
 *   <li>两个兜底 mixin：标签页重复条目降级为跳过（OneEnoughItem 重建时必中），
 *       以及 IU 污染视觉在配置加载前不读配置。</li>
 * </ol>
 */
@Mod(SJCore.MODID)
public final class SJCore {

    public static final String MODID = "sinkjourneycore";
    public static final String IU_MODID = "industrialupgrade";
    public static final String MMCR_MODID = "mmcr";
    public static final String NUCLEARCRAFT_MODID = "nuclearcraftneohaul";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** IU 的 TagsUpdatedEvent 之后才排队，下一 tick 统一施加补丁。 */
    private static boolean pendingPatch;

    /** 已经告警过的重复标签页条目，避免每次重建刷满日志。 */
    private static final Set<String> DUPLICATE_TAB_ENTRY_SEEN = ConcurrentHashMap.newKeySet();

    /** IU 污染配置尚未加载时跳过视觉更新的告警只打一次。 */
    private static volatile boolean pollutionVisualSkipped;

    /** 已被拦下的行星跃迁目标，每个天体只记一条日志。 */
    private static final Set<String> SPACE_TRAVEL_BLOCKED_SEEN = ConcurrentHashMap.newKeySet();

    /**
     * 由 {@code IuSpaceDimensionRegistrationMixin} 调用：注册表加载时丢掉了 IU 的太空维度。
     *
     * @param dropped 本次少注册的维度数（dimension 注册表一次扫描）
     */
    public static void onSpaceDimensionsDropped(int dropped) {
        LOGGER.info("[SJCore] 已跳过 {} 个 IU 太空维度的注册（载人太空只走 Ad Astra，无人远征不依赖维度）", dropped);
    }

    /**
     * 由 {@code IuSpaceTravelBlockMixin} / {@code IuSpaceTranslocatorItemMixin} 调用：
     * 玩家试图用 IU 的行星跃迁设备进出太空时，统一在这里回绝并留痕。
     *
     * <p>本包的载人太空只走 Ad Astra：IU 那 34 个太空维度已被 sj-oregen 改成虚空，
     * 而 IU 的探测/远征/殖民地是纯模拟，不需要玩家落地。
     */
    public static void onSpaceTravelBlocked(Player player, String bodyName) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable("sjcore.space.travel_locked"), false);
        }
        if (bodyName != null && SPACE_TRAVEL_BLOCKED_SEEN.add(bodyName)) {
            LOGGER.info("[SJCore] 已拦截 IU 行星跃迁（目标 {}）：载人太空只走 Ad Astra 火箭", bodyName);
        }
    }

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

    /**
     * 由 {@code PollutionVisualConfigGuardMixin} 调用：IU 的污染视觉处理器在配置加载前被跳过。
     * 正常情况下第一 tick 之后就不再触发。
     */
    public static void onPollutionVisualSkipped() {
        if (pollutionVisualSkipped) {
            return;
        }
        pollutionVisualSkipped = true;
        LOGGER.info("[SJCore] IU 污染配置还没加载，本帧视觉更新已跳过（客户端首个 tick 早于配置加载，配置就绪后自动恢复）");
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

        if (ModList.get().isLoaded(NUCLEARCRAFT_MODID)) {
            try {
                SJMachineFrames.registerAll(modBus);
            } catch (Throwable t) {
                LOGGER.error("[SJCore] 电解池外壳档位注册失败（核电工艺版本不匹配？）", t);
            }
        } else {
            LOGGER.info("[SJCore] 未安装核电工艺，电解池外壳档位不注册");
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
