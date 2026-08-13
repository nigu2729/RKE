package com.randomkillentity.agent;

import com.randomkillentity.utils.NativeLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * ワールド管理構造からの直接除去 (設計v4: 構造破壊レイヤー)。
 *
 * <p>防御mod が {@code die() / setHealth() / setRemoved() / discard()} を mixin で
 * cancel しても、エンティティが「ワールドに存在し続ける」のは vanilla の
 * エンティティ管理データ構造 ({@code EntityTickList} / {@code EntitySectionStorage} /
 * {@code EntityLookup}) に登録されたままであるため。この層ではこれらの
 * 内部フィールドを直接操作して、メソッド介入では防げない除去を行う。</p>
 *
 * <p>手法は pig2mod-1.20.1-2.4.3 の {@code removeTheEnemiesInTheServerLevel} の
 * 汎用移植。クラス名・アイテム名の参照は一切行わず、vanilla の
 * フィールド名 (SRG) / メソッド名 (SRG) のみに依存する。</p>
 *
 * <p>MC クラスへの直接参照は持たない (agent は隔離クラスローダー)。
 * 全参照はリフレクション + dev名/SRG名フォールバックで行う (既存方針と同一)。
 * Forge 本体のクラス (MinecraftForge 等) は reobf されないため dev名で確定。</p>
 */
public final class WorldStructureDestroyer {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Structure");

    // ── エンティティ ──
    private static final String[] ENTITY_LEVEL_FIELDS = {"f_19853_", "level"};
    private static final String[] ENTITY_ID_FIELDS = {"f_19848_", "id"};
    private static final String[] REMOVAL_REASON_FIELDS = {"f_146795_", "removalReason"};
    private static final String[] SET_REMOVED_NAMES = {"m_142467_", "setRemoved"};
    private static final String[] SET_POS_NAMES = {"m_20343_", "setPos"};
    private static final String[] GET_LEVEL_NAMES = {"m_9236_", "level"};

    // ── ServerLevel ──
    private static final String[] ENTITY_MANAGER_FIELDS = {"f_143244_", "entityManager"};
    private static final String[] ENTITY_TICK_LIST_FIELDS = {"f_143243_", "entityTickList"};
    private static final String[] GET_CHUNK_SOURCE_NAMES = {"m_7726_", "getChunkSource"};

    // ── PersistentEntitySectionManager ──
    private static final String[] SECTION_STORAGE_FIELDS = {"f_157495_", "sectionStorage"};
    private static final String[] ENTITY_LOOKUP_FIELDS = {"f_157494_", "entityLookup"};
    private static final String[] ENTITY_GETTER_FIELDS = {"f_157496_", "entityGetter"};

    // ── EntitySectionStorage ──
    private static final String[] SECTIONS_FIELDS = {"f_156852_", "sections"};

    // ── EntitySection ──
    private static final String[] SECTION_STORAGE_INNER_FIELDS = {"f_156827_", "storage"};

    // ── EntityTickList ──
    private static final String[] ITERATED_FIELDS = {"f_156903_", "iterated"};

    // ── LevelEntityGetterAdapter ──
    private static final String[] VISIBLE_STORAGE_FIELDS = {"f_156940_", "visibleEntityStorage"};

    // ── ServerChunkCache ──
    private static final String[] REMOVE_ENTITY_NAMES = {"m_8443_", "removeEntity"};

    // ── RemovalReason enum ──
    private static final String REMOVAL_REASON_CLASS = "net.minecraft.world.entity.Entity$RemovalReason";
    private static final String KILLED_ENUM_NAME = "KILLED";

    // ── Forge イベントバス (Forge クラスは reobf されないため dev名で確定) ──
    private static final String MINECRAFT_FORGE_CLASS = "net.minecraftforge.common.MinecraftForge";
    private static final String EVENT_BUS_FIELD = "EVENT_BUS";
    private static final String[] EVENT_BUS_LISTENERS_FIELDS = {"listeners"};
    private static final String UNREGISTER_METHOD = "unregister";

    private WorldStructureDestroyer() {
    }

