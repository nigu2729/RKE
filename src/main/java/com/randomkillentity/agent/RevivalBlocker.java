package com.randomkillentity.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汎用復活ブロッカー (専用対策禁止・汎用ヒューリスティック)。
 *
 * <p>UUID が変わって復活する敵への対策として、当エージェント側で
 * 構造から除去・殺害した直後の短時間ウィンドウに限り、
 * {@code ServerLevel.addFreshEntity} / {@code Level.addFreshEntity} による
 * 同クラスの再登録を条件付きでブロックする。</p>
 *
 * <p>初回召喚はブロックしない。判定はクラス・次元・粗い座標・時間のみで行い、
 * Mod固有名・完全なフィールド名は一切参照しない。</p>
 */
public final class RevivalBlocker {
    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill RevivalBlocker");

    /** ブロックする時間ウィンドウ (ms) */
    private static final long REVIVAL_WINDOW_MS = 3500L; // 3.5秒 (UUID変化・新規スポーン型復活に余裕)

    /** 座標粗視化 (チャンク単位程度) で誤爆を抑える */
    private static final int COARSE_SHIFT = 4; // 16ブロック単位

    private static final Map<Key, Long> killedAt = new ConcurrentHashMap<>(256);

    private RevivalBlocker() {
    }

    /**
     * 構造除去・殺害直後に呼び出す (汎用)。
     * purgeFromWorld(structural=true) 実行後にこのクラスを登録する。
     */
    public static void markKilled(Object entity, Object level) {
        if (entity == null) return;
        Class<?> cls = entity.getClass();
        long now = System.currentTimeMillis();
        Key key = makeKey(cls, level, entity);
        killedAt.put(key, now);
        for (DimKey dk : new DimKey[]{DimKey.ANY, DimKey.LEVEL, DimKey.SERVER, DimKey.UNKNOWN, DimKey.CLIENT}) {
            killedAt.put(new Key(cls, dk, 0, 0), now);
        }
        cleanup(now);
    }

    /**
     * addFreshEntity の冒頭で呼び出す (汎用)。
     * ブロックすべき復活なら true を返す。
     */
    public static boolean shouldBlockRevival(Object entity, Object level) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();
        long now = System.currentTimeMillis();
        Key key = makeKey(cls, level, entity);
        Long t = killedAt.get(key);
        if (t != null && now - t < REVIVAL_WINDOW_MS) {
            killedAt.remove(key);
            LOGGER.debug("Blocked revival (coarse): {}", cls.getName());
            return true;
        }
        for (DimKey dk : new DimKey[]{DimKey.ANY, DimKey.LEVEL, DimKey.SERVER, DimKey.UNKNOWN, DimKey.CLIENT}) {
            Key k = new Key(cls, dk, 0, 0);
            Long t2 = killedAt.get(k);
            if (t2 != null && now - t2 < REVIVAL_WINDOW_MS) {
                // keep short-lived? optional
                LOGGER.debug("Blocked revival (class-wide {}): {}", dk, cls.getName());
                return true;
            }
        }
        cleanup(now);
        return false;
    }

    private static Key makeKey(Class<?> cls, Object level, Object entity) {
        DimKey dim = getDimensionKey(level);
        long cx = 0;
        long cz = 0;
        try {
            double x = getEntityX(entity);
            double z = getEntityZ(entity);
            cx = ((long) Math.floor(x)) >> COARSE_SHIFT;
            cz = ((long) Math.floor(z)) >> COARSE_SHIFT;
        } catch (Throwable ignored) {
        }
        return new Key(cls, dim, cx, cz);
    }

    private static DimKey getDimensionKey(Object level) {
        if (level == null) return DimKey.UNKNOWN;
        try {
            Class<?> c = level.getClass();
            String cn = c.getName();
            if (cn.contains("ServerLevel")) return DimKey.SERVER;
            if (cn.contains("ClientLevel")) return DimKey.CLIENT;
            if (cn.contains("Level")) return DimKey.LEVEL;
        } catch (Throwable ignored) {
        }
        return DimKey.UNKNOWN;
    }

    private static double getEntityX(Object entity) throws Exception {
        try {
            Object bp = entity.getClass().getMethod("blockPosition").invoke(entity);
            return (double) bp.getClass().getMethod("getX").invoke(bp);
        } catch (Exception ignored) {
        }
        try {
            return (double) entity.getClass().getMethod("m_20185_").invoke(entity);
        } catch (Exception ignored) {
        }
        try {
            return (double) entity.getClass().getMethod("getX").invoke(entity);
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private static double getEntityZ(Object entity) throws Exception {
        try {
            Object bp = entity.getClass().getMethod("blockPosition").invoke(entity);
            return (double) bp.getClass().getMethod("getZ").invoke(bp);
        } catch (Exception ignored) {
        }
        try {
            return (double) entity.getClass().getMethod("m_20189_").invoke(entity);
        } catch (Exception ignored) {
        }
        try {
            return (double) entity.getClass().getMethod("getZ").invoke(entity);
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private static void cleanup(long now) {
        if (killedAt.size() > 512) {
            List<Key> stale = new ArrayList<>(32);
            for (Map.Entry<Key, Long> e : killedAt.entrySet()) {
                if (now - e.getValue() > REVIVAL_WINDOW_MS * 2) {
                    stale.add(e.getKey());
                }
            }
            for (Key k : stale) {
                killedAt.remove(k);
            }
        }
    }

    private enum DimKey {
        ANY, SERVER, CLIENT, LEVEL, UNKNOWN
    }

    private static final class Key {
        private final Class<?> cls;
        private final DimKey dim;
        private final long cx;
        private final long cz;
        private final int hc;

        Key(Class<?> cls, DimKey dim, long cx, long cz) {
            this.cls = cls;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.hc = 31 * cls.hashCode() + dim.hashCode() * 131542391 + (int) (cx * 131542391L ^ (cz * 1000000009L));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return cls == k.cls && dim == k.dim && cx == k.cx && cz == k.cz;
        }

        @Override
        public int hashCode() {
            return hc;
        }
    }
}