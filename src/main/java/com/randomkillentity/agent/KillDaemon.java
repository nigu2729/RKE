package com.randomkillentity.agent;

import com.randomkillentity.agent.hook.LivingEntityMethodImpl;
import com.randomkillentity.utils.NativeLoader;
import com.randomkillentity.utils.UnsafeUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 5ms 常駐デーモン。設計v2 項目3: 防御mod が health を書き戻しても 5ms 以内に再殺する。
 *
 * <p>対象 UUID を持つエンティティをサーバーレベルから走査し、
 * lal (KillEnforcer) で実証されたエスカレーションキルチェーンで殺害する:</p>
 *
 * <ol>
 *   <li>invulnerableTime (f_19802_) を 0 にリセット</li>
 *   <li>hurt(damageSources().genericKill(), Float.MAX_VALUE)  (m_6469_)</li>
 *   <li>生存なら hurt を再攻撃 (100000.0f)</li>
 *   <li>生存なら damageSources().generic() で再攻撃 (m_269264_)</li>
 *   <li>生存なら kill() 直接呼び (m_6074_)</li>
 *   <li>生存なら setHealth(0) + die(genericKill)  (m_21153_ / m_6667_)</li>
 * </ol>
 *
 * <p>各段の後に isDeadOrDying() (m_21224_) / isRemoved() (m_213877_) で
 * 実死亡 (fakeなし) を検証する。最終保険として health 実体フィールドを
 * Unsafe 直書きで 0 に強制する (設計v2 項目10, 11)。</p>
 *
 * <p>MC クラスへは直接依存しない (agent は隔離クラスローダー)。
 * 全参照は "Server thread" のコンテキストクラスローダー経由のリフレクションで行う。</p>
 */
public final class KillDaemon {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill KillDaemon");
    private static final long CYCLE_MS = 5L;
    private static final int RESCAN_INTERVAL = 10;
    // 設計v2 項目11: ネイティブヒープスイープの周期 (100サイクル = 500ms)。
    // フルヒープ走査 (IterateOverInstancesOfClass) は高コストのため Java 側の 5ms 再殺とは分離。
    private static final int NATIVE_SWEEP_INTERVAL = 100;
    private static final String[] HEALTH_FIELDS = {"f_20963_", "health"};
    private static final String[] INVULN_FIELDS = {"f_19802_", "invulnerableTime"};

    private static final Map<UUID, WeakReference<Object>> WATCHED = new ConcurrentHashMap<>();

    /** 設計v4: イベントバス破壊 (他 mod ハンドラ全解除) は初回キル時に1回だけ実行する。 */
    private static final AtomicBoolean EVENT_BUS_PURGED = new AtomicBoolean(false);

    private static volatile Thread daemonThread;
    private static volatile boolean running;

    private KillDaemon() {
    }

    public static synchronized void start() {
        if (running) return;
        running = true;
        daemonThread = new Thread(KillDaemon::loop, "RK-KillDaemon");
        daemonThread.setDaemon(true);
        daemonThread.start();
    }

    public static synchronized void stop() {
        running = false;
        daemonThread = null;
    }

    public static boolean isRunning() {
        return running;
    }

