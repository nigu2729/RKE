package com.randomkillentity.agent;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 対象エンティティの状態を根こそぎ破壊する (設計v2 項目6: mod状態破壊, 項目10: SynchedEntityData非依存)。
 *
 * <p>KillDaemon のキルチェーンが vanilla の死亡処理を完了させた後、
 * 防御mod が「このエンティティは守られている」と認識するための状態を
 * 全て壊す。lal KillEnforcer の corruptAllHealthFields / corruptSynchedEntityData を
 * Forge非依存のリフレクション実装として移植したもの。</p>
 *
 * <p>MC クラスへの直接参照は持たない (agent は隔離クラスローダー)。
 * クラス名プレフィクス "net.minecraft." で vanilla 基底クラスを検出し、
 * それより下 (mod 独自サブクラス) のフィールドだけを破壊する。
 * vanilla の health 等は KillDaemon が Unsafe 直書きで処理済みのため対象外。</p>
 */
public final class StateDestroyer {

    private static final String[] ENTITY_DATA_ITEMS_NAMES = {"f_135354_", "itemsById"};
    private static final String[] DATA_HEALTH_ID_NAMES = {"f_20961_", "DATA_HEALTH_ID"};
    private static final String[] SET_NAMES = {"m_135381_", "set"};
    private static final String[] DATA_ITEM_VALUE_NAMES = {"f_135365_", "value"};
    private static final String[] GET_ENTITY_DATA_NAMES = {"m_20088_", "getEntityData"};

    /** 永続化データを絶対に置かないシステムディレクトリ (走査スキップ対象) */
    private static final String[] SKIP_DIR_NAMES = {"logs", "crash-reports"};

    /** vanilla クラスへ mixin 注入された防御フィールドのキーワード (設計v5)。
     *  muteki / invincible / protect / immortal / fumetsu / safe 等、防御系の
     *  boolean / int / float フィールドを false / 0 に破壊する汎用ヒューリスティック。
     *  専用対策ではない (クラス名・mod 名・完全なフィールド名は参照しない)。 */
    private static final String[] DEFENSE_KEYWORDS = {
            "alive", "valid", "invul", "protect", "immortal", "muteki",
            "invincible", "fumetsu", "safe"
    };

    /** 自爆系キーワード (設計v8 柱2: 一般名詞ヒューリスティック)。
     *  対象エンティティの boolean フィールド名にこれらが含まれる場合、
     *  キル前に true に反転して isEnding() 等のロックをかける。
     *  日本語「自殺」(jisatsu/jisatu) を含む。専用対策ではない (完全フィールド名非参照)。
     *  注意: "remov"/"dead"/"die"/"kill"/"dying"/"destroy"/"discard" は含めない。
     *  これらを true にすると mIsSetRemoveding 等の除去ガードが作動し setRemoved が封じられるため。 */
    private static final String[] SELF_DESTRUCT_KEYWORDS = {"jisatsu", "jisatu", "suicide", "selfkill"};

    private StateDestroyer() {
    }

    /**
     * 対象エンティティの防御mod 状態を全て破壊する。
     * 数値フィールドの MIN_VALUE 化 → 防御フラグ破壊 → SynchedEntityData 破壊 の順。
     */
    public static void destroy(Object entity) {
        if (entity == null) return;
        corruptNumericFields(entity);
        corruptProtectionFlags(entity);
        // 設計v5 (プレイヤー殺害拡大): vanilla クラスへ mixin 注入された
        // 防御フィールド (modid$immortal 等) も破壊する。
        corruptVanillaDefensiveFields(entity);
        corruptSynchedEntityData(entity);
    }

