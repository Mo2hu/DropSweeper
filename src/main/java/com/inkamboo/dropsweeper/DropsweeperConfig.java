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
 * DropSweeper 配置文件。位置 {@code config/dropsweeper.json}，首次启动时生成默认值。
 */
public class DropsweeperConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    public int sweepIntervalSeconds = 600;                 // 10 分钟
    public int[] announcePointsLong = {120, 60, 30};
    public int announceShortThreshold = 3;
    public String messageBeforeSweepLong = "【DropSweeper】: 嘿嘿，$1 秒后扫地姬要开始打扫啦~ 大家快把想要的东西捡起来哦！ฅ^•ﻌ•^ฅ";
    public String messageBeforeSweepShort = "【DropSweeper】: 主人注意啦~ 还剩 $1 秒！扫地姬要动手啦，别怪我不提醒哦！(๑•̀ㅂ•́)و✧";
    public String messageAfterSweep = "【DropSweeper】: 打扫完成~ 这次清理了 $1 个掉落物！屋子干干净净啦~(ˊᗜˋ)♪";

    public List<String> itemWhitelist = new ArrayList<>(List.of(
            "minecraft:nether_star",
            "minecraft:heavy_core"
    ));
    public List<String> itemBlacklist = new ArrayList<>(List.of(
            "minecraft:cobblestone",
            "minecraft:sand",
            "minecraft:gravel"
    ));
    public boolean matchWildcard = true;

    public int dustbinCount = 1;
    public String dustbinName = "垃圾桶 ";

    public AnnouncementChannel announcementChannel = new AnnouncementChannel();

    public static class AnnouncementChannel {
        public static final String ACTIONBAR = "actionbar";
        public static final String CHAT = "chat";

        // 字段名 long 是 Java 关键字，所以用 longCountdown 映射
        public String longCountdown = ACTIONBAR;
        public String shortCountdown = CHAT;
        public String complete = CHAT;

        public boolean isLongActionBar() { return ACTIONBAR.equalsIgnoreCase(longCountdown); }
        public boolean isShortActionBar() { return ACTIONBAR.equalsIgnoreCase(shortCountdown); }
        public boolean isCompleteActionBar() { return ACTIONBAR.equalsIgnoreCase(complete); }
    }

    public int itemOverloadThreshold = 640;     // 0 = 禁用

    public List<String> extraEntityTypes = List.of(
            "minecraft:arrow",
            "minecraft:spectral_arrow",
            "minecraft:experience_orb"
    );

    public int permissionLevelDustbin = 0;       // 0 = 任何玩家
    public int permissionLevelClean = 2;         // 2 = OP

    // transient：派生自 List，Gson 不持久化，加载后通过 rebuildDerived() 重建
    private transient ItemFilter whitelistFilter;
    private transient ItemFilter blacklistFilter;
    private transient Set<EntityType<?>> extraTypeFilter = Set.of();

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("dropsweeper.json");

    private static volatile DropsweeperConfig INSTANCE;

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
     * 强制重新从磁盘加载。同步 {@link Dustbin} bin 数量（减少时直接截断，可能丢失物品）。
     *
     * @return Dustbin resize 结果
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
     * 把当前配置写回磁盘。失败时回滚到内存实例（不抛异常）。
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
     * 重建预编译过滤器。必须在 {@link #loadOrCreate()} 之后调用一次（Gson 不构造 transient 字段）。
     */
    public void rebuildDerived() {
        this.whitelistFilter = new ItemFilter(itemWhitelist, matchWildcard);
        this.blacklistFilter = new ItemFilter(itemBlacklist, matchWildcard);
        this.extraTypeFilter = buildExtraTypeFilter(extraEntityTypes);
    }

    /**
     * 解析 extraEntityTypes 中的 EntityType 引用。无效 ID 发 WARN 但不抛异常。
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

    public Set<EntityType<?>> extraTypeFilter() {
        if (extraTypeFilter == null) {
            extraTypeFilter = buildExtraTypeFilter(extraEntityTypes);
        }
        return extraTypeFilter;
    }

    public ItemFilter whitelistFilter() {
        ItemFilter f = whitelistFilter;
        if (f == null) {
            f = new ItemFilter(itemWhitelist, matchWildcard);
            whitelistFilter = f;
        }
        return f;
    }

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
            // 玩家可能只填了部分字段——补默认值
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
