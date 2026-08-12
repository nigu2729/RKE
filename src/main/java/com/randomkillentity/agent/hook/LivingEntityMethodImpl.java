package com.randomkillentity.agent.hook;

import com.randomkillentity.agent.KillTargetRegistry;
import com.randomkillentity.utils.UnsafeUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LivingEntity 注入フックの実装。
 *
 * <p>設計v2「RK真・即死」: RETURN 再注入 (キャンセル不能)。
 * 防御mod が HEAD でキャンセルしても、すべての RETURN 出口で
 * vanilla の戻り値 / 状態を上書きし直す。</p>
 *
 * <p>health の書き換えは DataWatcher (SyncedEntityData) を一切通らず、
 * {@link UnsafeUtils} による実体フィールド直書きで行う
 * (「SyncedData置き換え無効」対策への耐性)。</p>
 */
public class LivingEntityMethodImpl implements ILivingEntityMethodHook {

    public static final ILivingEntityMethodHook INSTANCE = new LivingEntityMethodImpl();

    /** LivingEntity.health のフィールド名候補 (SRG f_20963_ / 通常名 health) */
    private static final String[] HEALTH_FIELDS = {"f_20963_", "health"};

    /** フック呼び出しカウンタ。CountermeasureService がフック除去を検出するために使う。 */
    private static final AtomicLong HOOK_CALLS = new AtomicLong();

    public static long hookCalls() {
        return HOOK_CALLS.get();
    }

    // ── 対象判定 ──

    /** ワールド全体即死フラグ (GlobalKillFlag と共有するシステムプロパティ)。
     *  毎 Tick 全エンティティ×フック数回呼ばれるため 100ms TTL でキャッシュする。 */
    private static final long SHOULD_KILL_CACHE_MS = 100L;

    // フィールド宣言側に volatile を付与する
    private volatile long shouldKillReadTime = 0L;
    private volatile boolean cachedShouldKill = false;

    private boolean shouldKill() {
        long now = System.currentTimeMillis();
        if (now - shouldKillReadTime >= SHOULD_KILL_CACHE_MS) {
            shouldKillReadTime = now;
            cachedShouldKill = Boolean.parseBoolean(System.getProperty("rk.should_kill", "false"));
        }
        return cachedShouldKill;
    }

    /**
     * 除外設定 (excluded_entities) のエンティティかどうか。
     * KillDaemon のワールド全体掃討からも参照されるため public static。
     * 毎 Tick 呼ばれるため、リフレクション解決結果をキャッシュする。
     */
    public static boolean isEntityExcluded(Object entityObj) {
        if (entityObj == null) return false;
        try {
            Method getTypeMethod = getEntityTypeMethod(entityObj.getClass());
            if (getTypeMethod == null) return false;
            Object entityType = getTypeMethod.invoke(entityObj);

            ForgeRegistryAccess access = getForgeRegistryAccess(entityObj.getClass().getClassLoader());
            if (access == null) return false;

            Object resourceLocation = access.getKeyMethod.invoke(access.entityTypes, entityType);
            if (resourceLocation != null) {
                return excludedEntities().contains(resourceLocation.toString());
            }
        } catch (Exception e) {
            // 例外時は安全のため即死対象にする
        }
        return false;
    }

    // ── isEntityExcluded のリフレクション解決キャッシュ ──

    /** getType() を持たないクラスのマーカー (ConcurrentHashMap は null 値を許さない) */
    private static final Method NO_METHOD_MARKER = noMethodMarker();

