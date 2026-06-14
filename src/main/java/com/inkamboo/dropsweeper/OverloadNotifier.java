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
 * 物品过载警告通知器。
 * <p>
 * 在每次清扫完成后被调用，遍历 {@link Sweeper.SweepResult#overloadByChunk()}，
 * 对超过 {@link DropsweeperConfig#itemOverloadThreshold} 的 chunk 发送警告。
 * <p>
 * <b>收件人策略</b>：
 * <ul>
 *   <li>至少 1 个 OP 在线 → 只发给 OP</li>
 *   <li>无 OP 在线 → 发给所有在线玩家（提示他们"管理员不在服务器"）</li>
 *   <li>无人在线 → 啥也不做</li>
 * </ul>
 * <p>
 * <b>冷却机制</b>：同一 chunk 在 10 分钟内只警告一次（避免刷屏）。
 */
public final class OverloadNotifier {
    public static final OverloadNotifier INSTANCE = new OverloadNotifier();

    /** 同 chunk 警告冷却时间（10 分钟）。 */
    private static final long WARN_COOLDOWN_MS = 10L * 60L * 1000L;

    /** chunk → 上次警告时间戳（毫秒）。 */
    private final Map<ChunkPos, Long> lastWarnTime = new ConcurrentHashMap<>();

    /** 警告消息颜色（橙色醒目）。 */
    private static final int COLOR_WARN = 0xFFAA00;

    private OverloadNotifier() {
    }

    /**
     * 检查并广播过载警告。
     *
     * @param server        服务端实例
     * @param chunkCounts   按 chunk 聚合的物品数量（来自 {@link Sweeper.SweepResult#overloadByChunk()}）
     */
    public void notify(MinecraftServer server, Map<ChunkPos, Integer> chunkCounts) {
        if (chunkCounts == null || chunkCounts.isEmpty()) return;
        int threshold = DropsweeperConfig.get().itemOverloadThreshold;
        if (threshold <= 0) return;  // 0 = 禁用过载警告

        long now = System.currentTimeMillis();

        // 收集所有过载 chunk（按阈值 + 冷却过滤）
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

        // 确定收件人
        List<ServerPlayer> recipients = pickRecipients(server);
        if (recipients.isEmpty()) return;

        // 发送警告
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

    /**
     * 收件人选择：有 OP 发给 OP，无 OP 发给所有玩家。
     */
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
        // 无 OP：广播给所有玩家（提示"管理员不在服务器"）
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }

    /** 清空警告冷却（用于测试 / 重置）。 */
    public void resetCooldowns() {
        lastWarnTime.clear();
    }
}
