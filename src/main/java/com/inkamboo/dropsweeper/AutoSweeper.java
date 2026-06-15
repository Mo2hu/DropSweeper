package com.inkamboo.dropsweeper;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;

/**
 * 自动周期清扫器。每个 server tick 末被调用，按 {@link DropsweeperConfig#sweepIntervalSeconds}
 * 周期触发 {@link Sweeper#sweepItemsAllWorlds}，并在到点前给玩家发倒计时提示。
 * <p>
 * 首次 tick 跳过（避免服务器启动瞬间误触）。
 */
public class AutoSweeper {
    public static final AutoSweeper INSTANCE = new AutoSweeper();

    /** 距下次清扫的 tick 数。1 tick = 1/20 秒。 */
    private int ticksUntilNextSweep;
    /** 首次 tick 跳过（避免在玩家进入前就触发） */
    private boolean firstTick = true;
    /** 本轮已发送过的"长时间"提示点，防止同一秒重发。 */
    private final Set<Integer> longPointsSent = new HashSet<>();

    // 颜色常量
    private static final int COLOR_GRAY = 0xAAAAAA;       // 长时间提示（ActionBar）
    private static final int COLOR_GOLD = 0xFFAA00;       // 短时间提示（聊天）
    private static final int COLOR_AQUA = 0x55FFFF;       // 清扫完成主消息
    private static final int COLOR_GREEN = 0x55FF55;     // 可点击链接

    private AutoSweeper() {
    }

    /** 在 {@code ServerTickEvents.END_SERVER_TICK} 调用。 */
    public void onServerTick(MinecraftServer server) {
        DropsweeperConfig cfg = DropsweeperConfig.get();
        if (cfg.sweepIntervalSeconds <= 0) {
            // 0 = 禁用周期清扫，但仍保留手动 / 命令触发
            return;
        }

        if (firstTick) {
            firstTick = false;
            ticksUntilNextSweep = cfg.sweepIntervalSeconds * 20;
            return;
        }

        // 记录递减前的秒数。每跨过整数秒边界（20 ticks）才发一次提示，
        // 否则 (ticks/20) 在整个 1 秒区间内保持不变，会导致每 tick 刷屏。
        int prevSecondsLeft = this.ticksUntilNextSweep / 20;
        ticksUntilNextSweep--;
        int secondsLeft = this.ticksUntilNextSweep / 20;

        if (secondsLeft != prevSecondsLeft) {
            announceIfNeeded(server, secondsLeft);
        }

        if (ticksUntilNextSweep <= 0) {
            doSweep(server);
            resetCountdown();
        }
    }

    /** 手动触发清扫后调用，重置倒计时避免紧接着又自动清扫。 */
    public void resetCountdown() {
        ticksUntilNextSweep = DropsweeperConfig.get().sweepIntervalSeconds * 20;
        longPointsSent.clear();
    }

    private void doSweep(MinecraftServer server) {
        Sweeper.SweepResult result = Sweeper.INSTANCE.sweepItemsAllWorlds(server);
        broadcastSweepResult(server, result);
        // 过载警告（在主消息和链接之后，不阻塞主消息）
        OverloadNotifier.INSTANCE.notify(server, result.overloadByChunk());
        Dropsweeper.LOGGER.info("自动清扫完成: removed={}, evicted={}",
                result.removed(), result.evicted());
    }

    /**
     * 广播清扫完成消息 + 非空 bin 的可点击链接。
     */
    private void broadcastSweepResult(MinecraftServer server, Sweeper.SweepResult result) {
        DropsweeperConfig cfg = DropsweeperConfig.get();
        boolean useActionBar = cfg.announcementChannel.isCompleteActionBar();

        // 主消息
        String main = cfg.messageAfterSweep
                .replace("$1", String.valueOf(result.removed()))
                .replace("$2", String.valueOf(result.evicted()))
                .replace("$3", String.valueOf(result.extraRemoved()));
        broadcastComponent(server,
                Component.literal(main).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_AQUA))),
                useActionBar);

        // 可点击的非空 bin 链接列表
        int[] nonEmpty = Dustbin.getInstance().nonEmptyBinIndices();
        if (nonEmpty.length > 0) {
            MutableComponent linkLine = Component.empty();
            for (int i = 0; i < nonEmpty.length; i++) {
                int idx = nonEmpty[i];
                if (i > 0) linkLine = linkLine.append(Component.literal(" "));
                linkLine = linkLine.append(
                        Component.literal("[" + cfg.dustbinName + idx + "]")
                                .withStyle(Style.EMPTY
                                        .withColor(TextColor.fromRgb(COLOR_GREEN))
                                        .withClickEvent(new ClickEvent.RunCommand(
                                                "/dropsweeper dustbin " + idx))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal("点击打开"))))
                );
            }
            broadcastComponent(server, linkLine, useActionBar);
        }
    }

    private void announceIfNeeded(MinecraftServer server, int secondsLeft) {
        DropsweeperConfig cfg = DropsweeperConfig.get();
        boolean useActionBar = cfg.announcementChannel.isLongActionBar();

        // 长时间提示：60/30/15（每点只发一次）
        for (int p : cfg.announcePointsLong) {
            if (secondsLeft == p && !longPointsSent.contains(p)) {
                longPointsSent.add(p);
                String msg = cfg.messageBeforeSweepLong.replace("$1", String.valueOf(secondsLeft));
                broadcastComponent(server,
                        Component.literal(msg).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_GRAY))),
                        useActionBar);
                return;
            }
        }

        // 短时间提示：<= announceShortThreshold 的整数秒每秒一次（除非已被长时间提示覆盖）
        if (secondsLeft > 0 && secondsLeft <= cfg.announceShortThreshold) {
            for (int p : cfg.announcePointsLong) {
                if (p == secondsLeft) {
                    return; // 已被长时间提示覆盖
                }
            }
            String msg = cfg.messageBeforeSweepShort.replace("$1", String.valueOf(secondsLeft));
            broadcastComponent(server,
                    Component.literal(msg).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_GOLD))),
                    cfg.announcementChannel.isShortActionBar());
        }
    }

    /**
     * 把 Component 广播给所有玩家，可选 ActionBar。
     * <p>
     * ActionBar 显示约 3 秒后自动消失（MC 客户端默认行为），<b>新消息会替换旧消息</b>，
     * 因此长时间提示不会一直挂在屏幕上。
     */
    private void broadcastComponent(MinecraftServer server, Component component, boolean useActionBar) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (useActionBar) {
                player.connection.send(new ClientboundSetActionBarTextPacket(component));
            } else {
                player.sendSystemMessage(component);
            }
        }
    }
}