    private static void loop() {
        int cycle = 0;
        while (running) {
            try {
                CountermeasureService.runCheck();
                ClientEraser.run();

                // ⭕ 設計v3: ワールド全体判定。
                // GlobalKillFlag が確率ロールで rk.should_kill=true を立てた場合、
                // 全ワールドの全 LivingEntity (プレイヤー含む) を一斉即死させる。
                // フラグの OFF は finally で保証する — dispatch 内で例外が起きても
                // フラグが true のまま残り、リスポーンが即死し続ける事故を防ぐ。
                if (isWorldKillActive()) {
                    try {
                        dispatchWorldSweep();
                        // 設計v5: ワールド即死中もネイティブ執行層の周期スイープを実行する。
                        // isMarkedEntity が rk.should_kill を honor するため、
                        // フラグ OFF (finally) より前に呼ぶ必要がある。
                        if (cycle % NATIVE_SWEEP_INTERVAL == 0) {
                            NativeLoader.sweepEntitiesAndKill();
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("World sweep dispatch error: {}", t);
                    } finally {
                        System.setProperty("rk.should_kill", "false");
                    }
                }

                // 設計v2 後方互換: UUID 個別ターゲットが残っている場合のみ従来パスを実行
                if (!KillTargetRegistry.targets().isEmpty()) {
                    if (cycle % RESCAN_INTERVAL == 0) {
                        dispatchOnServerThread(KillDaemon::rescanAndEnforce, KILL_ENFORCE_QUEUED);
                    } else {
                        dispatchOnServerThread(KillDaemon::enforceWatched, KILL_ENFORCE_QUEUED);
                    }
                    // 設計v2 項目11: ネイティブ執行層の周期スイープ (500ms)。
                    // JVMTI ヒープ全域走査で対象エンティティを JNI 直書き + kill() 直呼び。
                    if (cycle % NATIVE_SWEEP_INTERVAL == 0) {
                        NativeLoader.sweepEntitiesAndKill();
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(CYCLE_MS);
            } catch (InterruptedException e) {
                break;
            }
            cycle++;
        }
    }

    // ── サーバースレッド委譲 (解析指摘対応) ──
    //
    // デーモンスレッドから MC のエンティティ状態 (hurt/die/discard) を直接操作すると、
    // サーバーティック中の処理と競合して NPE / 状態破損が起きるため、実操作は
    // MinecraftServer#execute(Runnable) でサーバースレッドに委譲する。
    // "execute" は tsrg で未マッピングのため dev/本番で同名 (リフレクションで解決可能)。
    // 5ms 周期のループに対しサーバーのタスク処理は 1 Tick (50ms) 単位のため、
    // キューガード (AtomicBoolean) で未処理タスクが残っている間の追加送信を抑止する。

    private static final AtomicBoolean WORLD_SWEEP_QUEUED = new AtomicBoolean(false);
    private static final AtomicBoolean KILL_ENFORCE_QUEUED = new AtomicBoolean(false);

    /** ワールド掃討タスクをサーバースレッドへ委譲する (フラグ OFF は呼び出し側の finally で保証)。 */
    private static void dispatchWorldSweep() {
        dispatchOnServerThread(KillDaemon::sweepWorldAndKill, WORLD_SWEEP_QUEUED);
    }

    /** タスクをサーバースレッドへ委譲する。guard が立て済みなら送信せず、サーバー不在ならインライン実行。 */
    private static void dispatchOnServerThread(Runnable task, AtomicBoolean guard) {
        if (guard != null && !guard.compareAndSet(false, true)) return;
        Runnable wrapped = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.warn("Server-thread task error: {}", t);
            } finally {
                if (guard != null) guard.set(false);
            }
        };
        Object server = findServer();
        if (server == null) {
            wrapped.run();
            return;
        }
        try {
            Method m = server.getClass().getMethod("execute", Runnable.class);
            m.invoke(server, wrapped);
        } catch (Throwable t) {
            if (guard != null) guard.set(false);
            wrapped.run();
        }
    }

    /** ワールド全体即死フラグの状態 (GlobalKillFlag と共有するシステムプロパティ) */
    private static boolean isWorldKillActive() {
        return Boolean.parseBoolean(System.getProperty("rk.should_kill", "false"));
    }

    /**
     * 設計v3: ワールド全体掃討。
     * サーバーの全レベルを走査し、全 LivingEntity (プレイヤー含む) に即死チェーンを適用する。
     * 除外設定 (excluded_entities) のエンティティは対象外。
     *
     * <p>getAllEntities() の Iterable を直接反復すると forceKill → discard で
     * ConcurrentModificationException が起きるため、先にスナップショットを取る。
     * 1エンティティの失敗が全体を止めないよう、エンティティ単位で例外を捕獲する。</p>
     */
    private static void sweepWorldAndKill() {
        Object server = findServer();
        if (server == null) {
            return;
        }
        Iterable<?> levels = invokeIterable(server, "getAllLevels", "m_129785_");
        if (levels == null) {
            return;
        }

        for (Object level : levels) {
            if (level == null) continue;
            Iterable<?> entities = invokeIterable(level, "getAllEntities", "m_8583_");
            if (entities == null) continue;
            List<Object> snapshot = new ArrayList<>();
            for (Object entity : entities) {
                snapshot.add(entity);
            }
            for (Object entity : snapshot) {
                try {
                    if (entity == null) continue;
                    if (!isLivingEntity(entity)) continue;
                    if (LivingEntityMethodImpl.isEntityExcluded(entity)) {
                        continue;
                    }
                    forceKill(entity);
                    UUID uuid = KillTargetRegistry.getEntityUuid(entity);
                    // プレイヤーの playerdata / advancements / stats は削除しない
                    // (アイテム・データ消失防止)。
                    if (uuid != null && !isPlayer(entity)) {
                        StateDestroyer.deletePersistenceFiles(server, uuid);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Entity sweep failed for {}: {}", entity, t);
                }
            }
        }
    }

    /** クラス階層に LivingEntity を含むか (MC 非依存のリフレクション判定)。
     *  ClassValue でクラス単位にキャッシュする (ワールド掃討では全エンティティに対して走る)。 */
    private static final ClassValue<Boolean> LIVING_ENTITY_CLASS_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                if ("net.minecraft.world.entity.LivingEntity".equals(c.getName())) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
    };

    private static boolean isLivingEntity(Object entity) {
        if (entity == null) return false;
        return LIVING_ENTITY_CLASS_CACHE.get(entity.getClass());
    }

    /**
     * クラス階層に Player / ServerPlayer を含むか (MC 非依存のリフレクション判定)。
     * null 安全: null なら false を返す。
     * LivingEntityMethodImpl の ClassValue キャッシュ版へ委譲する (重複実装の排除)。
     */
    private static boolean isPlayer(Object entity) {
        return LivingEntityMethodImpl.isPlayer(entity);
    }

    private static void enforceWatched() {
        for (Map.Entry<UUID, WeakReference<Object>> e : WATCHED.entrySet()) {
            if (!KillTargetRegistry.isMarked(e.getKey())) continue;
            Object entity = e.getValue() == null ? null : e.getValue().get();
            if (entity == null) continue;
            forceKill(entity);
        }
    }

    private static void rescanAndEnforce() {
        Object server = findServer();
        if (server == null) {
            enforceWatched();
            return;
        }
        Iterable<?> levels = invokeIterable(server, "getAllLevels", "m_129785_");
        if (levels == null) {
            enforceWatched();
            return;
        }

        WATCHED.clear();
        for (Object level : levels) {
            if (level == null) continue;
            Iterable<?> entities = invokeIterable(level, "getAllEntities", "m_8583_");
            if (entities == null) continue;
            for (Object entity : entities) {
                if (entity == null) continue;
                UUID uuid = KillTargetRegistry.getEntityUuid(entity);
                if (uuid == null || !KillTargetRegistry.isMarked(uuid)) continue;
                WATCHED.put(uuid, new WeakReference<>(entity));
                forceKill(entity);
                // プレイヤーの playerdata / advancements / stats は削除しない。
                if (!isPlayer(entity)) {
                    StateDestroyer.deletePersistenceFiles(server, uuid);
                }
            }
        }
        enforceWatched();
    }

    private static void forceKill(Object entity) {
        // 設計v4: 初回キル時に他 mod のイベントハンドラを一度だけ解除する。
        // (防御mod の無敵化チェックがイベント経由の場合に、以後の再キルを恒久無効化)
        if (EVENT_BUS_PURGED.compareAndSet(false, true)) {
            WorldStructureDestroyer.unregisterOtherModHandlers();
        }

        UnsafeUtils.writeIntField(entity, 0, INVULN_FIELDS);
        if (!isDead(entity)) {
            Object source = getGenericKillSource(entity);
            if (source != null) {
                invokeHurt(entity, source, Float.MAX_VALUE);
            }
        }
        if (!isDead(entity)) {
            Object source = getGenericKillSource(entity);
            if (source != null) {
                invokeHurt(entity, source, 100000.0f);
            }
        }
        if (!isDead(entity)) {
            Object source = getGenericSource(entity);
            if (source != null) {
                invokeHurt(entity, source, 100000.0f);
            }
        }
        if (!isDead(entity)) {
            invokeVoidNoArg(entity, "kill", "m_6074_");
        }
        if (!isDead(entity)) {
            UnsafeUtils.writeFloatField(entity, 0.0f, HEALTH_FIELDS);
            Object source = getGenericKillSource(entity);
            if (source != null) {
                invokeVoid(entity, source, "die", "m_6667_");
            }
        }
        UnsafeUtils.writeFloatField(entity, 0.0f, HEALTH_FIELDS);

        // 設計v5 (プレイヤー殺害拡大): プレイヤーは vanilla クラス階層のみのため、
        // 既存 destroy() の corruptNumericFields / corruptProtectionFlags は即 break する。
        // 代わりに destroyPlayerDefenses() で mixin 注入フィールド ($ 接頭辞) と
        // SynchedEntityData を破壊する。mob は従来どおり destroy() で全破壊する。
        if (isPlayer(entity)) {
            StateDestroyer.destroyPlayerDefenses(entity);
        } else {
            StateDestroyer.destroy(entity);
        }

        try {
            NativeLoader.forceWriteIntField(entity, INVULN_FIELDS[0], 0);
            NativeLoader.forceWriteFloatField(entity, HEALTH_FIELDS[0], 0.0f);
        } catch (Throwable t) {
            LOGGER.warn("Native force-write failed for {}: {}", entity, t);
        }

        boolean structural = !isPlayer(entity);
        WorldStructureDestroyer.purgeFromWorld(entity, structural);

        // spoofOnLevelUnload はプレイヤーが居るレベルに対しては発火させない。
        // 偽の LevelEvent.Unload が Forge/他 mod のプレイヤー死亡処理を再実行させ
        // 2回目の死亡を引き起こす原因になる。
        if (!isPlayer(entity)) {
            WorldStructureDestroyer.spoofOnLevelUnload(entity);
        }

        if (!isPlayer(entity)) {
            invokeVoidNoArg(entity, "discard", "m_146870_");
        }

        // 設計v5: 最終保険 (独自死亡処理)。
        // vanilla の死亡チェーン (die / setRemoved / 構造除去) が防御mod の mixin で
        // 完全に塞がれ、dead フラグが立っていない場合のみ、dead=true + health=0 +
        // removed=true を Unsafe 直書きで確定させ、purgeFromWorld に委譲する。
        // プレイヤーは structural=false なので安全 (虚空送りは行われない)。
        if (!FallbackDeathHandler.isDeadFlagged(entity)) {
            FallbackDeathHandler.forceDeath(entity);
        }
    }

    private static boolean isRemoved(Object entity) {
        Object removed = invokeObject(entity, "isRemoved", "m_213877_");
        return Boolean.TRUE.equals(removed);
    }

    private static boolean isDead(Object entity) {
        Object deadOrDying = invokeObject(entity, "isDeadOrDying", "m_21224_");
        if (Boolean.TRUE.equals(deadOrDying)) return true;
        Object removed = invokeObject(entity, "isRemoved", "m_213877_");
        return Boolean.TRUE.equals(removed);
    }

    private static Object getGenericKillSource(Object entity) {
        try {
            // Entity.damageSources() = m_269291_() (lal で実証済み: target.m_269291_().m_287172_())
            Object sources = invokeObject(entity, "damageSources", "m_269291_");
            if (sources == null) return null;
            return invokeObject(sources, "genericKill", "m_287172_");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getGenericSource(Object entity) {
        try {
            // DamageSources.generic() = m_269264_() (srg_to_official_1.20.1.tsrg で実証)
            Object sources = invokeObject(entity, "damageSources", "m_269291_");
            if (sources == null) return null;
            return invokeObject(sources, "generic", "m_269264_");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findServer() {
        ClassLoader cl = findServerClassLoader();
        if (cl == null) return null;

        // Path 1 (primary): Forge の ServerLifecycleHooks.getCurrentServer()。
        // 1.20.1 の MinecraftServer には static getServer() が存在しない (javap 実証済み)。
        // Forge 本体のクラスは reobf されないため dev/本番で名前が同一。
        // 統合サーバー (シングル)・専用サーバーの両方で現在のサーバーを返す。
        try {
            Class<?> hooksClass = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", true, cl);
            Method m = hooksClass.getMethod("getCurrentServer");
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                Object server = m.invoke(null);
                if (server != null) {
                    return server;
                }
            }
        } catch (Throwable ignored) {
        }

        // Path 2 (fallback): Minecraft.getInstance().getSingleplayerServer()。
        // クライアント統合サーバー専用。SRG: getInstance=m_91087_, getSingleplayerServer=m_91092_
        // (srg_to_official_1.20.1.tsrg で実証済み)。
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            Object mc = invokeStatic(mcClass, "getInstance", "m_91087_");
            if (mc != null) {
                Object server = invokeObject(mc, "getSingleplayerServer", "m_91092_");
                if (server != null) {
                    return server;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static volatile ClassLoader cachedServerClassLoader;

    private static ClassLoader findServerClassLoader() {
        // getAllStackTraces は全スレッドのスタックを凍結する高コスト操作のため、
        // 一度解決した CCL をキャッシュする (5ms ループで毎回呼ぶとスパイクの原因)。
        ClassLoader cached = cachedServerClassLoader;
        if (cached != null) return cached;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName() != null && "Server thread".equals(t.getName())) {
                ClassLoader cl = t.getContextClassLoader();
                if (cl != null) {
                    cachedServerClassLoader = cl;
                    return cl;
                }
            }
        }
        return KillDaemon.class.getClassLoader();
    }

    private static Iterable<?> invokeIterable(Object target, String devName, String srgName) {
        Object result = invokeObject(target, devName, srgName);
        return result instanceof Iterable ? (Iterable<?>) result : null;
    }

    private static Object invokeObject(Object target, String devName, String srgName) {
        try {
            Method m = target.getClass().getMethod(devName);
            return m.invoke(target);
        } catch (Throwable t1) {
            try {
                Method m = target.getClass().getMethod(srgName);
                return m.invoke(target);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static Object invokeStatic(Class<?> targetClass, String devName, String srgName) {
        try {
            Method m = targetClass.getMethod(devName);
            if (!Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException();
            return m.invoke(null);
        } catch (Throwable t1) {
            try {
                Method m = targetClass.getMethod(srgName);
                if (!Modifier.isStatic(m.getModifiers())) return null;
                return m.invoke(null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static void invokeVoid(Object target, Object arg, String devName, String srgName) {
        try {
            Method m = target.getClass().getMethod(devName, arg.getClass());
            m.invoke(target, arg);
        } catch (Throwable t1) {
            try {
                Method m = target.getClass().getMethod(srgName, arg.getClass());
                m.invoke(target, arg);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void invokeVoidNoArg(Object target, String devName, String srgName) {
        try {
            Method m = target.getClass().getMethod(devName);
            m.invoke(target);
        } catch (Throwable t1) {
            try {
                Method m = target.getClass().getMethod(srgName);
                m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void invokeHurt(Object target, Object source, float amount) {
        try {
            Method m = target.getClass().getMethod("hurt", source.getClass(), float.class);
            m.invoke(target, source, amount);
        } catch (Throwable t1) {
            try {
                Method m = target.getClass().getMethod("m_6469_", source.getClass(), float.class);
                m.invoke(target, source, amount);
            } catch (Throwable ignored) {
            }
        }
    }
}
