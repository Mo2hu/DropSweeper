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
 * <p>
 * 多 bin + FIFO + 跨箱合并/逐出。继承 {@link SavedData}，MC 写盘到
 * {@code world/data/dropsweeper_dustbin.dat}。NBT 顶层 key 从 {@code "items"} 迁移到 {@code "bins"}，
 * Codec 用 {@code optionalFieldOf("items")} 兼容旧存档。
 */
public class Dustbin extends SavedData {
    private static Dustbin INSTANCE = new Dustbin();

    public static Dustbin getInstance() {
        return INSTANCE;
    }

    public static void setInstance(Dustbin dustbin) {
        INSTANCE = dustbin;
    }

    public static final int BIN_SIZE = 54;

    private final List<SimpleContainer> bins = new ArrayList<>();
    private final List<long[]> slotTimestamps = new ArrayList<>();
    private long insertionCounter = 0;

    public static final Codec<Dustbin> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ItemStack.CODEC.listOf().listOf().fieldOf("bins")
                            .forGetter(Dustbin::serializeBins),
                    ItemStack.CODEC.listOf().optionalFieldOf("items")
                            .forGetter(d -> Optional.empty())
            ).apply(instance, (List<List<ItemStack>> binsData, Optional<List<ItemStack>> oldItems) -> {
                Dustbin d = new Dustbin();
                if (!binsData.isEmpty()) {
                    d.deserializeBins(binsData);
                } else if (oldItems.isPresent()) {
                    // 旧存档 "items" 字段：单 bin 包装后走同一路径
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
            Dustbin::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    /**
     * 默认构造。创建 1 bin（保证旧 API 调用不 NPE）。
     * <p>
     * 不读 {@link DropsweeperConfig}（静态初始化阶段配置可能未加载），
     * {@code resize} 由 {@link Dropsweeper} 启动后调用。
     */
    public Dustbin() {
        resize(1);
    }

    /**
     * {@link #resize} 的结果。{@code droppedNonEmpty} &gt; 0 时调用方应高亮警告数据丢失。
     */
    public record ResizeResult(int added, int removed, int droppedNonEmpty) {
        public static final ResizeResult NOOP = new ResizeResult(0, 0, 0);
        public boolean isTruncated() { return removed > 0; }
        public boolean lostData() { return droppedNonEmpty > 0; }
    }

    /**
     * 调整 bin 数量。target &lt; 当前时<b>直接截断</b>（非空 bin 物品会丢失）。
     * <p>
     * 行为示例（4 bin 状态 → resize(1)）：
     * <ul>
     *   <li>{@code [空, 空, 空, 空]} → 1 bin，droppedNonEmpty=0</li>
     *   <li>{@code [满, 满, 满, 满]} → 1 bin，droppedNonEmpty=3</li>
     *   <li>{@code [满, 满, 空, 空]} → 2 bin，droppedNonEmpty=0（保留前 2 个非空）</li>
     * </ul>
     *
     * @param target 目标 bin 数
     * @return 调整结果
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

    public int binCount() {
        return bins.size();
    }

    public SimpleContainer getBin(int index) {
        if (index < 0 || index >= bins.size()) {
            throw new IndexOutOfBoundsException(
                    "Dustbin bin index " + index + " 超出范围 [0, " + bins.size() + ")"
            );
        }
        return bins.get(index);
    }

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
