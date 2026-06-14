package com.inkamboo.dropsweeper;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 垃圾箱：用于存放被清扫的掉落物，玩家可随时取回。
 *
 * <p><b>多 bin 设计</b>：
 * <ul>
 *   <li>内存中维护 {@code List<SimpleContainer> bins}，每 bin 54 槽</li>
 *   <li>每 bin 独立维护 {@code long[54] slotTimestamps} 用于 FIFO</li>
 *   <li>{@code insertionCounter} 全局单调递增，保证新物品 timestamp 总是最大</li>
 * </ul>
 *
 * <p><b>添加策略</b>（{@link #addItem}）：
 * <ol>
 *   <li>跨所有 bin 尝试与同类型槽合并</li>
 *   <li>仍有剩余 → 跨所有 bin 找第一个空槽</li>
 *   <li>仍有剩余 → 跨所有 bin 找 timestamp 最小（最旧）的非空槽覆盖</li>
 * </ol>
 *
 * <p><b>持久化（26.1.2 Codec 体系）</b>：
 * <ul>
 *   <li>继承 {@link SavedData}，被 MC 视为"自定义存档数据"</li>
 *   <li>{@link SimpleContainer#setChanged()} 触发本类的 {@code setDirty()}</li>
 *   <li>MC 在合适的时机写盘到 {@code world/data/dropsweeper_dustbin.dat}</li>
 * </ul>
 *
 * <p><b>存档兼容</b>：NBT 顶层 key 从 {@code "items"} 迁移到 {@code "bins"}。
 * Codec 同时声明两个字段（旧字段 {@code optionalFieldOf} 写时跳过），读时自动识别。
 */
public class Dustbin extends SavedData {
    /** 单例。类加载时是 1 bin（向后兼容旧行为），服务器启动时被替换为从磁盘加载的实例。 */
    private static Dustbin INSTANCE = new Dustbin();

    public static Dustbin getInstance() {
        return INSTANCE;
    }

    public static void setInstance(Dustbin dustbin) {
        INSTANCE = dustbin;
    }

    /** 单个 bin 的槽位数（54 = 大箱子，6 行 9 列）。 */
    public static final int BIN_SIZE = 54;

    /** bin 容器列表（外层）。下标 = binIndex。 */
    private final List<SimpleContainer> bins = new ArrayList<>();
    /** 每 bin 的 slot 时间戳：{@code slotTimestamps.get(b)[i]} = bin b 第 i slot 的写入时间。 */
    private final List<long[]> slotTimestamps = new ArrayList<>();
    /** 全局单调递增计数器。 */
    private long insertionCounter = 0;

    /**
     * Codec：序列化 {@code List<List<ItemStack>>}（新版） + 兼容旧 {@code List<ItemStack>}。
     * <p>
     * 旧 NBT 顶层结构：{@code {"items": [stack1, stack2, ...]}}
     * <br>新 NBT 顶层结构：{@code {"bins": [[stack1, ...], [stack3, ...]]}}
     * <p>
     * 旧字段 {@code items} 写时通过 {@code forGetter(d -> Optional.empty())} 永远不写出。
     */
    public static final Codec<Dustbin> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // 新版：List<List<ItemStack>>
                    ItemStack.CODEC.listOf().listOf().fieldOf("bins")
                            .forGetter(Dustbin::serializeBins),
                    // 旧版：List<ItemStack>，写时跳过（forGetter 永远返回 empty）
                    ItemStack.CODEC.listOf().optionalFieldOf("items")
                            .forGetter(d -> Optional.empty())
            ).apply(instance, (List<List<ItemStack>> binsData, Optional<List<ItemStack>> oldItems) -> {
                Dustbin d = new Dustbin();
                if (!binsData.isEmpty()) {
                    // 新版：直接反序列化
                    d.deserializeBins(binsData);
                } else if (oldItems.isPresent()) {
                    // 旧版：把旧的单 bin 包成单元素 list，再走 deserialize 路径
                    List<List<ItemStack>> migrated = new ArrayList<>();
                    migrated.add(oldItems.get());
                    d.deserializeBins(migrated);
                }
                d.setDirty(false);  // 刚加载完，不要立刻又写盘
                return d;
            })
    );

    public static final SavedDataType<Dustbin> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("dropsweeper", "dustbin"),
            Dustbin::new,         // 工厂：没存档时调用
            CODEC,                // 序列化器
            DataFixTypes.LEVEL    // 数据修复类型
    );

    /**
     * 默认构造（Codec 工厂 + 静态初始化器都走这里）。
     * 创建 1 bin（保证旧 API 调用不 NPE）。
     * <p>
     * 注意：这里<b>不</b>读 {@link DropsweeperConfig}——
     * 静态初始化阶段配置可能未加载。{@code resize} 由 {@link Dropsweeper#registerLifecycle} 启动后调用。
     */
    public Dustbin() {
        resize(1);
    }

    /**
     * {@link #resize} 的结果。
     *
     * @param added         新增的空 bin 数
     * @param removed       截断删除的 bin 数
     * @param droppedNonEmpty 其中 <b>非空</b>（含物品）的 bin 数 —— 这部分数据<b>已丢失</b>，调用方应高亮警告
     */
    public record ResizeResult(int added, int removed, int droppedNonEmpty) {
        public static final ResizeResult NOOP = new ResizeResult(0, 0, 0);
        public boolean isTruncated() { return removed > 0; }
        public boolean lostData() { return droppedNonEmpty > 0; }
    }

    /**
     * 调整 bin 数量。
     * <ul>
     *   <li>target &gt; 当前 → 补齐空 bin（已有数据保留）</li>
     *   <li>target &lt; 当前 → <b>直接截断</b>多余 bin（无论是否为空，<b>非空 bin 中的物品会丢失</b>）</li>
     *   <li>target == 当前 → 啥也不做</li>
     * </ul>
     * <p>
     * <b>调用方责任</b>：检查返回的 {@link ResizeResult#droppedNonEmpty()}，
     * 如果 &gt; 0 必须在日志/玩家消息中明确警告"数据已丢失"。
     * <p>
     * <b>行为示例</b>（4 bin 状态 → resize(1)）：
     * <ul>
     *   <li>{@code [空, 空, 空, 空]} → 1 bin，droppedNonEmpty=0</li>
     *   <li>{@code [满, 空, 空, 空]} → 1 bin，droppedNonEmpty=1（1 个非空 bin 的物品丢失）</li>
     *   <li>{@code [满, 满, 满, 满]} → 1 bin，droppedNonEmpty=3（3 个非空 bin 的物品丢失）</li>
     * </ul>
     */
    public ResizeResult resize(int target) {
        if (target > bins.size()) {
            // 增加：补齐空 bin
            int added = 0;
            for (int i = bins.size(); i < target; i++) {
                bins.add(new SimpleContainer(BIN_SIZE) {
                    @Override
                    public void setChanged() {
                        super.setChanged();
                        Dustbin.this.setDirty();  // 触发存档写盘
                    }
                });
                slotTimestamps.add(new long[BIN_SIZE]);
                added++;
            }
            return new ResizeResult(added, 0, 0);
        } else if (target < bins.size()) {
            // 减少：直接截断（不保留任何数据，包括非空 bin 的物品）
            int removed = 0;
            int droppedNonEmpty = 0;
            for (int i = bins.size() - 1; i >= target; i--) {
                if (!isBinEmpty(i)) {
                    droppedNonEmpty++;
                }
                bins.remove(i);
                slotTimestamps.remove(i);
                removed++;
            }
            if (droppedNonEmpty > 0) {
                Dropsweeper.LOGGER.warn(
                        "Dustbin 直接截断：删除了 {} 个 bin，其中 {} 个非空，物品数据已丢失（请玩家提前用 /dropsweeper dustbin 取出）",
                        removed, droppedNonEmpty);
            }
            return new ResizeResult(0, removed, droppedNonEmpty);
        }
        return ResizeResult.NOOP;
    }

    /** bin 总数。 */
    public int binCount() {
        return bins.size();
    }

    /** 取得指定 bin 的 SimpleContainer（用于打开箱子界面）。 */
    public SimpleContainer getBin(int index) {
        if (index < 0 || index >= bins.size()) {
            throw new IndexOutOfBoundsException(
                    "Dustbin bin index " + index + " 超出范围 [0, " + bins.size() + ")"
            );
        }
        return bins.get(index);
    }

    /** 指定 bin 是否完全为空。 */
    public boolean isBinEmpty(int index) {
        SimpleContainer bin = getBin(index);
        for (int i = 0; i < BIN_SIZE; i++) {
            if (!bin.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /** 收集所有非空 bin 的索引。用于清扫完成后发"打开垃圾桶 i"链接列表。 */
    public int[] nonEmptyBinIndices() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < bins.size(); i++) {
            if (!isBinEmpty(i)) list.add(i);
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 序列化所有 bin（编码用）。每个 bin 跳过空槽（空 ItemStack 编码会抛异常）。
     */
    private List<List<ItemStack>> serializeBins() {
        List<List<ItemStack>> result = new ArrayList<>(bins.size());
        for (SimpleContainer bin : bins) {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < BIN_SIZE; i++) {
                ItemStack stack = bin.getItem(i);
                if (!stack.isEmpty()) {
                    items.add(stack);
                }
            }
            result.add(items);
        }
        return result;
    }

    /**
     * 从 List&lt;List&lt;ItemStack&gt;&gt; 还原所有 bin（解码用）。
     * <p>
     * 数据 bin 数可能比当前 {@code bins.size()} 少（玩家减少配置）或多（玩家增加配置）。
     * 这里采用"信任数据"策略：扩容到数据 bin 数与配置 bin 数的较大值，
     * 超出当前配置的 bin 会保留下来（防止"减少配置后重启 → 数据丢失"）。
     */
    private void deserializeBins(List<List<ItemStack>> data) {
        int target = Math.max(data.size(), currentConfiguredCount());
        resize(target);

        for (int b = 0; b < data.size() && b < bins.size(); b++) {
            List<ItemStack> items = data.get(b);
            SimpleContainer bin = bins.get(b);
            long[] ts = slotTimestamps.get(b);
            for (int i = 0; i < Math.min(items.size(), BIN_SIZE); i++) {
                bin.setItem(i, items.get(i));
                ts[i] = ++insertionCounter;
            }
        }
    }

    /** 当前配置中的 {@code dustbinCount}（避免读不到配置时崩）。 */
    private static int currentConfiguredCount() {
        try {
            return DropsweeperConfig.get().dustbinCount;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 单次添加物品的结果。
     */
    public record AddResult(ItemStack leftover, int evictedCount) {
        public static final AddResult FULLY_STORED = new AddResult(ItemStack.EMPTY, 0);
    }

    /**
     * 把物品塞进垃圾箱。
     * <ol>
     *   <li>跨所有 bin 尝试与同类型槽合并（合并后更新该 slot 的时间戳）</li>
     *   <li>仍有剩余 → 跨所有 bin 找第一个空槽</li>
     *   <li>仍有剩余 → 跨所有 bin 找 timestamp 最小（最旧）的非空槽覆盖</li>
     * </ol>
     */
    public AddResult addItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return AddResult.FULLY_STORED;
        }
        ItemStack remaining = stack.copy();
        long now = ++insertionCounter;

        // 第 1 步：跨所有 bin 尝试与现有同类型槽合并
        for (int b = 0; b < bins.size() && !remaining.isEmpty(); b++) {
            SimpleContainer bin = bins.get(b);
            for (int i = 0; i < BIN_SIZE && !remaining.isEmpty(); i++) {
                ItemStack existing = bin.getItem(i);
                if (existing.isEmpty()) {
                    continue;
                }
                if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                    continue;
                }
                int maxStack = existing.getMaxStackSize();
                int canTransfer = Math.min(maxStack - existing.getCount(), remaining.getCount());
                if (canTransfer > 0) {
                    existing.grow(canTransfer);
                    remaining.shrink(canTransfer);
                    bin.setItem(i, existing);  // ← 触发 setChanged() → setDirty()
                    slotTimestamps.get(b)[i] = now;
                }
            }
        }

        // 第 2 步：跨所有 bin 找空槽
        if (!remaining.isEmpty()) {
            for (int b = 0; b < bins.size(); b++) {
                SimpleContainer bin = bins.get(b);
                for (int i = 0; i < BIN_SIZE; i++) {
                    if (bin.getItem(i).isEmpty()) {
                        bin.setItem(i, remaining.copy());  // ← 触发 setChanged() → setDirty()
                        slotTimestamps.get(b)[i] = now;
                        return AddResult.FULLY_STORED;
                    }
                }
            }
        }

        // 第 3 步：跨所有 bin 找最旧（timestamp 最小）的非空 slot 覆盖
        if (!remaining.isEmpty()) {
            int oldestBin = -1, oldestSlot = -1;
            long minTs = Long.MAX_VALUE;
            for (int b = 0; b < bins.size(); b++) {
                SimpleContainer bin = bins.get(b);
                long[] ts = slotTimestamps.get(b);
                for (int i = 0; i < BIN_SIZE; i++) {
                    if (bin.getItem(i).isEmpty()) continue;
                    if (ts[i] < minTs) {
                        minTs = ts[i];
                        oldestBin = b;
                        oldestSlot = i;
                    }
                }
            }
            if (oldestBin >= 0) {
                ItemStack evicted = bins.get(oldestBin).getItem(oldestSlot);
                int evictedCount = evicted.getCount();
                bins.get(oldestBin).setItem(oldestSlot, remaining.copy());
                slotTimestamps.get(oldestBin)[oldestSlot] = now;
                return new AddResult(ItemStack.EMPTY, evictedCount);
            }
        }

        return new AddResult(remaining, 0);
    }
}
