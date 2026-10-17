package com.randomkillentity.agent;

import com.randomkillentity.agent.hook.LivingEntityMethodImpl;
import com.randomkillentity.utils.UnsafeUtils;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 死亡対象 UUID 管理 + 防御modレジストリスイープ。
 *
 * <p>設計v2「RK真・即死」項目5: 防御modレジストリスイープ。
 * 対象 UUID を静的 List/Set/Map から除去し、防御modが
 * 「このエンティティは守られている」と認識できなくする。</p>
 *
 * <p>純Java (Forge/MC非依存) — agent 配下に置き、フック層から直接参照できる。
 * クラスローダーの問題で mod 側と agent 側で別クラスになる可能性があるため、
 * 対象リストの共有は {@link System#getProperties()} のプロパティと
 * ローカルの ConcurrentHashMap の二段構えにする。</p>
 */
public final class KillTargetRegistry {

    /** ローカル対象セット (同一クラスローダー内での高速判定用) */
    private static final Set<UUID> LOCAL_TARGETS = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 対象 UUID を共有するためのプロパティキー (エージェント/mod間の橋渡し) */
    private static final String PROP_TARGETS = "rk.kill_targets";

    /** ロード済みクラス列挙用 (RKAgent.agentmain で設定される。ネイティブ経路では null) */
    private static volatile Instrumentation instrumentation;

    // ── スイープ負荷対策 (解析指摘対応) ──

    /** スイープの最小実行間隔 (ms)。die() フックは KillDaemon から 5ms 周期で呼ばれ得るため、
     *  毎回フルクラス走査すると Tick Lag になる。ここで 5 秒に 1 回へ間引く。 */
    private static final long SWEEP_MIN_INTERVAL_MS = 5000L;

    /** ロード済みクラス一覧キャッシュの有効期限 (ms)。クラスロードは起動後にほぼ停止するため、
     *  30 秒キャッシュで十分。 */
    private static final long CLASS_CACHE_TTL_MS = 30000L;

    /** 入れ子コンテナ走査の最大深さ。循環参照 (コンテナが自身を含む) による無限再帰を防ぐ。 */
    private static final int MAX_PURGE_DEPTH = 4;

    /** 防御modレジストリを絶対に保持しないパッケージ (JDK / 基盤ライブラリ)。
     *  ここに該当するクラスは走査をスキップする。mod 自身のクラスや
     *  mixin 注入先になり得る net.minecraft.* / net.minecraftforge.* は対象のまま。 */
    private static final String[] SKIP_PACKAGE_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.netty.", "org.apache.", "com.google.", "it.unimi.",
            "org.slf4j.", "org.objectweb.", "org.lwjgl.", "org.yaml.",
            "org.codehaus.", "com.ibm.", "org.joml.",
    };

    private static volatile long lastSweepTime = 0L;
    private static volatile Class<?>[] cachedClasses = new Class<?>[0];
    private static volatile long classCacheTime = 0L;

    private KillTargetRegistry() {
    }

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    // ── 対象管理 ──

    public static void markForKill(UUID uuid) {
        if (uuid == null) return;
        LOCAL_TARGETS.add(uuid);
        syncProperty();
    }

    public static void unmark(UUID uuid) {
        if (uuid == null) return;
        LOCAL_TARGETS.remove(uuid);
        syncProperty();
    }

    public static boolean isMarked(UUID uuid) {
        if (uuid == null) return false;
        if (LOCAL_TARGETS.contains(uuid)) return true;
        // 別クラスローダーのインスタンス経由で登録された可能性 → プロパティを再読込
        return readPropertyTargets().contains(uuid);
    }

    /**
     * エンティティ (Object) が対象かどうか。UUID をリフレクションで取得する。
     *
     * <p>設計v5: ワールド全体即死フラグ ({@code rk.should_kill}) が ON の間は
     * 全エンティティを対象とみなす (除外設定は {@code isEntityExcluded} を尊重)。
     * ネイティブ執行層 (sweepEntitiesAndKill) もこのメソッドをキャッシュして
     * 照合するため、フラグ ON 中のネイティブスイープが全対象に効く。</p>
     */
    public static boolean isMarkedEntity(Object entity) {
        if (entity == null) return false;
        if (Boolean.parseBoolean(System.getProperty("rk.should_kill", "false"))) {
            return !LivingEntityMethodImpl.isEntityExcluded(entity);
        }
        UUID uuid = getEntityUuid(entity);
        return uuid != null && isMarked(uuid);
    }

    public static Set<UUID> targets() {
        Set<UUID> merged = new HashSet<>(LOCAL_TARGETS);
        merged.addAll(readPropertyTargets());
        return merged;
    }

    public static void clear() {
        LOCAL_TARGETS.clear();
        syncProperty();
    }

    // ── 防御modレジストリスイープ (設計v2 項目5) ──

    /**
     * ロード済み全クラスの静的フィールドを走査し、
     * 対象 UUID (または対象エンティティ参照) を含む List/Set/Map から除去する。
     *
     * <p>静的コレクションから対象を除去する考え方を「対象 UUID ベース」に
     * 一般化したもの。防御mod が対象を「保護対象」として覚えている
     * データ構造 (Set/Map 等) を空にする。</p>
     */
    public static void sweepDefenseRegistries() {
        Set<UUID> targets = targets();
        if (targets.isEmpty()) return;

        // 解析指摘: フルクラス走査は高コスト。5 秒に 1 回へ間引く (die() フックは
        // KillDaemon から 5ms 周期で呼ばれ得るため、間引かないと Tick Lag になる)
        long now = System.currentTimeMillis();
        if (now - lastSweepTime < SWEEP_MIN_INTERVAL_MS) return;
        lastSweepTime = now;

        Class<?>[] loaded = getSweepableClasses();
        if (loaded == null) return;

        for (Class<?> clazz : loaded) {
            try {
                sweepClassStatics(clazz, targets);
            } catch (Throwable ignored) {
                // 個別クラスの失敗は無視して次へ
            }
        }
    }

    /** スイープ対象クラス一覧 (JDK/基盤ライブラリを除外 + 30 秒キャッシュ) */
    private static Class<?>[] getSweepableClasses() {
        long now = System.currentTimeMillis();
        if (cachedClasses.length > 0 && now - classCacheTime < CLASS_CACHE_TTL_MS) {
            return cachedClasses;
        }

        Instrumentation inst = instrumentation;
        Class<?>[] all;
        if (inst != null) {
            try {
                all = inst.getAllLoadedClasses();
            } catch (Throwable t) {
                return cachedClasses;
            }
        } else {
            // Instrumentation が無い経路 (ネイティブフォールバック) では
            // 自クラスローダー配下のみ走査する
            all = collectClassesFromClassLoader(KillTargetRegistry.class.getClassLoader());
        }
        if (all == null) return cachedClasses;

        List<Class<?>> filtered = new ArrayList<>(all.length / 4);
        for (Class<?> c : all) {
            if (c == null || c.isPrimitive() || c.isInterface() || c.isArray()) continue;
            if (isSkippablePackage(c)) continue;
            filtered.add(c);
        }
        Class<?>[] result = filtered.toArray(new Class<?>[0]);
        cachedClasses = result;
        classCacheTime = now;
        return result;
    }

    private static boolean isSkippablePackage(Class<?> clazz) {
        String name = clazz.getName();
        for (String prefix : SKIP_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void sweepClassStatics(Class<?> clazz, Set<UUID> targets) {
        for (Field f : UnsafeUtils.safeGetDeclaredFields(clazz)) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            Class<?> type = f.getType();
            // コンテナになり得ない型 (String/int/boolean 等) は f.get(null) すら呼ばずスキップ。
            // purgeContainer は Map/Collection インスタンスにしか作用しないため安全。
            if (type.isPrimitive()) continue;
            if (type != Object.class
                    && !Map.class.isAssignableFrom(type)
                    && !Collection.class.isAssignableFrom(type)) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object val = f.get(null);
                if (val == null) continue;
                purgeContainer(val, targets);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * コンテナ (List/Set/Map) から対象 UUID / 対象エンティティを除去する。
     * さらにコンテナの要素がコンテナである場合も再帰的に走査する (入れ子対策)。
     * 深さは {@link #MAX_PURGE_DEPTH} で制限する (循環参照による無限再帰防止)。
     */
    private static void purgeContainer(Object val, Set<UUID> targets) {
        purgeContainer(val, targets, 0);
    }

    private static void purgeContainer(Object val, Set<UUID> targets, int depth) {
        if (depth > MAX_PURGE_DEPTH) return;
        if (val instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) val;
            try {
                map.keySet().removeIf(k -> matchesTarget(k, targets));
            } catch (Throwable ignored) {
            }
            try {
                map.values().removeIf(v -> matchesTarget(v, targets));
            } catch (Throwable ignored) {
            }
            // 入れ子コンテナ
            for (Object v : new ArrayList<>(map.values())) {
                if (v != null && (v instanceof Map || v instanceof Collection)) {
                    purgeContainer(v, targets, depth + 1);
                }
            }
        } else if (val instanceof Collection) {
            Collection<?> col = (Collection<?>) val;
            boolean removedByApi = false;
            try {
                // Java 8+: removeIf
                removedByApi = col.removeIf(e -> matchesTarget(e, targets));
            } catch (Throwable ignored) {
                removedByApi = false;
            }

            // 汎用フォールバック: no-op Set 検出
            // removeIf が何も削除せず、かつ対象要素がまだ残っているなら内部削除を試す
            if (!removedByApi) {
                List<Object> remainingTargets = new ArrayList<>(2);
                try {
                    for (Object e : col) {
                        if (e != null && matchesTarget(e, targets)) {
                            remainingTargets.add(e);
                            if (remainingTargets.size() > 8) break; // 過剰走査防止（汎用・軽量）
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!remainingTargets.isEmpty()) {
                    tryForceInternalRemove(col, remainingTargets);
                }
            }

            // 入れ子コンテナ
            for (Object e : new ArrayList<>(col)) {
                if (e != null && (e instanceof Map || e instanceof Collection)) {
                    purgeContainer(e, targets, depth + 1);
                }
            }
        }
    }

    private static void tryForceInternalRemove(Collection<?> col, List<Object> targetsToRemove) {
        Class<?> c = col.getClass();
        String[] names = {
                "internalRemove",
                "removeInternal",
                "internalRemoveIf",
                "removeInternalIf",
                "internalClear",
                "clearInternal",
                "erase",
                "eraseAll",
                "eraseIf",
                "delete",
                "deleteAll",
                "removeElement",
                "fastRemove",
                "remove0",
                "removeAt",
                "removeNode",
                "unlink",
                "unlinkAll",
                "discard",
                "discardAll"
        };
        for (String nm : names) {
            try {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(nm)) continue;
                    int pc = m.getParameterCount();
                    if (pc == 1) {
                        m.setAccessible(true);
                        for (Object t : targetsToRemove) {
                            try {
                                m.invoke(col, t);
                            } catch (Throwable ignored) {
                            }
                        }
                    } else if (pc == 0) {
                        String lower = nm.toLowerCase();
                        if (lower.contains("clear") || lower.contains("all") || lower.contains("erase") && lower.contains("all")
                                || lower.contains("delete") && lower.contains("all") || lower.contains("discard") && lower.contains("all")) {
                            m.setAccessible(true);
                            m.invoke(col);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean matchesTarget(Object element, Set<UUID> targets) {
        if (element == null) return false;
        if (element instanceof UUID) return targets.contains(element);
        if (element instanceof CharSequence) {
            String s = element.toString();
            for (UUID u : targets) {
                if (s.equalsIgnoreCase(u.toString())) return true;
            }
            return false;
        }
        if (element instanceof java.util.UUID) return targets.contains(element);
        // エンティティ系オブジェクト: UUID をリフレクションで取得して照合
        try {
            UUID uuid = getEntityUuid(element);
            return uuid != null && targets.contains(uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── エンティティ UUID 取得 (リフレクション) ──

    /** getUUID を持たないクラスのマーカー (ConcurrentHashMap は null 値を許さない) */
    private static final java.lang.reflect.Method UUID_METHOD_MISSING = uuidMethodMissing();

    private static java.lang.reflect.Method uuidMethodMissing() {
        try {
            return KillTargetRegistry.class.getMethod("markForKill", UUID.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** getUUID 解決キャッシュ (クラス単位)。getMethod は階層走査のため毎回呼ぶと重い
     *  (KillDaemon の再走査は 50ms 周期で全エンティティ対象)。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method> UUID_METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Entity#getUUID() / m_20148_() をリフレクションで呼び出す。
     * MC クラスへの直接参照を避けるため (agent は Forge 非依存でなければならない)。
     */
    public static UUID getEntityUuid(Object entity) {
        if (entity == null) return null;
        try {
            Class<?> clazz = entity.getClass();
            java.lang.reflect.Method m = UUID_METHOD_CACHE.get(clazz);
            if (m == null) {
                m = findUuidMethod(clazz);
                UUID_METHOD_CACHE.putIfAbsent(clazz, m);
            }
            if (m == UUID_METHOD_MISSING) return null;
            Object result = m.invoke(entity);
            return result instanceof UUID ? (UUID) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method findUuidMethod(Class<?> clazz) {
        try {
            return clazz.getMethod("getUUID");
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getMethod("m_20148_");
            } catch (NoSuchMethodException ignored) {
                return UUID_METHOD_MISSING;
            }
        }
    }

    // ── プロパティ共有 ──

    private static void syncProperty() {
        try {
            System.setProperty(PROP_TARGETS, String.join(",", LOCAL_TARGETS.stream().map(UUID::toString).toList()));
        } catch (Throwable ignored) {
        }
    }

    private static Set<UUID> readPropertyTargets() {
        Set<UUID> result = new HashSet<>();
        try {
            String prop = System.getProperty(PROP_TARGETS, "");
            if (!prop.isEmpty()) {
                for (String s : prop.split(",")) {
                    try {
                        result.add(UUID.fromString(s.trim()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    // ── クラス列挙フォールバック ──

    private static Class<?>[] collectClassesFromClassLoader(ClassLoader loader) {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<?> classes = (Vector<?>) classesField.get(loader);
            if (classes == null) return new Class<?>[0];
            return classes.toArray(new Class<?>[0]);
        } catch (Throwable t) {
            return new Class<?>[0];
        }
    }
}
