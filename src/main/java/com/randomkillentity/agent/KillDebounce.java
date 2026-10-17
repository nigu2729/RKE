package com.randomkillentity.agent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汎用デバウンス: 同一エンティティへの再度キルを短時間抑制 (プレイヤーの死のループ防止用)。
 * 完全に汎用・Mod固有参照なし。
 */
public final class KillDebounce {
    private static final long DEBOUNCE_MS = 500L; // 0.5秒
    private static final long DEBOUNCE_MS_PLAYER = 2000L; // プレイヤーは長めに
    private static final Map<Key, Long> lastKill = new ConcurrentHashMap<>(256);

    private KillDebounce() {}

    private static boolean isPlayerLike(Object entity) {
        if (entity == null) return false;
        String name = entity.getClass().getName();
        if (name.endsWith("Player") || name.contains("ServerPlayer") || name.contains("ClientPlayer")) {
            return true;
        }
        try {
            entity.getClass().getMethod("isSpectator");
            entity.getClass().getMethod("getGameProfile");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean shouldThrottle(Object entity) {
        if (entity == null) return false;
        long now = System.currentTimeMillis();
        Key k = key(entity);
        Long t = lastKill.get(k);
        long threshold = isPlayerLike(entity) ? DEBOUNCE_MS_PLAYER : DEBOUNCE_MS;
        if (t != null && now - t < threshold) {
            return true;
        }
        lastKill.put(k, now);
        cleanup(now);
        return false;
    }

    private static Key key(Object entity) {
        Class<?> cls = entity.getClass();
        // Try to get persistent identity (UUID) for players/entities
        java.util.UUID uuid = null;
        try {
            // Common method names
            try {
                Method m = cls.getMethod("getUUID");
                uuid = (java.util.UUID) m.invoke(entity);
            } catch (Throwable ignored1) {
                try {
                    Method m = cls.getMethod("m_142081_", Class.class); // MC dev name sometimes
                } catch (Throwable ignored2) {}
                // fallback: no
            }
        } catch (Throwable ignored) {}
        if (uuid != null) {
            return new Key(cls, uuid);
        }
        int id = System.identityHashCode(entity);
        return new Key(cls, id);
    }

    private static void cleanup(long now) {
        if (lastKill.size() > 512) {
            lastKill.entrySet().removeIf(e -> now - e.getValue() > DEBOUNCE_MS * 4);
        }
    }

    private static final class Key {
        private final Class<?> cls;
        private final Object idObj;
        private final int hc;

        Key(Class<?> cls, Object idObj) {
            this.cls = cls;
            this.idObj = idObj;
            this.hc = cls.hashCode() * 131542391 + (idObj == null ? 0 : idObj.hashCode());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return cls == k.cls && java.util.Objects.equals(idObj, k.idObj);
        }

        @Override
        public int hashCode() {
            return hc;
        }
    }
}
