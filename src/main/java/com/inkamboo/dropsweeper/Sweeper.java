package com.inkamboo.dropsweeper;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 掉落物清理核心逻辑。
 * 设计为单例，便于从命令和 tick 事件中调用。
 */
public class Sweeper {
    public static final Sweeper INSTANCE = new Sweeper();

    private Sweeper() {
    }

    /**
     * 一次清扫的聚合结果。
     *
     * @param removed         被清理的物品总数量（按堆叠数计算）
     * @param evicted         触发垃圾箱 FIFO 逐出时被覆盖的物品总数量
     * @param blacklisted     命中黑名单被清理但不进 Dustbin 的物品数量
     * @param extraRemoved    命中的额外实体（箭矢/经验球/投掷物）数量（按 entity 数，不是 stack 数）
     * @param notFound        世界未找到的标志（仅 sweepItemsByWorldId 可能为 true）
     * @param overloadByChunk 按 chunk 聚合的物品数量（用于过载警告）。key = chunk 坐标，value = 该 chunk 中本次清理的物品堆叠总数。
     */
    public record SweepResult(
            int removed,
            int evicted,
            int blacklisted,
            int extraRemoved,
            boolean notFound,
            Map<ChunkPos, Integer> overloadByChunk
    ) {
        public SweepResult add(SweepResult other) {
            if (this.notFound || other.notFound) {
                return NOT_FOUND;
            }
            // 合并 overload map：同 chunk 累加
            Map<ChunkPos, Integer> merged = new HashMap<>(this.overloadByChunk);
            for (var e : other.overloadByChunk.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            return new SweepResult(
                    this.removed + other.removed,
                    this.evicted + other.evicted,
                    this.blacklisted + other.blacklisted,
                    this.extraRemoved + other.extraRemoved,
                    false,
                    merged
            );
        }

        public static final SweepResult NOT_FOUND = new SweepResult(0, 0, 0, 0, true, Map.of());
        public static final SweepResult EMPTY = new SweepResult(0, 0, 0, 0, false, Map.of());
    }

    /**
     * 清理指定世界内的所有掉落物。
     * 物品被搬入垃圾箱，触发逐出时记录 evicted 计数。
     * <p>
     * <b>过滤策略</b>（参考 sweepermaid，顺序固定）：
     * <ol>
     *   <li>命中白名单 → 完全跳过（不清理、不入箱）</li>
     *   <li>命中黑名单 → 清理但不进 Dustbin（避免垃圾箱爆满）</li>
     *   <li>其他 → 清理并入 Dustbin</li>
     * </ol>
     * <p>
     * <b>过载统计</b>：循环中按 {@code entity.chunkPosition()} 聚合被清理的物品数量，
     * 写入 {@link SweepResult#overloadByChunk()}。调用方（{@link OverloadNotifier}）负责判断阈值和广播。
     *
     * @param world 要清理的世界
     * @return 清理结果
     */
    public SweepResult sweepItems(ServerLevel world) {
        DropsweeperConfig cfg = DropsweeperConfig.get();
        ItemFilter whitelist = cfg.whitelistFilter();
        ItemFilter blacklist = cfg.blacklistFilter();
        Set<net.minecraft.world.entity.EntityType<?>> extraTypeFilter = cfg.extraTypeFilter();

        // ServerLevel.getEntities(EntityTypeTest, Predicate) 走 ItemEntity 分区存储，
        // 直接列全世界的 ItemEntity，不需要 AABB、不需要 instanceof 过滤。
        List<ItemEntity> items = new ArrayList<>(world.getEntities(
                EntityTypeTest.forClass(ItemEntity.class),
                e -> true
        ));

        int removed = 0;
        int evicted = 0;
        int blacklisted = 0;
        Map<ChunkPos, Integer> chunkCounts = new HashMap<>();
        for (ItemEntity item : items) {
            // 防御：循环中可能因其它回调 discard
            if (item.isRemoved()) continue;

            ItemStack stack = item.getItem();
            // getKey() 走 IdMap（O(1) 数组查表），无字符串分配
            Identifier itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());

            // 1. 白名单：完全跳过（不清理、不入箱）
            if (whitelist.matches(itemId)) continue;

            // 读 count 在 discard 之前
            int count = stack.getCount();
            // 关键：先搬进垃圾箱 / 标记黑名单，再销毁实体
            item.discard();
            removed += count;

            // 2. 黑名单：只清理，不入箱
            if (blacklist.matches(itemId)) {
                blacklisted += count;
                // 黑名单也计入过载统计——它的消失同样说明 chunk 在堆积
                chunkCounts.merge(item.chunkPosition(), count, Integer::sum);
                continue;
            }

            // 3. 默认：入箱
            Dustbin.AddResult result = Dustbin.getInstance().addItem(stack);
            evicted += result.evictedCount();
            // 计入过载统计
            chunkCounts.merge(item.chunkPosition(), count, Integer::sum);
        }

        // 4. 额外实体清理（箭矢、经验球、投掷物等）
        //    玩家 / ItemEntity 永远跳过。命中 cfg.extraTypeFilter 的全部 discard。
        int extraRemoved = 0;
        if (!extraTypeFilter.isEmpty()) {
            // 走公共 API：EntityTypeTest.forClass(Entity.class) 匹配所有实体子类
            for (Entity e : world.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    entity -> true
            )) {
                if (e.isRemoved()) continue;
                if (e instanceof ItemEntity) continue;   // 走主路径
                if (e instanceof Player) continue;        // 永远跳过玩家
                if (extraTypeFilter.contains(e.getType())) {
                    e.discard();
                    extraRemoved++;
                }
            }
        }

        return new SweepResult(removed, evicted, blacklisted, extraRemoved, false, chunkCounts);
    }

    /**
     * 清理所有世界内的所有掉落物。
     */
    public SweepResult sweepItemsAllWorlds(MinecraftServer server) {
        SweepResult total = SweepResult.EMPTY;
        for (ServerLevel world : server.getAllLevels()) {
            total = total.add(sweepItems(world));
        }
        return total;
    }

    /**
     * 清理指定世界内的所有掉落物。
     *
     * @return 清理结果；如果世界不存在或未加载则返回 {@link SweepResult#NOT_FOUND}
     */
    public SweepResult sweepItemsByWorldId(MinecraftServer server, Identifier worldId) {
        var worldKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, worldId);
        ServerLevel world = server.getLevel(worldKey);
        if (world == null) {
            return SweepResult.NOT_FOUND;
        }
        return sweepItems(world);
    }
}
