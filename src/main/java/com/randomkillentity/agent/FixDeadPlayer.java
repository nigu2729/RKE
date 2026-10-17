package com.randomkillentity.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 死亡後のプレイヤー状態を強制的に「リスポーン可能」な状態にする汎用ユーティリティ。
 * 既存のキルパイプラインを補完するための緊急回避策。
 */
public final class FixDeadPlayer {
    private FixDeadPlayer() {}

    public static void tryFix(Object entity) {
        if (entity == null) return;
        String name = entity.getClass().getName();
        if (!name.endsWith("Player") && !name.endsWith("ServerPlayer") && !name.contains("Player")) {
            return;
        }
        try {
            // setDead(false)
            Method m = findMethod(entity.getClass(), new String[]{"setDead", "m_6899_"}, 1, boolean.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(entity, false);
            }
        } catch (Throwable ignored) {}
        try {
            // setRemoved(RemovalReason.DISCARDED?) no, better set to alive? not straightforward
        } catch (Throwable ignored) {}
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
                            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}