    // ─────────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 対象エンティティをワールド管理構造から直接除去する。
     * 各除去は独立した try/catch で囲み、1つの失敗が他を止めない。
     *
     * @param structural true なら EntityTickList / EntitySectionStorage / EntityLookup から
     *                   直接除去し虚空送りまで行う。false (プレイヤー等、管理構造の
     *                   直接除去がサーバー破綻を招く対象) なら removalReason 直書きと
     *                   setRemoved 呼び出しのみ行う。
     */
    public static void purgeFromWorld(Object entity, boolean structural) {
        if (entity == null) return;
        if (structural) {
            Object level = getLevel(entity);
            if (level != null) {
                removeFromEntityTickList(level, entity);
                removeFromEntitySections(level, entity);
                removeFromEntityLookups(level, entity);
                removeViaChunkCache(level, entity);
            }
        }
        // removalReason フィールド直書き (setRemoved が mixin で cancel されても残る)
        writeRemovalReasonKilled(entity);
        // setRemoved(KILLED) 呼び出し (正規の除去経路。cancel されても直書きが残る)
        callSetRemoved(entity);
        if (structural) {
            // 虚空送り (tick 継続による復活を防ぐ最終保険)
            teleportToVoid(entity);
        }

        // Aggressive fallback: if the entity resists Java-side removal (due to third-party mixins),
        // attempt native enforcement: force-write health/invuln fields and run JVMTI heap sweep.
        // This is a behavioral (not mod-specific) fallback that avoids hardcoded blacklist names.
        try {
            // force-write commonly used health/invuln field names (SRG + plain)
            NativeLoader.forceWriteFloatField(entity, "f_20963_", 0.0f);
            NativeLoader.forceWriteFloatField(entity, "health", 0.0f);
            NativeLoader.forceWriteIntField(entity, "f_19802_", 0);
            NativeLoader.forceWriteIntField(entity, "invulnerableTime", 0);
            // Run JVMTI sweep which can find and kill surviving instances at native level
            int removed = NativeLoader.sweepEntitiesAndKill();
            if (removed > 0) {
                LOGGER.info("Native sweep removed {} entity instances as aggressive fallback", removed);
            }
        } catch (Throwable t) {
            LOGGER.warn("Aggressive native fallback failed: {}", t.toString());
        }
    }

    /**
     * Forge のイベントバスから「他 mod の」登録ハンドラを全て解除する。
     *
     * <p>vanilla (net.minecraft.*) ・ Forge 本体 (net.minecraftforge.*) ・
     * 自 mod (com.randomkillentity.*) のハンドラは絶対に解除しない。
     * 防御mod のイベントハンドラ (無敵化チェック等) だけを汎用的に無力化する。</p>
     */
    public static void unregisterOtherModHandlers() {
        try {
            Class<?> forgeClass = Class.forName(MINECRAFT_FORGE_CLASS, true, findContextClassLoader());
            Field busField = forgeClass.getField(EVENT_BUS_FIELD);
            Object bus = busField.get(null);
            if (bus == null) return;

            // EventBus 内部の登録マップ (ConcurrentHashMap<Object, List<IEventListener>>)
            Field listenersField = findField(bus.getClass(), EVENT_BUS_LISTENERS_FIELDS);
            if (listenersField == null) return;
            listenersField.setAccessible(true);
            Object listenersObj = listenersField.get(bus);
            if (!(listenersObj instanceof Map)) return;

            Method unregister = findMethod(bus.getClass(), UNREGISTER_METHOD, 1);
            if (unregister == null) return;

            int removed = 0;
            for (Object key : ((Map<?, ?>) listenersObj).keySet()) {
                if (key == null || isProtectedHandler(key)) continue;
                try {
                    unregister.invoke(bus, key);
                    removed++;
                } catch (Throwable ignored) {
                }
            }
            if (removed > 0) {
                LOGGER.info("Unregistered {} other-mod event handler(s)", removed);
            }
        } catch (Throwable t) {
            LOGGER.warn("Event bus purge failed: {}", t);
        }
    }

