package com.vbiafe.sjcore.bio;

import com.denfop.api.crop.genetics.EnumGenetic;
import com.denfop.api.crop.genetics.GeneticTraits;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.CommandDispatcher;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /sjbio —— 遗传层自检命令。
 *
 * <ul>
 *   <li>{@code inspect}：打印手上作物 19 轴的显性 / 隐性 / 上限；</li>
 *   <li>{@code writetest}：隐性层读写往返测试，写完立刻读回比对，然后还原原值；</li>
 *   <li>{@code cross}：把手上作物和自己交配一次（只打印，不写回），看规则跑起来什么样。</li>
 * </ul>
 */
public final class BioCommand {

    private BioCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("sjbio")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").executes(context -> inspect(context.getSource())))
                .then(Commands.literal("writetest").executes(context -> writeTest(context.getSource())))
                .then(Commands.literal("cross").executes(context -> cross(context.getSource()))));
    }

    private static int inspect(CommandSourceStack source) {
        ItemStack stack = held(source);
        if (stack == null) {
            return 0;
        }
        Map<EnumGenetic, GeneticTraits> dominant = SJBiology.dominant(stack);
        Map<EnumGenetic, GeneticTraits> recessive = SJBiology.recessive(stack);

        say(source, "== " + stack.getHoverName().getString() + " ==");
        say(source, "显性 " + dominant.size() + " 轴 / 隐性记录 " + (SJBiology.hasAlleles(stack) ? "有" : "无（按显=隐回填）")
                + " / 总档位 " + SJBiology.totalTiers(dominant, recessive));

        for (CropAxis.Group group : CropAxis.Group.values()) {
            List<String> lines = new ArrayList<>();
            for (CropAxis.Axis axis : CropAxis.all().values()) {
                if (axis.group() != group) {
                    continue;
                }
                GeneticTraits dom = dominant.get(axis.genetic());
                GeneticTraits rec = recessive.get(axis.genetic());
                if (dom == null && rec == null) {
                    continue;
                }
                lines.add(axis.nameZh() + " " + tier(axis, dom) + "/" + tier(axis, rec) + "/" + axis.cap());
            }
            if (!lines.isEmpty()) {
                say(source, "[" + group.label + "] " + String.join("  ", lines));
            }
        }
        return dominant.size();
    }

    private static int writeTest(CommandSourceStack source) {
        ItemStack stack = held(source);
        if (stack == null) {
            return 0;
        }
        Map<EnumGenetic, GeneticTraits> dominant = new EnumMap<>(SJBiology.dominant(stack));
        Map<EnumGenetic, GeneticTraits> original = new EnumMap<>(SJBiology.recessive(stack));

        // 造一份和显性不同、且贴住上限的隐性层，才验得出有没有写进去
        Map<EnumGenetic, GeneticTraits> probe = new EnumMap<>(EnumGenetic.class);
        for (CropAxis.Axis axis : CropAxis.all().values()) {
            if (axis.cap() > 0) {
                probe.put(axis.genetic(), axis.traitAt(axis.cap()));
            }
        }

        SJBiology.write(stack, dominant, probe);
        Map<EnumGenetic, GeneticTraits> readBack = new EnumMap<>(SJBiology.recessive(stack));
        boolean writeOk = probe.equals(readBack);
        boolean dominantKept = dominant.equals(SJBiology.dominant(stack));

        SJBiology.write(stack, dominant, original);
        boolean restoreOk = original.equals(SJBiology.recessive(stack));

        say(source, "[SJCore] 隐性层写入 " + (writeOk ? "通过" : "失败")
                + "，显性层保持不变 " + (dominantKept ? "是" : "否（被覆盖了）")
                + "，还原 " + (restoreOk ? "通过" : "失败"));
        return writeOk && dominantKept && restoreOk ? 1 : 0;
    }

    private static int cross(CommandSourceStack source) {
        ItemStack stack = held(source);
        if (stack == null) {
            return 0;
        }
        Map<EnumGenetic, GeneticTraits> dominant = SJBiology.dominant(stack);
        Map<EnumGenetic, GeneticTraits> recessive = SJBiology.recessive(stack);
        Player player = source.getPlayer();
        SJBiology.Offspring child = SJBiology.cross(dominant, recessive, dominant, recessive,
                Rng.seeded(player == null ? 0L : player.level().getGameTime() + stack.hashCode()));

        say(source, "== 自交一次（不写回，仅演示规则） ==");
        for (CropAxis.Group group : CropAxis.Group.values()) {
            List<String> lines = new ArrayList<>();
            for (CropAxis.Axis axis : CropAxis.all().values()) {
                if (axis.group() != group) {
                    continue;
                }
                GeneticTraits parentDom = dominant.get(axis.genetic());
                GeneticTraits childDom = child.dominant().get(axis.genetic());
                GeneticTraits childRec = child.recessive().get(axis.genetic());
                if (parentDom == null && childDom == null) {
                    continue;
                }
                lines.add(axis.nameZh() + " " + tier(axis, parentDom) + "→" + tier(axis, childDom) + "/" + tier(axis, childRec));
            }
            if (!lines.isEmpty()) {
                say(source, "[" + group.label + "] " + String.join("  ", lines));
            }
        }
        return child.dominant().size();
    }

    private static String tier(CropAxis.Axis axis, GeneticTraits trait) {
        int tier = trait == null ? 0 : axis.tier(trait);
        return tier == 0 ? "-" : (axis.kind() == CropAxis.Kind.LADDER ? String.valueOf(tier) : String.format(Locale.ROOT, "%s", trait.name()));
    }

    private static ItemStack held(CommandSourceStack source) {
        Player player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[SJCore] 这条命令需要玩家手持作物执行"));
            return null;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("[SJCore] 主手是空的"));
            return null;
        }
        return stack;
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