    /**
     * プレイヤー (vanilla クラス) の防御状態を破壊する (設計v5: プレイヤー殺害拡大)。
     *
     * <p>プレイヤー (ServerPlayer 等) はクラス階層全体が {@code net.minecraft.*} のため、
     * 既存の {@link #destroy} 内の corruptNumericFields / corruptProtectionFlags は
     * vanilla クラスで即 break して何もしない。プレイヤー経路では mixin 注入フィールド
     * の破壊と SynchedEntityData 破壊だけが有効なため、KillDaemon はこの専用
     * エントリポイントを呼ぶ。</p>
     */
    public static void destroyPlayerDefenses(Object entity) {
        if (entity == null) return;
        corruptVanillaDefensiveFields(entity);
        corruptSynchedEntityData(entity);
    }

    /**
     * UUID 名を含む永続化ファイルをワールドディレクトリから削除する (mod状態のディスク永続化対策)。
     * 深さ3まで走査する。
     *
     * <p>誤爆防止: ファイル名に UUID が「独立トークン」として現れた場合のみ削除する。
     * 36桁 (ダッシュ付き) と 32桁 (ダッシュなし) の両形式に対応し、UUID の前後に
     * hex 文字が直接続く場合 (別の長い hex 列の一部) は対象外とする。</p>
     */
    public static void deletePersistenceFiles(Object server, UUID uuid) {
        if (uuid == null) return;
        try {
            File dir = getServerDirectory(server);
            if (dir == null || !dir.isDirectory()) return;
            String uuidDashed = uuid.toString().toLowerCase();
            String uuidNoDash = uuidDashed.replace("-", "");
            List<File> matches = new ArrayList<>();
            collectMatches(dir, uuidDashed, uuidNoDash, matches, 3);
            for (File f : matches) {
                try {
                    if (f.isFile() && !f.delete()) {
                        f.deleteOnExit();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void corruptNumericFields(Object target) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                if (isVanillaClass(clazz)) break;
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        Class<?> type = f.getType();
                        if (type == float.class) {
                            f.setAccessible(true);
                            f.setFloat(target, Float.MIN_VALUE);
                        } else if (type == double.class) {
                            f.setAccessible(true);
                            f.setDouble(target, Double.MIN_VALUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void corruptProtectionFlags(Object target) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                if (isVanillaClass(clazz)) break;
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        Class<?> type = f.getType();
                        String name = f.getName().toLowerCase();
                        if (type == boolean.class) {
                            f.setAccessible(true);
                            if (containsAny(name, "dead", "die", "kill", "dying", "destroy", "discard", "remov")) {
                                f.setBoolean(target, true);
                            } else if (containsAny(name, "alive", "valid", "invul", "protect", "immortal", "muteki", "invincible", "fumetsu", "safe", "novel")) {
                                f.setBoolean(target, false);
                            }
                        } else if (type == int.class && containsAny(name, "protect", "immortal", "muteki", "invincible", "fumetsu", "safe", "novel")) {
                            f.setAccessible(true);
                            f.setInt(target, 0);
                        } else if (Map.class.isAssignableFrom(type)) {
                            f.setAccessible(true);
                            Object val = f.get(target);
                            if (val instanceof Map) {
                                ((Map<?, ?>) val).clear();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * vanilla クラスへ mixin 注入された防御フィールドを破壊する (設計v5)。
     *
     * <p>既存の {@link #corruptProtectionFlags} は {@code isVanillaClass} の break により
     * vanilla 基底クラスを一切走査しないため、防御mod が mixin で vanilla クラス
     * (Player 等) に注入したフィールド ({@code modid$immortal} 等) には届かない。
     * ここではクラス種別を問わず階層全体を走査する代わりに、mixin 注入の慣習である
     * {@code $} を含むフィールド名 + 防御キーワード一致の二重条件で対象を絞る。</p>
     *
     * <p>専用対策ではない: クラス名・mod 名・完全なフィールド名は一切参照せず、
     * 命名規則 (mixin は {@code modid$name} 形式) とキーワードの汎用ヒューリスティック
     * のみに依存する。あらゆる例外は握りつぶし、クラッシュしない。</p>
     */
    private static void corruptVanillaDefensiveFields(Object entity) {
        if (entity == null) return;
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    // mixin 注入フィールドのみ対象 ($ を含む = modid$name 形式)
                    if (!f.getName().contains("$")) continue;
                    String name = f.getName().toLowerCase();
                    try {
                        Class<?> type = f.getType();
                        if (type == boolean.class) {
                            f.setAccessible(true);
                            if (containsAny(name, "dead", "die", "kill", "dying", "destroy", "discard", "remov")) {
                                f.setBoolean(entity, true);
                            } else if (containsAny(name, DEFENSE_KEYWORDS)) {
                                f.setBoolean(entity, false);
                            }
                        } else if (type == int.class && containsAny(name, DEFENSE_KEYWORDS)) {
                            f.setAccessible(true);
                            f.setInt(entity, 0);
                        } else if (type == float.class && containsAny(name, DEFENSE_KEYWORDS)) {
                            f.setAccessible(true);
                            f.setFloat(entity, 0.0f);
                        } else if (type == double.class && containsAny(name, DEFENSE_KEYWORDS)) {
                            f.setAccessible(true);
                            f.setDouble(entity, 0.0d);
                        } else if (Map.class.isAssignableFrom(type)) {
                            f.setAccessible(true);
                            Object val = f.get(entity);
                            if (val instanceof Map) {
                                ((Map<?, ?>) val).clear();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * キル前専用の自爆フラグ反転 (設計v8 柱2)。
     *
     * <p>対象エンティティのクラス階層 (Object まで) を走査し、非 static boolean フィールドのうち
     * 名前 (小文字) が {@link #SELF_DESTRUCT_KEYWORDS} を含むものを true に、
     * {@link #DEFENSE_KEYWORDS} を含むものを false に反転する。
     *
     * <p>「remov"/"dead"/"die"/"kill"/"dying"/"destroy"/"discard" は含めない。
     * これらを true にすると {@code mIsSetRemoveding} (setRemoved 再入ガード) が true になり
     * setRemoved が封じられるため。既存の {@link #corruptProtectionFlags} は事後破壊用として
     * これらを含むが、本メソッドはキル「前」に走るため自爆系のみに限定する。
     *
     * <p>リフレクションのみで MC ガードを呼ばないためフレーム汚染しない
     * (setRemoved 呼び出し前に return 済み → スタック検査に agent フレームが残らない)。
     * 全例外を握りつぶしクラッシュしない。
     *
     * @param entity 対象エンティティ (null 安全)
     */
    public static void flipSelfDestructFlags(Object entity) {
        if (entity == null) return;
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        Class<?> type = f.getType();
                        if (type == boolean.class) {
                            String name = f.getName().toLowerCase();
                            f.setAccessible(true);
                            if (containsAny(name, SELF_DESTRUCT_KEYWORDS)) {
                                f.setBoolean(entity, true);
                            } else if (containsAny(name, DEFENSE_KEYWORDS)) {
                                f.setBoolean(entity, false);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void corruptSynchedEntityData(Object target) {
        try {
            Object entityData = invokeEntityData(target);
            if (entityData == null) return;
            Field itemsField = findField(entityData.getClass(), ENTITY_DATA_ITEMS_NAMES);
            if (itemsField != null) {
                itemsField.setAccessible(true);
                Object items = itemsField.get(entityData);
                corruptItems(items);
            }
            setHealthZero(entityData, target);
        } catch (Throwable ignored) {
        }
    }

    private static void corruptItems(Object items) {
        if (items == null) return;
        try {
            if (items.getClass().isArray()) {
                for (Object item : (Object[]) items) {
                    corruptDataItem(item);
                }
            } else if (items instanceof Map) {
                for (Object item : ((Map<?, ?>) items).values()) {
                    corruptDataItem(item);
                }
            } else {
                Method values = items.getClass().getMethod("values");
                Object vals = values.invoke(items);
                if (vals instanceof Iterable) {
                    for (Object item : (Iterable<?>) vals) {
                        corruptDataItem(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void corruptDataItem(Object item) {
        if (item == null) return;
        try {
            Field valueField = findField(item.getClass(), DATA_ITEM_VALUE_NAMES);
            if (valueField == null) return;
            valueField.setAccessible(true);
            Class<?> type = valueField.getType();
            if (type == float.class || type == Float.class) {
                valueField.set(item, 0.0f);
            } else if (type == boolean.class || type == Boolean.class) {
                valueField.set(item, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setHealthZero(Object entityData, Object target) {
        try {
            Object dataHealthId = readStaticField(target, DATA_HEALTH_ID_NAMES);
            if (dataHealthId == null) return;
            Method setter = findMethod(entityData.getClass(), SET_NAMES, 2);
            if (setter == null) return;
            setter.invoke(entityData, dataHealthId, 0.0f);
        } catch (Throwable ignored) {
        }
    }

    private static Object invokeEntityData(Object target) {
        try {
            Method m = findMethod(target.getClass(), GET_ENTITY_DATA_NAMES, 0);
            return m == null ? null : m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readStaticField(Object target, String[] names) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (String name : names) {
                    try {
                        Field f = clazz.getDeclaredField(name);
                        if (Modifier.isStatic(f.getModifiers())) {
                            f.setAccessible(true);
                            return f.get(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File getServerDirectory(Object server) {
        if (server != null) {
            try {
                // Forge/NeoForgeの標準APIが使える場合はそちらを優先するのが最も安全
                // 例: return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile();

                Method m = findMethod(server.getClass(), new String[]{"getServerDirectory", "m_129774_"}, 0);
                if (m != null) {
                    Object dir = m.invoke(server);
                    if (dir instanceof File) return (File) dir;
                    if (dir instanceof Path) return ((Path) dir).toFile();
                }
            } catch (Throwable ignored) {
            }
        }
        return new File(System.getProperty("user.dir", "."));
    }

    private static void collectMatches(File dir, String uuidDashed, String uuidNoDash, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (f.isFile()) {
                    if (isUuidNamedFile(f.getName(), uuidDashed, uuidNoDash)) out.add(f);
                } else if (depth > 0 && f.isDirectory() && !isSkippedDir(f.getName())) {
                    collectMatches(f, uuidDashed, uuidNoDash, out, depth - 1);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ファイル名内で UUID が独立トークンとして現れるかを判定する。
     * 前後に hex 文字が直接続く場合は対象外 (別の長い hex 列の一部とみなす)。
     */
    private static boolean isUuidNamedFile(String fileName, String uuidDashed, String uuidNoDash) {
        String name = fileName.toLowerCase();
        return containsUuidToken(name, uuidDashed) || containsUuidToken(name, uuidNoDash);
    }

    private static boolean containsUuidToken(String name, String token) {
        int idx = 0;
        while ((idx = name.indexOf(token, idx)) >= 0) {
            boolean beforeOk = idx == 0 || !isHexChar(name.charAt(idx - 1));
            int end = idx + token.length();
            boolean afterOk = end >= name.length() || !isHexChar(name.charAt(end));
            if (beforeOk && afterOk) return true;
            idx = end;
        }
        return false;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static boolean isSkippedDir(String dirName) {
        String lower = dirName.toLowerCase();
        for (String s : SKIP_DIR_NAMES) {
            if (s.equals(lower)) return true;
        }
        return false;
    }

    private static boolean isVanillaClass(Class<?> clazz) {
        return clazz.getName() != null && clazz.getName().startsWith("net.minecraft.");
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    private static Field findField(Class<?> clazz, String[] names) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (!Modifier.isStatic(f.getModifiers())) return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String[] names, int paramCount) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (m.getParameterCount() == paramCount) return m;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Field[] safeGetDeclaredFields(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
    }
}
