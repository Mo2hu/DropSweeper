package com.inkamboo.dropsweeper;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 物品名单过滤器（白名单/黑名单共用）。运行期 O(1) 匹配。空名单短路避免 hash 查找。
 */
public final class ItemFilter {
    private static final ItemFilter EMPTY = new ItemFilter(Collections.emptySet(), Collections.emptySet());

    private final Set<Identifier> exact;
    private final Set<String> wildcardNamespaces;

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
