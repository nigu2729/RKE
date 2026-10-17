package com.randomkillentity.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * クライアント側エンティティ消去 (設計v2 項目9)。
 *
 * <p>サーバー側で kill したエンティティをクライアント表示からも除去し、
 * 「死んだはずのモブが画面に残る」表示不一致を防ぐ。vanilla の死亡画面
 * (プレイヤー自身の死亡) は維持するため、プレイヤーエンティティは対象外。</p>
 *
 * <p>クライアント側エンティティ除去の代表的パターン:
 * setRemoved(RemovalReason.KILLED) (m_142467_) → それでも残っていれば
 * removalReason フィールド (f_146801_) 直接設定 → kill() (m_6074_)。</p>
 *
 * <p>統合サーバー (シングルプレイ) ではクライアントと同一 JVM なので機能する。
 * 専用サーバーでは Minecraft クライアントクラスが存在せず安全に no-op になる。</p>
 */
public final class ClientEraser {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill ClientEraser");

    /** 実行間引き間隔 (クライアント消去は補助手段なので過剰に呼ばない) */
    private static final long THROTTLE_MS = 100L;

    private static volatile long lastRunTime;

    private ClientEraser() {
    }

    /**
     * 対象 UUID のエンティティをクライアントレベルから除去する。
     * KillDaemon ループから呼ばれる。100ms 間隔で間引く。
     */
    public static void run() {
        long now = System.currentTimeMillis();
        if (now - lastRunTime < THROTTLE_MS) return;
        lastRunTime = now;

        try {
            Object minecraft = getMinecraft();
            if (minecraft == null) return;
            Object clientLevel = getClientLevel(minecraft);
            if (clientLevel == null) return;
            Object entityGetter = getEntityGetter(clientLevel);
            if (entityGetter == null) return;

            for (UUID uuid : KillTargetRegistry.targets()) {
                Object entity = getEntityByUuid(entityGetter, uuid);
                if (entity == null) continue;
                removeEntityClientSide(entity, uuid);
            }
        } catch (Throwable t) {
            // クライアント消去は補助手段。失敗してもサーバー側 KillDaemon が本命。
            LOGGER.debug("Client erasure skipped: {}", t);
        }
    }

    // ── Minecraft クライアント取得 ──

    private static Object getMinecraft() {
        ClassLoader cl = findClientClassLoader();
        if (cl == null) return null;
        try {
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft", false, cl);
            Method m;
            try {
                m = mc.getMethod("getInstance");
            } catch (NoSuchMethodException e) {
                m = mc.getMethod("m_91087_");
            }
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getClientLevel(Object minecraft) {
        try {
            Field f = null;
            for (String name : new String[]{"level", "f_91073_"}) {
                try {
                    f = minecraft.getClass().getField(name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) return null;
            return f.get(minecraft);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getEntityGetter(Object clientLevel) {
        try {
            Method m;
            try {
                m = clientLevel.getClass().getMethod("getEntities");
            } catch (NoSuchMethodException e) {
                m = clientLevel.getClass().getMethod("m_142646_");
            }
            return m.invoke(clientLevel);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getEntityByUuid(Object entityGetter, UUID uuid) {
        try {
            Method m;
            try {
                m = entityGetter.getClass().getMethod("m_142694_", UUID.class);
            } catch (NoSuchMethodException e) {
                m = entityGetter.getClass().getMethod("get", UUID.class);
            }
            return m.invoke(entityGetter, uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── クライアント側除去 (エンティティ除去パケット処理パターン) ──

    private static void removeEntityClientSide(Object entity, UUID uuid) {
        try {
            // vanilla 死亡画面を維持するためプレイヤーは対象外
            if (isPlayer(entity)) return;

            Object removalReason = getRemovalReasonKilled(entity);
            if (removalReason != null) {
                // 1. setRemoved(RemovalReason.KILLED) = m_142467_
                try {
                    Method m;
                    try {
                        m = entity.getClass().getMethod("m_142467_", removalReason.getClass());
                    } catch (NoSuchMethodException e) {
                        m = entity.getClass().getMethod("setRemoved", removalReason.getClass());
                    }
                    m.invoke(entity, removalReason);
                } catch (Throwable ignored) {
                }
            }

            // 2. それでも isRemoved() でなければ removalReason フィールドを直接設定
            if (!isRemoved(entity)) {
                Field f = null;
                for (String name : new String[]{"removalReason", "f_146801_"}) {
                    try {
                        f = entity.getClass().getDeclaredField(name);
                        f.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (f != null && removalReason != null) {
                    try {
                        f.set(entity, removalReason);
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 3. kill() も呼んでおく (m_6074_)
            try {
                Method m;
                try {
                    m = entity.getClass().getMethod("kill");
                } catch (NoSuchMethodException e) {
                    m = entity.getClass().getMethod("m_6074_");
                }
                m.invoke(entity);
            } catch (Throwable ignored) {
            }

            LOGGER.debug("Erased client entity {}", uuid);
        } catch (Throwable t) {
            LOGGER.debug("Client remove failed for {}: {}", uuid, t);
        }
    }

    private static boolean isRemoved(Object entity) {
        try {
            Method m;
            try {
                m = entity.getClass().getMethod("isRemoved");
            } catch (NoSuchMethodException e) {
                m = entity.getClass().getMethod("m_213877_");
            }
            return Boolean.TRUE.equals(m.invoke(entity));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getRemovalReasonKilled(Object entity) {
        try {
            ClassLoader cl = entity.getClass().getClassLoader();
            Class<?> removalReasonClass = Class.forName("net.minecraft.world.entity.Entity$RemovalReason", true, cl);
            for (Object constant : removalReasonClass.getEnumConstants()) {
                if (constant != null && "KILLED".equals(((Enum<?>) constant).name())) {
                    return constant;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isPlayer(Object entity) {
        String name = entity.getClass().getName();
        return name.contains("Player") || name.endsWith("LocalPlayer") || name.endsWith("ServerPlayer");
    }

    // ── クラスローダー探索 ──

    private static ClassLoader findClientClassLoader() {
        // Render thread (クライアントスレッド) の CCL が最有力
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name != null && name.contains("Render")) {
                ClassLoader cl = t.getContextClassLoader();
                if (cl != null) return cl;
            }
        }
        // 統合サーバーではサーバースレッドの CCL からも見える可能性がある
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name != null && "Server thread".equals(name)) {
                ClassLoader cl = t.getContextClassLoader();
                if (cl != null) return cl;
            }
        }
        return ClientEraser.class.getClassLoader();
    }
}