    private static Method noMethodMarker() {
        try {
            return LivingEntityMethodImpl.class.getMethod("hookCalls");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Entity#getType() の解決キャッシュ (クラス単位)。getMethod は階層走査のため毎回呼ぶと重い。 */
    private static final ConcurrentHashMap<Class<?>, Method> GET_TYPE_CACHE = new ConcurrentHashMap<>();

    private static Method getEntityTypeMethod(Class<?> clazz) {
        Method m = GET_TYPE_CACHE.get(clazz);
        if (m != null) return m == NO_METHOD_MARKER ? null : m;
        Method resolved = NO_METHOD_MARKER;
        try {
            resolved = clazz.getMethod("getType");
        } catch (Throwable ignored) {
        }
        GET_TYPE_CACHE.putIfAbsent(clazz, resolved);
        return resolved == NO_METHOD_MARKER ? null : resolved;
    }

    /** ForgeRegistries.ENTITY_TYPES と getKey の解決結果 */
    private static final class ForgeRegistryAccess {
        final Object entityTypes;
        final Method getKeyMethod;

        ForgeRegistryAccess(Object entityTypes, Method getKeyMethod) {
            this.entityTypes = entityTypes;
            this.getKeyMethod = getKeyMethod;
        }
    }

    /** ForgeRegistries 解決失敗後の再試行間隔 (非 Forge 環境での毎 Tick 再試行を防ぐ) */
    private static final long FORGE_ACCESS_RETRY_MS = 5000L;

    private static volatile ForgeRegistryAccess forgeRegistryAccess;
    private static volatile long forgeAccessFailTime = 0L;

    private static ForgeRegistryAccess getForgeRegistryAccess(ClassLoader cl) {
        ForgeRegistryAccess access = forgeRegistryAccess;
        if (access != null) return access;
        if (System.currentTimeMillis() - forgeAccessFailTime < FORGE_ACCESS_RETRY_MS) return null;
        try {
            Class<?> forgeRegistriesClass = Class.forName("net.minecraftforge.registries.ForgeRegistries", true, cl);
            java.lang.reflect.Field entityTypesField = forgeRegistriesClass.getField("ENTITY_TYPES");
            Object entityRegistry = entityTypesField.get(null);
            Method getKeyMethod = entityRegistry.getClass().getMethod("getKey", Object.class);
            access = new ForgeRegistryAccess(entityRegistry, getKeyMethod);
            forgeRegistryAccess = access;
        } catch (Throwable t) {
            forgeAccessFailTime = System.currentTimeMillis();
            access = null;
        }
        return access;
    }

    /** rk.excluded_entities のパース結果キャッシュ (起動時に設定され以後ほぼ不変のため 1 秒 TTL) */
    private static final long EXCLUDED_CACHE_MS = 1000L;

    private static volatile long excludedReadTime = 0L;
    private static volatile Set<String> excludedEntitiesCache = Collections.emptySet();

    private static Set<String> excludedEntities() {
        long now = System.currentTimeMillis();
        if (now - excludedReadTime >= EXCLUDED_CACHE_MS) {
            excludedReadTime = now;
            Set<String> parsed = new HashSet<>();
            String prop = System.getProperty("rk.excluded_entities", "");
            if (!prop.isEmpty()) {
                for (String s : prop.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) parsed.add(t);
                }
            }
            excludedEntitiesCache = parsed;
        }
        return excludedEntitiesCache;
    }

    /**
     * 殺害対象かどうか。
     * <ul>
     *   <li>設計v3: ワールド全体判定。フラグ {@code rk.should_kill} が ON の間、
     *       除外設定以外の全 LivingEntity が対象</li>
     *   <li>設計v5 (プレイヤー殺害拡大): プレイヤーも対象に含める (従来の除外を撤廃)。
     *       除外理由だった「health フェイク (getHealth→0.0f / setHealth→0 強制) により
     *       リスポーン時 {@code PlayerList.respawn()} の {@code setHealth(getHealth())} が
     *       0 になり復帰不能になる」問題は、ユーザー指示により「2回死 (リスポーン直後の
     *       再即死) は許容範囲」とされたため、リスポーン挙動の修正は行わない。
     *       フラグは KillDaemon / GlobalKillFlag が 200 ticks 以内に自動OFFするため、
     *       フラグOFF後のリスポーンは正常に機能する。</li>
     *   <li>エンティティ単位の UUID 照合は廃止 (設計v2 → v3 移行)</li>
     * </ul>
     */
    private boolean isKillTarget(Object entityObj) {
        if (entityObj == null) return false;
        HOOK_CALLS.incrementAndGet();

        if (!shouldKill()) return false;
        return !isEntityExcluded(entityObj);
    }

    /**
     * クラス階層に Player / ServerPlayer を含むか (MC 非依存のリフレクション判定)。
     * クライアントの LocalPlayer は Player を継承するため、シングルプレイ
     * (統合サーバー) でも正しく判定できる。KillDaemon からも参照されるため public。
     * 毎 Tick 呼ばれるため、クラス階層走査の結果を ClassValue でキャッシュする。
     */
    public static boolean isPlayer(Object entityObj) {
        if (entityObj == null) return false;
        return PLAYER_CLASS_CACHE.get(entityObj.getClass());
    }

    /** isPlayer のクラス単位判定キャッシュ (階層走査は 1 クラスにつき 1 回) */
    private static final ClassValue<Boolean> PLAYER_CLASS_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                String name = c.getName();
                if ("net.minecraft.world.entity.player.Player".equals(name)
                        || "net.minecraft.server.level.ServerPlayer".equals(name)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    // ── health 実体フィールド直書き (SyncedEntityData 非依存) ──

    private void forceHealthZero(Object entityObj) {
        if (entityObj == null) return;
        // 実体フィールドへ直接 0 を書く。DataWatcher / setHealth は一切通らない。
        UnsafeUtils.writeFloatField(entityObj, 0.0f, HEALTH_FIELDS);
    }

    private boolean isHealthAboveZero(Object entityObj) {
        if (entityObj == null) return false;
        float h = UnsafeUtils.readFloatField(entityObj, HEALTH_FIELDS);
        return !Float.isNaN(h) && h > 0.0f;
    }

    // ── 読み取り系フック (既存 + 強化) ──

    @Override
    public float getHealth(float original, Object entityObj, Object phase) {
        if (isKillTarget(entityObj)) return 0.0f;
        return original;
    }

    @Override
    public boolean isDeadOrDying(boolean original, Object entityObj, Object phase) {
        // 設計v3: vanilla の実値を返す (嘘撤去)。
        // getHealth()→0.0f フックにより、対象の vanilla 計算 (getHealth()<=0) は
        // 自然に true になるため、即死の格は維持される。ここで真に返すと
        // vanilla die() 内の死亡判定が誤動作する。
        return original;
    }

    @Override
    public boolean isAlive(boolean original, Object entityObj, Object phase) {
        // 設計v3: vanilla の実値を返す (嘘撤去)。
        // getHealth()→0.0f フックにより isAlive は自然に false になる。
        return original;
    }

    @Override
    public boolean isRemoved(boolean original, Object entityObj, Object phase) {
        // 設計v3: vanilla の実値を返す (嘘撤去)。
        // 従来は真を返していたため、vanilla die() 冒頭の
        // `if (this.isRemoved()) return;` で早期 return し、
        // 死亡処理 (ドロップ) が一切実行されなかった。
        // また KillDaemon の discard 判定も誤動作の原因だった。
        return original;
    }

    // ── 設計v2: キャンセル不能 RETURN 再注入 ──

    @Override
    public boolean hurt(boolean original, Object entityObj, Object phase) {
        // 対象: ダメージが「実際に通った」ことを保証し、vanilla の
        // ダメージ処理 (health 減少) を強制継続させる。
        if (isKillTarget(entityObj)) {
            forceHealthZero(entityObj);
            return true;
        }
        return original;
    }

    @Override
    public void die(Object entityObj, Object phase) {
        // 実死亡チェーン: die() が呼ばれた時点で health を確実に 0 にし、
        // 防御modが持つ対象レジストリを掃除する。
        if (!isKillTarget(entityObj)) return;
        forceHealthZero(entityObj);
        try {
            KillTargetRegistry.sweepDefenseRegistries();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setHealth(Object entityObj, Object phase) {
        // 不死身化ブロック: setHealth が 0 以外の値で RETURN したら
        // 実体フィールドへ直接 0 を書き戻す。
        if (!isKillTarget(entityObj)) return;
        if (isHealthAboveZero(entityObj)) {
            forceHealthZero(entityObj);
        }
    }

    @Override
    public void heal(Object entityObj, Object phase) {
        // 対象への回復を無効化する。
        if (!isKillTarget(entityObj)) return;
        forceHealthZero(entityObj);
    }

    @Override
    public boolean addEffect(boolean original, Object entityObj, Object phase) {
        // 対象が不死身化に使うエフェクトを得るのを防ぐ。
        if (isKillTarget(entityObj)) return false;
        return original;
    }

    @Override
    public void handleDamageEvent(Object entityObj, Object phase) {
        // ダメージ後処理の保証: 対象なら health を 0 にし続ける。
        if (!isKillTarget(entityObj)) return;
        forceHealthZero(entityObj);
    }

    @Override
    public boolean checkTotemDeathProtection(boolean original, Object entityObj, Object phase) {
        // トーテム不死の発動を防ぐ。
        if (isKillTarget(entityObj)) return false;
        return original;
    }

    @Override
    public void tick(Object entityObj, Object phase) {
        // 設計v5 (プレイヤー殺害拡大 項目2): 毎 tick の RETURN フック。
        // 防御mod が「毎 tick 体力復元」(Hyperlink muteki 型) で health を戻しても、
        // RETURN 再注入により復元直後に health 実体フィールドへ 0 を書き戻す。
        // isKillTarget は shouldKill() の 100ms キャッシュ + 除外判定のみで安価。
        if (!isKillTarget(entityObj)) return;
        forceHealthZero(entityObj);
    }

    /**
     * setHealth の HEAD フック (引数書き換え)。既存動作を維持。
     * 設計v2 では RETURN フック (setHealth(Object,Object)) が最終保証を担う。
     */
    public static float setHealthHook(Object entityObj, float health) {
        LivingEntityMethodImpl impl = (LivingEntityMethodImpl) INSTANCE;
        if (impl.isKillTarget(entityObj)) return 0.0f;
        return health;
    }
}
