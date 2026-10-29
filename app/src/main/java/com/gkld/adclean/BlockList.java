package com.gkld.adclean;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 广告域名匹配。规则：host 完全相等，或以 ".<域名>" 结尾。
 * 结果做缓存，避免每个请求都跑一遍字符串比较。
 */
public final class BlockList {

    private static final String[] ALL;

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    static {
        String[] a = Config.HOSTS;
        String[] b = Config.EXTRA_HOSTS;
        ALL = new String[a.length + b.length];
        System.arraycopy(a, 0, ALL, 0, a.length);
        System.arraycopy(b, 0, ALL, a.length, b.length);
    }

    private BlockList() {
    }

    public static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        Boolean cached = CACHE.get(host);
        if (cached != null) {
            return cached;
        }
        String h = host.toLowerCase();
        boolean blocked = false;
        for (String d : ALL) {
            if (d == null || d.isEmpty()) {
                continue;
            }
            if (h.equals(d) || h.endsWith("." + d)) {
                blocked = true;
                break;
            }
        }
        if (CACHE.size() < 4096) {
            CACHE.put(host, blocked);
        }
        return blocked;
    }

    public static boolean shouldBlock(String host, String path) {
        if (isBlockedHost(host)) {
            return true;
        }
        if (Config.BLOCK_PATH_KEYWORDS && path != null) {
            String p = path.toLowerCase();
            for (String k : Config.PATH_KEYWORDS) {
                if (p.contains(k.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}
