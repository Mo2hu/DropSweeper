package com.inkamboo.dropsweeper;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品过载警告通知器。清扫完成后被调用，对超阈值 chunk 发警告。
 * <p>
 * 收件人：有 OP 在线 → OP；无 OP → 全员；无人 → 不发。
 * 同 chunk 冷却 10 分钟避免刷屏。
 */
public final class OverloadNotifier {
    public static final OverloadNotifier INSTANCE = new OverloadNotifier();

    private static final long WARN_COOLDOWN_MS = 10L * 60L * 1000L;

    /** chunk → 上次警告时间戳（毫秒）。 */
    private final Map<ChunkPos, Long> lastWarnTime = new ConcurrentHashMap<>();

    private static final int COLOR_WARN = 0xFFAA00;

    private OverloadNotifier() {
    }

    public void notify(MinecraftServer server, Map<ChunkPos, Integer> chunkCounts) {
        if (chunkCounts == null || chunkCounts.isEmpty()) return;
        int threshold = DropsweeperConfig.get().itemOverloadThreshold;
        if (threshold <= 0) return;  // 0 = 禁用

        long now = System.currentTimeMillis();

        List<Map.Entry<ChunkPos, Integer>> toWarn = new ArrayList<>();
        for (var e : chunkCounts.entrySet()) {
            if (e.getValue() < threshold) continue;
            ChunkPos pos = e.getKey();
            Long last = lastWarnTime.get(pos);
            if (last != null && now - last < WARN_COOLDOWN_MS) continue;
            lastWarnTime.put(pos, now);
            toWarn.add(e);
        }
        if (toWarn.isEmpty()) return;

        List<ServerPlayer> recipients = pickRecipients(server);
        if (recipients.isEmpty()) return;

        for (var e : toWarn) {
            ChunkPos pos = e.getKey();
            String msg = String.format("⚠ 区域 [chunk %d, %d] 物品堆积 %d 个（超过阈值 %d）",
                    pos.x(), pos.z(), e.getValue(), threshold);
            Component component = Component.literal(msg)
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLOR_WARN)));
            for (ServerPlayer p : recipients) {
                p.sendSystemMessage(component);
            }
            Dropsweeper.LOGGER.warn("过载警告：chunk [{}, {}] 本次清扫 {} 个物品（阈值 {}）",
                    pos.x(), pos.z(), e.getValue(), threshold);
        }
    }

    private List<ServerPlayer> pickRecipients(MinecraftServer server) {
        List<ServerPlayer> ops = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                ops.add(p);
            }
        }
        if (!ops.isEmpty()) {
            return ops;
        }
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }

    public void resetCooldowns() {
        lastWarnTime.clear();
    }
}
