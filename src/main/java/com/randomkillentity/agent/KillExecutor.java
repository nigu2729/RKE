package com.randomkillentity.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 汎用即時キル実行ヘルパー (/rke 用)。
 * <p>
 * 専用対策ではなく、既存の汎用キルレイヤー
 * (WorldStructureDestroyer / StateDestroyer / RevivalBlocker) を再利用して
 * 対象エンティティ1体のみを即時に殺害する。
 * </p>
 */
public final class KillExecutor {
    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Executor");

    private KillExecutor() {
    }

    /**
     * 対象 LivingEntity を汎用的に即時殺害する。
     * 自己召喚・復活ブロックには影響しない（RevivalBlocker.markKilled は呼ぶが
     * ウィンドウは「死亡直後」にのみ有効）。
     */
    public static void killNow(Object livingEntity) {
        if (livingEntity == null) return;
        try {
            if (KillDebounce.shouldThrottle(livingEntity)) {
                return;
            }
            // For players, be even more conservative to avoid client desync
            boolean isPlayer = livingEntity.getClass().getName().endsWith("Player");
            // 構造から直接除去
            WorldStructureDestroyer.purgeFromWorld(livingEntity, true);

            // 防御状態の破壊
            StateDestroyer.destroy(livingEntity);

            // 死亡直後ウィンドウを登録
            try {
                Object level = getLevel(livingEntity);
                RevivalBlocker.markKilled(livingEntity, level);
            } catch (Throwable ignored) {
            }

            try {
                // Ensure health is forced to 0 for LivingEntity (generic)
                forceHealthZero(livingEntity);
            } catch (Throwable ignored) {
            }

            try {
                // Try discard if present
                tryDiscard(livingEntity);
            } catch (Throwable ignored) {
            }

            try {
                forceHealthZero(livingEntity);
            } catch (Throwable ignored) {
            }

            try {
                tryDiscard(livingEntity);
            } catch (Throwable ignored) {
            }

            try {
                FixDeadPlayer.tryFix(livingEntity);
            } catch (Throwable ignored) {
            }

            LOGGER.debug("killNow executed for {}", livingEntity.getClass().getName());
        } catch (Throwable t) {
            LOGGER.warn("killNow failed: {}", t);
        }
    }

    private static void forceHealthZero(Object entity) {
        try {
            ClassLoader cl = entity.getClass().getClassLoader();
            Class<?> le = Class.forName("net.minecraft.world.entity.LivingEntity", false, cl);
            if (le.isInstance(entity)) {
                Method m = findMethod(le, new String[]{"setHealth", "m_21154_"}, 1, float.class);
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(entity, 0.0f);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tryDiscard(Object entity) {
        try {
            Method m = findMethod(entity.getClass(), new String[]{"discard", "m_146870_"}, 0);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(entity);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method findMethod(Class<?> clazz, String[] names, int paramCount, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    if (paramTypes.length > 0) {
                        Method m = c.getDeclaredMethod(name, paramTypes);
                        return m;
                    } else {
                        for (Method m : c.getDeclaredMethods()) {
                            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                                return m;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object getLevel(Object entity) {
        try {
            // Entity.level() (dev)
            Method m = entity.getClass().getMethod("level");
            return m.invoke(entity);
        } catch (Throwable ignored) {
        }
        try {
            // SRG m_9236_()
            Method m = entity.getClass().getMethod("m_9236_");
            return m.invoke(entity);
        } catch (Throwable ignored) {
        }
        try {
            Field f = entity.getClass().getDeclaredField("f_19853_");
            f.setAccessible(true);
            return f.get(entity);
        } catch (Throwable ignored) {
        }
        try {
            Field f = entity.getClass().getField("f_19853_");
            f.setAccessible(true);
            return f.get(entity);
        } catch (Throwable ignored) {
        }
        return null;
    }
}