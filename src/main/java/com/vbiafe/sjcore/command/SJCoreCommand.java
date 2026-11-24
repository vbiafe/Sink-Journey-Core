package com.vbiafe.sjcore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.vbiafe.sjcore.SJCore;
import com.vbiafe.sjcore.iu.IuPatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/** /sjcore iu ... —— 补丁与配方池的自查命令。 */
public final class SJCoreCommand {

    private static final int MAX_LINES = 80;

    private SJCoreCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sjcore")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("iu")
                        .then(Commands.literal("reload").executes(context -> {
                            if (!SJCore.isIuLoaded()) {
                                return fail(context.getSource(), "Industrial Upgrade 未安装");
                            }
                            IuPatcher.applyAll();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[SJCore] 已重新施加 IU 配方补丁"), true);
                            return 1;
                        }))
                        .then(Commands.literal("pools").executes(context -> {
                            if (!SJCore.isIuLoaded()) {
                                return fail(context.getSource(), "Industrial Upgrade 未安装");
                            }
                            List<String> pools = IuPatcher.listPools();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[SJCore] " + pools.size() + " 个配方池：" + String.join(", ", pools)),
                                    false);
                            return pools.size();
                        }))
                        .then(Commands.literal("recipes")
                                .then(Commands.argument("pool", StringArgumentType.word()).executes(context -> {
                                    if (!SJCore.isIuLoaded()) {
                                        return fail(context.getSource(), "Industrial Upgrade 未安装");
                                    }
                                    String pool = StringArgumentType.getString(context, "pool");
                                    List<String> lines = IuPatcher.describePool(pool);
                                    for (int i = 0; i < Math.min(lines.size(), MAX_LINES); i++) {
                                        String line = lines.get(i);
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    if (lines.size() > MAX_LINES) {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("[SJCore] 还有 " + (lines.size() - MAX_LINES) + " 条，日志里有完整列表"),
                                                false);
                                    }
                                    return lines.size();
                                })))));
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[SJCore] " + message));
        return 0;
    }
}
