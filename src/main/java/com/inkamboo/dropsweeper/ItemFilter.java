package com.inkamboo.dropsweeper;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 物品名单过滤器（白名单 / 黑名单共用）。
 * <p>
 * <b>性能设计</b>：启动时把原始字符串列表预编译为两个 O(1) 查询结构：
 * <ul>
 *   <li>{@code exact}：完全匹配的 {@link Identifier} 集合
 *       ——hash 查找，无字符串分配</li>
 *   <li>{@code wildcardNamespaces}：通配符的 namespace 集合（{@code modid:*}）
 *       ——按命名空间匹配</li>
 * </ul>
 * 运行期 {@link #matches(Identifier)} 复杂度 O(1)，
 * JIT 会内联此方法，循环内几乎零开销。
 * <p>
 * <b>空名单短路</b>：当 exact 与 wildcard 都为空时直接 return false，
 * 连 hash 都不查（默认配置走此路径）。
 */
public final class ItemFilter {
    /** 共享的空实例。 */
    private static final ItemFilter EMPTY = new ItemFilter(Collections.emptySet(), Collections.emptySet());

    private final Set<Identifier> exact;
    private final Set<String> wildcardNamespaces;

    /**
     * 从原始字符串列表构建过滤器。
     * 非法格式（空、缺冒号、无效字符）静默忽略。
     *
     * @param rawEntries 原始字符串列表（可为 null）
     * @param enableWildcard 是否启用 {@code modid:*} 通配符
     */
    public ItemFilter(List<String> rawEntries, boolean enableWildcard) {
        Set<Identifier> e = new HashSet<>();
        Set<String> w = new HashSet<>();
        if (rawEntries != null) {
            for (String s : rawEntries) {
                if (s == null) continue;
                String t = s.trim();
                if (t.isEmpty()) continue;
                if (enableWildcard && t.endsWith(":*")) {
                    String ns = t.substring(0, t.length() - 2);
                    if (!ns.isEmpty() && Identifier.isValidNamespace(ns)) {
                        w.add(ns);
                    }
                } else if (t.contains(":")) {
                    Identifier id = Identifier.tryParse(t);
                    if (id != null) e.add(id);
                }
                // 非法格式：忽略
            }
        }
        this.exact = e;
        this.wildcardNamespaces = w;
    }

    private ItemFilter(Set<Identifier> exact, Set<String> wildcard) {
        this.exact = exact;
        this.wildcardNamespaces = wildcard;
    }

    public static ItemFilter empty() {
        return EMPTY;
    }

    /**
     * 检查物品 ID 是否匹配。O(1)。
     */
    public boolean matches(Identifier itemId) {
        if (exact.isEmpty() && wildcardNamespaces.isEmpty()) return false;
        if (exact.contains(itemId)) return true;
        return wildcardNamespaces.contains(itemId.getNamespace());
    }

    public boolean isEmpty() {
        return exact.isEmpty() && wildcardNamespaces.isEmpty();
    }

    public int size() {
        return exact.size() + wildcardNamespaces.size();
    }
}
