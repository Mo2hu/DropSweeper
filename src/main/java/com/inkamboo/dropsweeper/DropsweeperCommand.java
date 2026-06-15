package com.inkamboo.dropsweeper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;

import java.util.concurrent.CompletableFuture;

/**
 * /dropsweeper 命令树。
 *   /dropsweeper items [all | <world>]
 *   /dropsweeper dustbin [index]
 *   /dropsweeper whitelist/blacklist add/remove/list <item>
 *   /dropsweeper config reload
 */
public class DropsweeperCommand {

    private static final String ALL_ALIAS = "all";

    /**
     * 把权限等级 0-4 映射到 26.1.2 的 {@link Permission}。26.1.2 没有 LEVEL_0 常量，
     * 0 级由 {@code hasAtLeast} 当作"任何玩家"处理。
     */
    private static Permission resolvePermission(int level) {
        return switch (level) {
            case 1 -> Permissions.COMMANDS_GAMEMASTER;
            case 2 -> Permissions.COMMANDS_MODERATOR;
            case 3 -> Permissions.COMMANDS_ADMIN;
            case 4 -> Permissions.COMMANDS_OWNER;
            default -> Permissions.COMMANDS_MODERATOR;  // 非法值兜底
        };
    }

    private static boolean hasAtLeast(CommandSourceStack source, int level) {
        if (level <= 0) return true;
        return source.permissions().hasPermission(resolvePermission(level));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal(Dropsweeper.MOD_ID)
                .then(Commands.literal("items")
                    // 动态读 permissionLevelClean：reload 后立即生效
                    .requires(source -> hasAtLeast(source, DropsweeperConfig.get().permissionLevelClean))
                    .executes(DropsweeperCommand::sweepCurrent)
                    .then(Commands.literal(ALL_ALIAS)
                        .executes(DropsweeperCommand::sweepAll))
                    .then(Commands.argument("world", IdentifierArgument.id())
                        .suggests(DropsweeperCommand::suggestWorlds)
                        .executes(DropsweeperCommand::sweepWorld))
                )
                .then(Commands.literal("dustbin")
                    .requires(source -> hasAtLeast(source, DropsweeperConfig.get().permissionLevelDustbin))
                    .executes(ctx -> openDustbin(ctx, 0))
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> openDustbin(ctx, IntegerArgumentType.getInteger(ctx, "index"))))
                )
                .then(Commands.literal("whitelist")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("add")
                        .then(Commands.argument("item", IdentifierArgument.id())
                            .executes(ctx -> modifyList(ctx, true, true))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("item", IdentifierArgument.id())
                            .executes(ctx -> modifyList(ctx, true, false))))
                    .then(Commands.literal("list")
                        .executes(ctx -> listList(ctx, true)))
                )
                .then(Commands.literal("blacklist")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("add")
                        .then(Commands.argument("item", IdentifierArgument.id())
                            .executes(ctx -> modifyList(ctx, false, true))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("item", IdentifierArgument.id())
                            .executes(ctx -> modifyList(ctx, false, false))))
                    .then(Commands.literal("list")
                        .executes(ctx -> listList(ctx, false)))
                )
                .then(Commands.literal("config")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("reload")
                        .executes(DropsweeperCommand::reloadConfig))
                )
        );
    }

    private static int sweepCurrent(CommandContext<CommandSourceStack> context) {
        var world = context.getSource().getLevel();
        var result = Sweeper.INSTANCE.sweepItems(world);
        var worldId = world.dimension().identifier();
        sendSweepMessage(context, result, worldId.toString());
        AutoSweeper.INSTANCE.resetCountdown();
        return 1;
    }

    private static int sweepAll(CommandContext<CommandSourceStack> context) {
        var result = Sweeper.INSTANCE.sweepItemsAllWorlds(context.getSource().getServer());
        sendSweepMessage(context, result, "所有世界");
        AutoSweeper.INSTANCE.resetCountdown();
        return 1;
    }

    private static int sweepWorld(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var worldId = IdentifierArgument.getId(context, "world");
        var result = Sweeper.INSTANCE.sweepItemsByWorldId(context.getSource().getServer(), worldId);
        if (result.notFound()) {
            context.getSource().sendFailure(
                Component.literal("世界 " + worldId + " 不存在或未加载")
            );
            return 0;
        }
        sendSweepMessage(context, result, worldId.toString());
        AutoSweeper.INSTANCE.resetCountdown();
        return 1;
    }

    /**
     * 发送清扫结果消息。
     * <p>
     * 主消息（青色）后追加非空 bin 的可点击链接（绿色 + 点击执行 {@code /dropsweeper dustbin <i>}），
     * 方便玩家在误清后立即打开垃圾箱取回物品。
     */
    private static void sendSweepMessage(
        CommandContext<CommandSourceStack> context,
        Sweeper.SweepResult result,
        String worldLabel
    ) {
        StringBuilder main = new StringBuilder("清理了 ").append(result.removed()).append(" 个掉落物 (").append(worldLabel).append(")，已搬入垃圾箱");
        if (result.evicted() > 0) {
            main.append("。垃圾箱已满，挤出了 ").append(result.evicted()).append(" 个");
        }
        if (result.extraRemoved() > 0) {
            main.append("，清理额外实体 ").append(result.extraRemoved()).append(" 个");
        }
        // 主消息
        context.getSource().sendSuccess(
            () -> Component.literal(main.toString()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))),
            true
        );

        // 可点击的"打开垃圾桶 N"链接（手动命令触发时显示；自动清扫由 AutoSweeper 处理）
        appendDustbinLinks(context, "可打开：");

        OverloadNotifier.INSTANCE.notify(context.getSource().getServer(), result.overloadByChunk());

        Dropsweeper.LOGGER.info("/dropsweeper items 清理了 {} 个掉落物，挤出了 {} 个，额外实体 {} 个",
                result.removed(), result.evicted(), result.extraRemoved());
    }

    /**
     * 追加可点击的"打开垃圾桶 i"链接（每个非空 bin 一个）。
     */
    private static void appendDustbinLinks(CommandContext<CommandSourceStack> context, String prefix) {
        int[] nonEmpty = Dustbin.getInstance().nonEmptyBinIndices();
        if (nonEmpty.length == 0) return;

        DropsweeperConfig cfg = DropsweeperConfig.get();
        MutableComponent line = Component.literal(prefix);
        for (int i = 0; i < nonEmpty.length; i++) {
            int idx = nonEmpty[i];
            if (i > 0) line = line.append(Component.literal(" "));
            line = line.append(
                Component.literal("[" + cfg.dustbinName + idx + "]")
                    .withStyle(Style.EMPTY
                        .withColor(TextColor.fromRgb(0x55FF55))
                        .withClickEvent(new ClickEvent.RunCommand(
                            "/dropsweeper dustbin " + idx))
                        .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("点击打开"))))
            );
        }
        final MutableComponent finalLine = line;
        context.getSource().sendSuccess(() -> finalLine, true);
    }

    /**
     * 热重载 config/dropsweeper.json。减少 dustbinCount 时直接截断（可能丢失非空 bin 物品，回显警告）。
     */
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        int oldCount = Dustbin.getInstance().binCount();
        Dustbin.ResizeResult result = DropsweeperConfig.reload();
        int newCount = Dustbin.getInstance().binCount();
        int color;
        StringBuilder msg = new StringBuilder("配置已重载");
        if (oldCount != newCount) {
            msg.append("，垃圾箱 ").append(oldCount).append(" → ").append(newCount);
        }
        if (result.lostData()) {
            msg.append("（⚠ 警告：丢失了 ").append(result.droppedNonEmpty())
                    .append(" 个非空 bin 的物品数据！）");
            color = 0xFF5555;  // 红色警告
        } else {
            color = 0x55FF55;  // 绿色
        }
        final MutableComponent component = Component.literal(msg.toString())
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
        context.getSource().sendSuccess(() -> component, true);
        Dropsweeper.LOGGER.info("/dropsweeper config reload 触发，垃圾箱 {} → {}, 丢失非空 {}",
                oldCount, newCount, result.droppedNonEmpty());
        return 1;
    }

    /**
     * 打开垃圾箱 UI。玩家看到普通大箱子界面，里面是清扫时搬过来的 ItemStack。
     */
    private static int openDustbin(CommandContext<CommandSourceStack> context, int index) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        if (index < 0 || index >= Dustbin.getInstance().binCount()) {
            context.getSource().sendFailure(
                Component.literal("垃圾箱索引超出范围（有效范围 0 ~ " + (Dustbin.getInstance().binCount() - 1) + "）")
            );
            return 0;
        }

        String name = DropsweeperConfig.get().dustbinName + index;
        SimpleContainer container = Dustbin.getInstance().getBin(index);
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal(name);
            }

            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player p) {
                // ChestMenu.sixRows = 6 行大箱子（54 槽）
                return ChestMenu.sixRows(windowId, inventory, container);
            }
        });
        return 1;
    }

    /** Tab 补全：列出服务端已加载的所有世界 ID */
    private static CompletableFuture<Suggestions> suggestWorlds(
        CommandContext<CommandSourceStack> context,
        SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(
            context.getSource().getServer().levelKeys().stream()
                .map(key -> key.identifier().toString()),
            builder
        );
    }

    // ===================== 白/黑名单管理 =====================

    private static int modifyList(
        CommandContext<CommandSourceStack> ctx,
        boolean isWhitelist,
        boolean add
    ) throws CommandSyntaxException {
        Identifier id = IdentifierArgument.getId(ctx, "item");
        String idStr = id.toString();
        String displayName = isWhitelist ? "白名单" : "黑名单";
        var action = add ? "添加" : "移除";

        DropsweeperConfig cfg = DropsweeperConfig.get();
        java.util.List<String> list = isWhitelist ? cfg.itemWhitelist : cfg.itemBlacklist;

        boolean changed;
        if (add) {
            if (list.contains(idStr)) {
                ctx.getSource().sendSuccess(
                    () -> Component.literal(idStr + " 已在" + displayName), false);
                return 0;
            }
            list.add(idStr);
            changed = true;
        } else {
            if (!list.remove(idStr)) {
                ctx.getSource().sendSuccess(
                    () -> Component.literal(idStr + " 不在" + displayName), false);
                return 0;
            }
            changed = true;
        }

        if (changed) {
            cfg.rebuildDerived();
            DropsweeperConfig.save();
        }
        ctx.getSource().sendSuccess(
            () -> Component.literal("已" + action + " " + idStr + " 到" + displayName), true);
        Dropsweeper.LOGGER.info("管理员修改了{}: {}", displayName, idStr);
        return 1;
    }

    private static int listList(
        CommandContext<CommandSourceStack> ctx,
        boolean isWhitelist
    ) {
        String displayName = isWhitelist ? "白名单" : "黑名单";
        DropsweeperConfig cfg = DropsweeperConfig.get();
        var list = isWhitelist ? cfg.itemWhitelist : cfg.itemBlacklist;

        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(
                () -> Component.literal(displayName + " 为空"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(displayName).append(" (").append(list.size()).append(") ===\n");
        for (String s : list) {
            sb.append("  - ").append(s).append("\n");
        }
        // 去掉末尾换行，避免聊天里出现空行
        String text = sb.substring(0, sb.length() - 1);
        final String finalText = text;
        ctx.getSource().sendSuccess(() -> Component.literal(finalText), false);
        return 1;
    }
}