    /**
     * 敵クラスの {@code onLevelUnload(LevelEvent$Unload)} ハンドラを反射で発見し、
     * 偽のアンロードイベントを送って「レベルがアンロードされた」と誤認させる
     * (pig2mod {@code invokeMethodOfEnemy} の汎用移植)。
     *
     * <p>専用対策ではない: クラス名・メソッド名の参照は行わず、実行時に
     * {@code getDeclaredMethod} で発見するだけ。クラッシュ安全のため
     * あらゆる例外は握りつぶす。</p>
     */
    public static void spoofOnLevelUnload(Object entity) {
        if (entity == null) return;
        Object level = getLevel(entity);
        if (level == null) return;

        try {
            Class<?> unloadEventClass = Class.forName(
                    "net.minecraftforge.event.level.LevelEvent$Unload", true, findContextClassLoader());
            Method handler;
            try {
                handler = entity.getClass().getDeclaredMethod("onLevelUnload", unloadEventClass);
            } catch (NoSuchMethodException e) {
                return; // ハンドラを持たない mod → 効果なし、静かに諦める
            }
            handler.setAccessible(true);

            // Whitelist-based safety: only invoke handlers declared in trusted packages
            // (vanilla, Forge, or this mod). This avoids ad-hoc blacklists while preventing
            // execution of potentially fragile third-party handlers.
            Class<?> decl = handler.getDeclaringClass();
            if (!isProtectedHandler(decl)) {
                LOGGER.info("Skipping onLevelUnload handler declared in external class {}", decl.getName());
                return;
            }

            Object event = unloadEventClass.getConstructor(
                    Class.forName("net.minecraft.world.level.LevelAccessor", true, findContextClassLoader()))
                    .newInstance(level);
            try {
                handler.invoke(entity, event);
            } catch (Throwable t) {
                // Guard: if invoking the handler throws, log and continue without failing the server
                LOGGER.warn("onLevelUnload handler threw, skipping: {}", t);
            }
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ワールド構造からの除去
    // ─────────────────────────────────────────────────────────────

    /** EntityTickList.iterated (Int2ObjectMap) から entityId を除去する。 */
    private static void removeFromEntityTickList(Object level, Object entity) {
        try {
            Object tickList = readField(level, ENTITY_TICK_LIST_FIELDS);
            if (tickList == null) return;
            Object iterated = readField(tickList, ITERATED_FIELDS);
            if (iterated == null) return;
            Integer id = (Integer) readField(entity, ENTITY_ID_FIELDS);
            if (id == null) return;
            // Int2ObjectMap.remove(int)
            Method removeInt = findMethod(iterated.getClass(), "remove", 1, int.class);
            if (removeInt != null) {
                removeInt.invoke(iterated, id);
            }
        } catch (Throwable ignored) {
        }
    }

    /** EntitySectionStorage.sections の全 EntitySection.storage (ClassInstanceMultiMap) から除去する。 */
    private static void removeFromEntitySections(Object level, Object entity) {
        try {
            Object entityManager = readField(level, ENTITY_MANAGER_FIELDS);
            if (entityManager == null) return;
            Object sectionStorage = readField(entityManager, SECTION_STORAGE_FIELDS);
            if (sectionStorage == null) return;
            Object sections = readField(sectionStorage, SECTIONS_FIELDS);
            if (sections == null) return;

            Method valuesMethod = null;
            try {
                valuesMethod = sections.getClass().getMethod("values");
            } catch (Throwable ignored) {
            }
            if (valuesMethod == null) return;
            Object values = valuesMethod.invoke(sections);
            if (!(values instanceof Iterable)) return;

            for (Object section : (Iterable<?>) values) {
                if (section == null) continue;
                try {
                    Object storage = readField(section, SECTION_STORAGE_INNER_FIELDS);
                    if (storage == null) continue;
                    // ClassInstanceMultiMap.remove(Object) — Collection 実装
                    Method remove = findMethod(storage.getClass(), "remove", 1);
                    if (remove != null) {
                        remove.invoke(storage, entity);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** EntityLookup (entityManager.entityLookup と entityGetter 内部) から除去する。 */
    private static void removeFromEntityLookups(Object level, Object entity) {
        try {
            Object entityManager = readField(level, ENTITY_MANAGER_FIELDS);
            if (entityManager == null) return;

            // 経路1: entityManager.entityLookup
            Object lookup1 = readField(entityManager, ENTITY_LOOKUP_FIELDS);
            if (lookup1 != null) {
                removeFromEntityLookup(lookup1, entity);
            }
            // 経路2: entityManager.entityGetter → LevelEntityGetterAdapter.visibleEntityStorage
            Object entityGetter = readField(entityManager, ENTITY_GETTER_FIELDS);
            if (entityGetter != null) {
                Object lookup2 = readField(entityGetter, VISIBLE_STORAGE_FIELDS);
                if (lookup2 != null) {
                    removeFromEntityLookup(lookup2, entity);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** EntityLookup.remove(entity)。SRG 候補 (m_156810_) と dev名 (remove) を両方試す。 */
    private static void removeFromEntityLookup(Object lookup, Object entity) {
        try {
            Method remove = findMethod(lookup.getClass(), new String[]{"m_156822_", "remove"}, 1);
            if (remove != null) {
                remove.invoke(lookup, entity);
            }
        } catch (Throwable ignored) {
        }
    }

    /** ServerChunkCache.removeEntity(entity) — 正規の除去 API。 */
    private static void removeViaChunkCache(Object level, Object entity) {
        try {
            Object chunkSource = invokeNoArg(level, GET_CHUNK_SOURCE_NAMES);
            if (chunkSource == null) return;
            Method remove = findMethod(chunkSource.getClass(), REMOVE_ENTITY_NAMES, 1);
            if (remove != null) {
                remove.invoke(chunkSource, entity);
            }
        } catch (Throwable ignored) {
        }
    }

    /** removalReason フィールドを RemovalReason.KILLED で直接書き込む (mixin バイパス)。 */
    private static void writeRemovalReasonKilled(Object entity) {
        try {
            Field reasonField = findField(entity.getClass(), REMOVAL_REASON_FIELDS);
            if (reasonField == null) return;
            reasonField.setAccessible(true);
            Object killed = readStaticEnumConstant(REMOVAL_REASON_CLASS, KILLED_ENUM_NAME);
            if (killed == null) return;
            reasonField.set(entity, killed);
        } catch (Throwable ignored) {
        }
    }

    /** setRemoved(RemovalReason.KILLED) を呼ぶ。mixin で cancel されても直書きが残る。 */
    private static void callSetRemoved(Object entity) {
        try {
            Object killed = readStaticEnumConstant(REMOVAL_REASON_CLASS, KILLED_ENUM_NAME);
            if (killed == null) return;
            Method setRemoved = findMethod(entity.getClass(), SET_REMOVED_NAMES, 1);
            if (setRemoved != null) {
                setRemoved.invoke(entity, killed);
            }
        } catch (Throwable ignored) {
        }
    }

    /** setPos(10000, -10000, 10000) で虚空送り (tick 継続による復活を防ぐ)。 */
    private static void teleportToVoid(Object entity) {
        try {
            Method setPos = findMethod(entity.getClass(), SET_POS_NAMES, 3, double.class, double.class, double.class);
            if (setPos != null) {
                setPos.invoke(entity, 10000.0d, -10000.0d, 10000.0d);
            }
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Forge イベントバス
    // ─────────────────────────────────────────────────────────────

    /** vanilla / Forge 本体 / 自 mod のハンドラは解除対象から除外する。 */
    private static boolean isProtectedHandler(Object key) {
        Class<?> cls = key instanceof Class ? (Class<?>) key : key.getClass();
        String name = cls.getName();
        return name.startsWith("net.minecraft.")
                || name.startsWith("net.minecraftforge.")
                || name.startsWith("com.randomkillentity.");
    }

    // ─────────────────────────────────────────────────────────────
    // リフレクションヘルパー (既存方針: dev名 + SRG名 フォールバック)
    // ─────────────────────────────────────────────────────────────

    private static Object getLevel(Object entity) {
        Object level = readField(entity, ENTITY_LEVEL_FIELDS);
        if (level != null) return level;
        return invokeNoArg(entity, GET_LEVEL_NAMES);
    }

    private static Object readField(Object target, String[] names) {
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String[] names) {
        Method m = findMethod(target.getClass(), names, 0);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readStaticEnumConstant(String className, String constantName) {
        try {
            Class<?> clazz = Class.forName(className, true, findContextClassLoader());
            for (Field f : clazz.getDeclaredFields()) {
                if (!f.isEnumConstant()) continue;
                if (f.getName().equals(constantName)) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
            // フォールバック: 定数名で直接検索
            Field f = clazz.getDeclaredField(constantName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
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

    private static Method findMethod(Class<?> clazz, String name, int paramCount, Class<?>... paramTypes) {
        return findMethod(clazz, new String[]{name}, paramCount, paramTypes);
    }

    private static Method findMethod(Class<?> clazz, String[] names, int paramCount, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    if (paramTypes.length > 0) {
                        Method m = c.getDeclaredMethod(name, paramTypes);
                        if (m.getParameterCount() == paramCount) return m;
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

    private static ClassLoader findContextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : WorldStructureDestroyer.class.getClassLoader();
    }
}
