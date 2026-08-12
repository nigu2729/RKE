package com.randomkillentity.utils;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Unsafe 経由のフィールド直書きユーティリティ。
 *
 * <p>設計v2「RK真・即死」の SyncedEntityData 非依存化の中核。
 * {@code LivingEntity.health} を DataWatcher を一切通らずに書き換えるため、
 * 「SyncedData置き換え無効」対策 (snackprotector 型の DataWatcher ブロック) を回避する。</p>
 *
 * <p>クラス名とメソッド名は MC 1.20.1 の SRG 名 (f_XXXXX_ / m_XXXXX_) と
 * 通常名の両対応で探索する (開発環境 = 通常名, 本番 = SRG 名)。</p>
 */
public class UnsafeUtils {

    private static final Unsafe UNSAFE;

    static {
        Unsafe u = null;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            u = (Unsafe) field.get(null);
        } catch (Exception e) {
            // 取得失敗時は null のまま (機能停止のみ、クラッシュはしない)
        }
        UNSAFE = u;
    }

    private UnsafeUtils() {
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    public static boolean isAvailable() {
        return UNSAFE != null;
    }

    /**
     * フィールド解決キャッシュ (クラス名 + 候補名列 をキーに解決済み Field を保持)。
     *
     * <p>解析指摘対応: findField はクラス階層 + getDeclaredField を毎回走るため、
     * 5ms デーモンの health / invulnerableTime 直書き (writeFloatField /
     * writeIntField) が毎 Tick 数百回のリフレクション走査になっていた。
     * 解決結果を (クラス × 候補名) 単位でキャッシュする。Field は不変で
     * 再利用可能 (objectFieldOffset はサブクラスインスタンスにも有効)。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String fieldCacheKey(Class<?> clazz, String... names) {
        StringBuilder sb = new StringBuilder(clazz.getName());
        for (String name : names) {
            sb.append('\u0000').append(name);
        }
        return sb.toString();
    }

    /**
     * クラス階層を遡ってフィールドを探す (static 含む)。
     * 複数の候補名 (通常名 + SRG 名) を順に試す。
     */
    public static Field findField(Class<?> clazz, String... names) {
        if (clazz == null || names == null || names.length == 0) return null;
        String key = fieldCacheKey(clazz, names);
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;
        Field f = findFieldUncached(clazz, names);
        if (f != null) {
            FIELD_CACHE.putIfAbsent(key, f);
        }
        return f;
    }

    private static Field findFieldUncached(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    // 次の名前/クラスへ
                }
            }
        }
        return null;
    }

    /**
     * 指定型のフィールドをクラス階層から探す (static 含む)。
     * 名前不明の SRG フィールド対策: 型ヒントで特定する。
     */
    public static Field findFieldByType(Class<?> clazz, Class<?> fieldType, String... preferredNames) {
        Field byName = findField(clazz, preferredNames);
        if (byName != null) return byName;
        if (clazz == null || fieldType == null) return null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : safeGetDeclaredFields(c)) {
                if (fieldType.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * オブジェクトの float フィールドへ直接書き込む (DataWatcher 非依存)。
     *
     * @param owner   対象オブジェクト (Entity 等)
     * @param value   書き込む値
     * @param names   フィールド名候補 (通常名 + SRG 名)
     * @return 成功したら true
     */
    public static boolean writeFloatField(Object owner, float value, String... names) {
        if (owner == null || UNSAFE == null) return false;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType() != float.class) return false;
        try {
            UNSAFE.putFloat(owner, UNSAFE.objectFieldOffset(f), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * オブジェクトの float フィールドを読み取る。
     *
     * @return 失敗時は {@link Float#NaN}
     */
    public static float readFloatField(Object owner, String... names) {
        if (owner == null || UNSAFE == null) return Float.NaN;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType() != float.class) return Float.NaN;
        try {
            return UNSAFE.getFloat(owner, UNSAFE.objectFieldOffset(f));
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * オブジェクトの boolean フィールドを読み取る (dead / removed フラグ判定用)。
     *
     * @return 失敗時は false
     */
    public static boolean readBooleanField(Object owner, String... names) {
        if (owner == null || UNSAFE == null) return false;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType() != boolean.class) return false;
        try {
            return UNSAFE.getBoolean(owner, UNSAFE.objectFieldOffset(f));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * オブジェクトの int フィールドへ直接書き込む (無敵時間リセット等)。
     */
    public static boolean writeIntField(Object owner, int value, String... names) {
        if (owner == null || UNSAFE == null) return false;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType() != int.class) return false;
        try {
            UNSAFE.putInt(owner, UNSAFE.objectFieldOffset(f), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * オブジェクトの boolean フィールドへ直接書き込む。
     */
    public static boolean writeBooleanField(Object owner, boolean value, String... names) {
        if (owner == null || UNSAFE == null) return false;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType() != boolean.class) return false;
        try {
            UNSAFE.putBoolean(owner, UNSAFE.objectFieldOffset(f), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * オブジェクトの参照フィールドへ直接書き込む。
     */
    public static boolean writeObjectField(Object owner, Object value, String... names) {
        if (owner == null || UNSAFE == null) return false;
        Field f = findField(owner.getClass(), names);
        if (f == null || f.getType().isPrimitive()) return false;
        try {
            UNSAFE.putObject(owner, UNSAFE.objectFieldOffset(f), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 静的参照フィールドへ直接書き込む (レジストリ掃除用)。
     */
    public static boolean writeStaticObjectField(Class<?> clazz, Object value, String... names) {
        if (clazz == null || UNSAFE == null) return false;
        Field f = findField(clazz, names);
        if (f == null || !Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) return false;
        try {
            UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 静的フィールドの現在値を読み取る。
     */
    public static Object readStaticObjectField(Class<?> clazz, String... names) {
        if (clazz == null || UNSAFE == null) return null;
        Field f = findField(clazz, names);
        if (f == null || !Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) return null;
        try {
            return UNSAFE.getObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * クラス階層の全フィールド (static + instance) を安全に列挙する。
     */
    public static List<Field> safeGetDeclaredFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        if (clazz == null) return result;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        result.add(f);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /**
     * Java 9以降で VirtualMachine.attach が制限されているのを回避する
     */
    public static void allowAttachSelf() {
        try {
            System.setProperty("jdk.attach.allowAttachSelf", "true");
        } catch (Exception e) {
            // 無視
        }
    }
}
