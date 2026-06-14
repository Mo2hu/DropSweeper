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
 * 当前支持：
 *   /dropsweeper items                  —— 清理当前世界的掉落物（2 级 OP）
 *   /dropsweeper items all              —— 清理所有世界的掉落物（2 级 OP）
 *   /dropsweeper items <world>          —— 清理指定世界的掉落物（2 级 OP）
 *   /dropsweeper dustbin [index]        —— 打开垃圾箱 UI 取出清扫时搬过来的物品（所有玩家）
 *   /dropsweeper whitelist add/remove/list <item>  —— 白名单管理（2 级 OP）
 *   /dropsweeper blacklist add/remove/list <item>  —— 黑名单管理（2 级 OP）
 */
public class DropsweeperCommand {

    /** 代名：指代所有世界 */
    private static final String ALL_ALIAS = "all";

    /**
     * 把"权限等级 0-4"映射到 26.1.2 的 {@link Permission} 对象。
     * <ul>
     *   <li>{@code 0} → 无权限要求（任何玩家）</li>
     *   <li>{@code 1} → {@link Permissions#COMMANDS_GAMEMASTER}</li>
     *   <li>{@code 2} → {@link Permissions#COMMANDS_MODERATOR}（OP）</li>
     *   <li>{@code 3} → {@link Permissions#COMMANDS_ADMIN}</li>
     *   <li>{@code 4} → {@link Permissions#COMMANDS_OWNER}</li>
     * </ul>
     * 26.1.2 的 {@code Permissions} 类<b>没有</b> LEVEL_0 常量——0 级在 MC 设计中等价于"无权限要求"。
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

    /**
     * 玩家是否满足指定最低权限等级。
     * <ul>
     *   <li>{@code level <= 0} → 总是返回 true（任何玩家）</li>
     *   <li>{@code level >= 1} → 检查对应 {@link Permission}</li>
     * </ul>
     * lambda 每次执行时调用，配置 reload 后立即生效。
     */
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
                    // /dropsweeper items    —— 不带参数，清当前世界
                    .executes(DropsweeperCommand::sweepCurrent)
                    // /dropsweeper items all
                    .then(Commands.literal(ALL_ALIAS)
                        .executes(DropsweeperCommand::sweepAll))
                    // /dropsweeper items <world>
                    .then(Commands.argument("world", IdentifierArgument.id())
                        .suggests(DropsweeperCommand::suggestWorlds)
                        .executes(DropsweeperCommand::sweepWorld))
                )
                // /dropsweeper dustbin [index] —— 权限来自 permissionLevelDustbin
                .then(Commands.literal("dustbin")
                    .requires(source -> hasAtLeast(source, DropsweeperConfig.get().permissionLevelDustbin))
                    .executes(ctx -> openDustbin(ctx, 0))
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> openDustbin(ctx, IntegerArgumentType.getInteger(ctx, "index"))))
                )
                // /dropsweeper whitelist {add|remove|list} <item>
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
                // /dropsweeper blacklist {add|remove|list} <item>
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
                // /dropsweeper config reload —— 热重载 config/dropsweeper.json
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

        // 可点击的"打开垃圾桶 N"链接（仅手动命令触发时显示；自动清扫由 AutoSweeper 处理）
        appendDustbinLinks(context, "可打开：");

        // 过载警告（手动命令触发也走同一通知逻辑）
        OverloadNotifier.INSTANCE.notify(context.getSource().getServer(), result.overloadByChunk());

        Dropsweeper.LOGGER.info("/dropsweeper items 清理了 {} 个掉落物，挤出了 {} 个，额外实体 {} 个",
                result.removed(), result.evicted(), result.extraRemoved());
    }

    /**
     * 给当前命令的 sender 追加一行可点击的"打开垃圾桶 i"链接（每个非空 bin 一个）。
     * <p>
     * 用在主消息后，让玩家一键跳到对应垃圾箱。
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
     * 热重载 {@code config/dropsweeper.json}。
     * <p>
     * 同时同步 {@link Dustbin} 的 bin 数量（如果 {@code dustbinCount} 变了）：
     * 增多补空 bin；减少<b>直接截断</b>多余 bin（可能丢失非空 bin 物品，回显警告）。
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
     * 打开垃圾箱 UI。
     * 玩家看到一个普通大箱子界面，但里面装的是清扫时搬过来的 ItemStack。
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

    /**
     * 在白/黑名单中增删一个物品 ID。
     *
     * @param ctx        命令上下文
     * @param isWhitelist true = 白名单 / false = 黑名单
     * @param add        true = 添加 / false = 移除
     * @return 1 = 改动成功；0 = 无变化（已在/不在名单）
     */
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

    /**
     * 列出白/黑名单的所有条目。
     *
     * @param ctx         命令上下文
     * @param isWhitelist true = 白名单 / false = 黑名单
     */
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
