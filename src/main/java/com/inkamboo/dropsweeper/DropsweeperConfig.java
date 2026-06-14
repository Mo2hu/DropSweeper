package com.inkamboo.dropsweeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * dropsweeper 配置文件。
 * <p>
 * 位置：{@code config/dropsweeper.json}（Fabric 配置目录）。
 * 首次启动时若文件不存在则生成默认值；之后玩家可自由编辑。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code sweepIntervalSeconds}：自动清扫周期（秒）。{@code 0} 表示禁用自动清扫。</li>
 *   <li>{@code announcePointsLong}：长时间倒计时提示点（秒），例如 [60, 30, 15]。</li>
 *   <li>{@code announceShortThreshold}：短时间倒计时的秒数阈值（≤该值的整数秒每秒发一次）。</li>
 *   <li>{@code messageBeforeSweepLong}：长时间提示文案，{@code $1} 会被替换为剩余秒数。</li>
 *   <li>{@code messageBeforeSweepShort}：短时间提示文案，{@code $1} 会被替换为剩余秒数。</li>
 *   <li>{@code messageAfterSweep}：清扫完成文案，{@code $1} 为清理数量，{@code $2} 为挤出数量。</li>
 * </ul>
 */
public class DropsweeperConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    public int sweepIntervalSeconds = 600;                 // 10 分钟
    public int[] announcePointsLong = {120, 60, 30};
    public int announceShortThreshold = 3;
    public String messageBeforeSweepLong = "【dropsweeper】: 嘿嘿，$1 秒后扫地姬要开始打扫啦~ 大家快把想要的东西捡起来哦！ฅ^•ﻌ•^ฅ";
    public String messageBeforeSweepShort = "【dropsweeper】: 主人注意啦~ 还剩 $1 秒！扫地姬要动手啦，别怪我不提醒哦！(๑•̀ㅂ•́)و✧";
    public String messageAfterSweep = "【dropsweeper】: 打扫完成~ 这次清理了 $1 个掉落物！屋子干干净净啦~(ˊᗜˋ)♪";

    /** 物品白名单（永不清理）。完全匹配 ID 或 {@code modid:*} 通配符。 */
    public java.util.List<String> itemWhitelist = new java.util.ArrayList<>(java.util.List.of(
            "minecraft:nether_star",
            "minecraft:heavy_core"
    ));
    /** 物品黑名单（清理但不进 Dustbin）。完全匹配 ID 或 {@code modid:*} 通配符。 */
    public java.util.List<String> itemBlacklist = new java.util.ArrayList<>(java.util.List.of(
            "minecraft:cobblestone",
            "minecraft:sand",
            "minecraft:gravel"
    ));
    /** 是否启用 {@code modid:*} 通配符。 */
    public boolean matchWildcard = true;

    /**
     * 垃圾箱数量。每个垃圾箱 54 槽。
     * <p>
     * <b>变更时</b>：增多 → 补齐空 bin（数据保留）；减少 → <b>不生效</b>（避免误删数据）。
     * 重启时由 Dustbin.resize() 处理。
     */
    public int dustbinCount = 1;
    /** 垃圾箱 UI 标题前缀。最终显示为 {@code dustbinName + index}，例如 "垃圾桶 0"。 */
    public String dustbinName = "垃圾桶 ";

    /**
     * 三类提示的发送通道（ActionBar vs 聊天）。
     * <p>
     * ActionBar：屏幕上方小字，约 3 秒后自动消失，新消息会替换旧消息，<b>不刷屏</b>。
     * 聊天：常规玩家消息，会留在历史里。
     */
    public AnnouncementChannel announcementChannel = new AnnouncementChannel();

    /**
     * 提示发送通道配置。
     * <p>
     * JSON 配置示例：
     * <pre>{@code
     * "announcementChannel": {
     *   "long": "actionbar",
     *   "short": "chat",
     *   "complete": "chat"
     * }
     * }</pre>
     * <p>
     * 字段名 {@code long} 是 Java 关键字，因此用 {@code longCountdown} 映射。
     */
    public static class AnnouncementChannel {
        public static final String ACTIONBAR = "actionbar";
        public static final String CHAT = "chat";

        /** 长时间倒计时（60/30/15s 之类）。默认 ActionBar 灰色，不刷屏。 */
        public String longCountdown = ACTIONBAR;
        /** 短时间倒计时（1-10s）。默认聊天金色，醒目。 */
        public String shortCountdown = CHAT;
        /** 清扫完成消息。默认聊天青色 + 可点击链接。 */
        public String complete = CHAT;

        public boolean isLongActionBar() { return ACTIONBAR.equalsIgnoreCase(longCountdown); }
        public boolean isShortActionBar() { return ACTIONBAR.equalsIgnoreCase(shortCountdown); }
        public boolean isCompleteActionBar() { return ACTIONBAR.equalsIgnoreCase(complete); }
    }

    /**
     * 单 chunk 物品堆积阈值。清扫时如果某 chunk 中清理的物品总数超过此值，
     * 给 OP（无 OP 时给所有玩家）发警告。
     * <p>
     * <b>0 = 禁用</b>过载警告。
     * <p>
     * 检测机制：只在清扫时按 {@code ChunkPos} 聚合已扫描到的 ItemEntity，<b>0 额外扫描</b>。
     */
    public int itemOverloadThreshold = 640;

    /**
     * 额外清理的实体类型（参考 sweepermaid 的 EXTRA_ENTITY_TYPES）。
     * <p>
     * 命中列表的实体（箭矢、经验球、投掷物等）会在清扫时被清理。
     * <p>
     * <b>注意</b>：
     * <ul>
     *   <li>玩家（{@code Player}）永远跳过，不会被错误清理</li>
     *   <li>ItemEntity 走主路径（白/黑名单 + Dustbin）不在此处处理</li>
     *   <li>ID 无效或未注册会发 WARN 但不会中断加载</li>
     *   <li>不要列入有价值的投掷物（trident / ender_pearl 等）</li>
     * </ul>
     */
    public List<String> extraEntityTypes = List.of(
            "minecraft:arrow",
            "minecraft:spectral_arrow",
            "minecraft:experience_orb"
    );

    /**
     * 打开垃圾箱命令的最低权限等级（参考 sweepermaid PERMISSION_LEVEL_DUSTBIN）。
     * <p>
     * 0 = 任何玩家都可以开垃圾箱（推荐：玩家要能取回误清物品）<br>
     * 2 = 仅 OP 可开<br>
     * 4 = 仅 server owner 可开
     * <p>
     * 影响命令：{@code /dropsweeper dustbin <index>}
     */
    public int permissionLevelDustbin = 0;

    /**
     * 手动清理命令的最低权限等级（参考 sweepermaid PERMISSION_LEVEL_CLEAN）。
     * <p>
     * 0 = 任何玩家都可以手动触发清扫<br>
     * 2 = 仅 OP 可触发（推荐：避免普通玩家误操作触发大清扫）
     * <p>
     * 影响命令：{@code /dropsweeper items [all | world <id>]}
     */
    public int permissionLevelClean = 2;

    /**
     * 预编译过滤器：启动时从上面两个 List 构造。
     * <p>
     * 用 {@code transient} 让 Gson 不持久化——它是从原始 List 派生的，
     * 每次从磁盘加载后通过 {@link #rebuildDerived()} 重建。
     */
    private transient ItemFilter whitelistFilter;
    private transient ItemFilter blacklistFilter;
    private transient Set<EntityType<?>> extraTypeFilter = Set.of();

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("dropsweeper.json");

    private static volatile DropsweeperConfig INSTANCE;

    /** 获取当前配置（首次访问时从磁盘加载或生成默认）。 */
    public static DropsweeperConfig get() {
        DropsweeperConfig local = INSTANCE;
        if (local == null) {
            synchronized (DropsweeperConfig.class) {
                local = INSTANCE;
                if (local == null) {
                    local = loadOrCreate();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    /**
     * 强制重新从磁盘加载（用于 {@code /dropsweeper config reload}）。
     * <p>
     * 重载后还会同步 {@link Dustbin} 的 bin 数量（如果 {@code dustbinCount} 变了）：
     * <ul>
     *   <li>新 {@code dustbinCount} &gt; 当前 → 补空 bin（保留数据）</li>
     *   <li>新 {@code dustbinCount} &lt; 当前 → 调 {@code Dustbin.resize()} <b>直接截断</b>多余 bin（可能丢失物品）</li>
     * </ul>
     *
     * @return Dustbin resize 结果（用于调用方反馈玩家"丢失了几个非空 bin"）
     */
    public static synchronized Dustbin.ResizeResult reload() {
        int oldDustbinCount = Dustbin.getInstance().binCount();
        INSTANCE = loadOrCreate();
        int newDustbinCount = INSTANCE.dustbinCount;
        Dustbin.ResizeResult resizeResult = Dustbin.ResizeResult.NOOP;
        if (newDustbinCount != oldDustbinCount) {
            resizeResult = Dustbin.getInstance().resize(newDustbinCount);
        }
        Dropsweeper.LOGGER.info("配置已重新加载: {}（垃圾箱 {} → {}，丢失非空 {}）",
                CONFIG_PATH, oldDustbinCount, newDustbinCount, resizeResult.droppedNonEmpty());
        return resizeResult;
    }

    /**
     * 把当前配置写回磁盘。
     * <p>
     * 玩家通过命令修改白/黑名单后调用，原子覆盖原文件。
     * 失败时回滚到内存中的当前实例（不抛异常）。
     */
    public static synchronized void save() {
        DropsweeperConfig cfg = get();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = GSON.toJson(cfg);
            Files.writeString(CONFIG_PATH, json);
            Dropsweeper.LOGGER.info("配置已保存: {}", CONFIG_PATH);
        } catch (IOException e) {
            Dropsweeper.LOGGER.error("写入配置 {} 失败", CONFIG_PATH, e);
        }
    }

    /**
     * 从原始 List 重建预编译过滤器。
     * <p>
     * 必须在 {@link #loadOrCreate()} 之后调用一次（Gson 反序列化不会构造 transient 字段）。
     * 也可在玩家通过命令修改名单后调用。
     */
    public void rebuildDerived() {
        this.whitelistFilter = new ItemFilter(itemWhitelist, matchWildcard);
        this.blacklistFilter = new ItemFilter(itemBlacklist, matchWildcard);
        this.extraTypeFilter = buildExtraTypeFilter(extraEntityTypes);
    }

    /**
     * 解析 {@code extraEntityTypes} 中的所有 EntityType 引用。
     * <p>
     * 任何 ID 无效或未注册时发 WARN 但不抛异常。
     */
    private static Set<EntityType<?>> buildExtraTypeFilter(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        Set<EntityType<?>> set = new HashSet<>();
        for (String id : ids) {
            Identifier loc = Identifier.tryParse(id);
            if (loc == null) {
                Dropsweeper.LOGGER.warn("extraEntityTypes 含无效 ID: {}", id);
                continue;
            }
            var opt = BuiltInRegistries.ENTITY_TYPE.getOptional(loc);
            if (opt.isPresent()) {
                set.add(opt.get());
            } else {
                Dropsweeper.LOGGER.warn("extraEntityTypes 含未注册的 entity type: {}", id);
            }
        }
        return set;
    }

    /** 获取额外实体清理过滤器。 */
    public Set<EntityType<?>> extraTypeFilter() {
        if (extraTypeFilter == null) {
            extraTypeFilter = buildExtraTypeFilter(extraEntityTypes);
        }
        return extraTypeFilter;
    }

    /** 获取白名单过滤器（懒构造）。 */
    public ItemFilter whitelistFilter() {
        ItemFilter f = whitelistFilter;
        if (f == null) {
            f = new ItemFilter(itemWhitelist, matchWildcard);
            whitelistFilter = f;
        }
        return f;
    }

    /** 获取黑名单过滤器（懒构造）。 */
    public ItemFilter blacklistFilter() {
        ItemFilter f = blacklistFilter;
        if (f == null) {
            f = new ItemFilter(itemBlacklist, matchWildcard);
            blacklistFilter = f;
        }
        return f;
    }

    private static DropsweeperConfig loadOrCreate() {
        if (!Files.exists(CONFIG_PATH)) {
            DropsweeperConfig defaults = new DropsweeperConfig();
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
                Dropsweeper.LOGGER.info("已生成默认配置: {}", CONFIG_PATH);
            } catch (IOException e) {
                Dropsweeper.LOGGER.error("写入默认配置失败: {}", CONFIG_PATH, e);
            }
            defaults.rebuildDerived();
            return defaults;
        }
        try {
            String content = Files.readString(CONFIG_PATH);
            DropsweeperConfig cfg = GSON.fromJson(content, DropsweeperConfig.class);
            if (cfg == null) {
                Dropsweeper.LOGGER.warn("配置 {} 解析为空，回退默认值", CONFIG_PATH);
                DropsweeperConfig def = new DropsweeperConfig();
                def.rebuildDerived();
                return def;
            }
            // 给可能缺失的字段补默认值（玩家可能只填了部分字段）
            applyMissingDefaults(cfg);
            cfg.rebuildDerived();
            return cfg;
        } catch (Exception e) {
            Dropsweeper.LOGGER.error("读取配置 {} 失败，回退默认值", CONFIG_PATH, e);
            DropsweeperConfig def = new DropsweeperConfig();
            def.rebuildDerived();
            return def;
        }
    }

    private static void applyMissingDefaults(DropsweeperConfig cfg) {
        DropsweeperConfig def = new DropsweeperConfig();
        if (cfg.messageBeforeSweepLong == null) cfg.messageBeforeSweepLong = def.messageBeforeSweepLong;
        if (cfg.messageBeforeSweepShort == null) cfg.messageBeforeSweepShort = def.messageBeforeSweepShort;
        if (cfg.messageAfterSweep == null) cfg.messageAfterSweep = def.messageAfterSweep;
        if (cfg.announcePointsLong == null) cfg.announcePointsLong = def.announcePointsLong;
        if (cfg.announceShortThreshold <= 0) cfg.announceShortThreshold = def.announceShortThreshold;
        if (cfg.itemWhitelist == null) cfg.itemWhitelist = def.itemWhitelist;
        if (cfg.itemBlacklist == null) cfg.itemBlacklist = def.itemBlacklist;
        if (cfg.dustbinCount <= 0) cfg.dustbinCount = def.dustbinCount;
        if (cfg.dustbinName == null) cfg.dustbinName = def.dustbinName;
        if (cfg.announcementChannel == null) cfg.announcementChannel = def.announcementChannel;
        if (cfg.itemOverloadThreshold < 0) cfg.itemOverloadThreshold = def.itemOverloadThreshold;
        if (cfg.extraEntityTypes == null) cfg.extraEntityTypes = new ArrayList<>();
        if (cfg.permissionLevelDustbin < 0 || cfg.permissionLevelDustbin > 4)
            cfg.permissionLevelDustbin = def.permissionLevelDustbin;
        if (cfg.permissionLevelClean < 0 || cfg.permissionLevelClean > 4)
            cfg.permissionLevelClean = def.permissionLevelClean;
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }
}
