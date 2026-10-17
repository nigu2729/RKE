package com.randomkillentity.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 汎用: EntityInLevelCallback の no-op 実装。null にせずダミーをセットすることで NPE を回避。
 */
public final class NoOpEntityInLevelCallback {
    private NoOpEntityInLevelCallback() {}

    private static final Object NO_OP;
    static {
        Object inst = null;
        try {
            Class<?> iface = Class.forName("net.minecraft.world.level.entity.EntityInLevelCallback", false, NoOpEntityInLevelCallback.class.getClassLoader());
            inst = Proxy.newProxyInstance(NoOpEntityInLevelCallback.class.getClassLoader(),
                    new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            return null;
                        }
                    });
        } catch (Throwable ignored) {
            inst = null;
        }
        NO_OP = inst;
    }

    public static Object get() {
        return NO_OP;
    }
}
