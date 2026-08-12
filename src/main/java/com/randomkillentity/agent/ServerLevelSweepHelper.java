package com.randomkillentity.agent;

import com.randomkillentity.utils.UnsafeUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ServerLevel 合成メソッド {@code rkKillSweep} から呼ばれるスナップショット収集。
 *
 * <p>設計v6 (pig2mod 対策): kill 呼び出しを「全てバニラフレーム」から行うため、
 * ServerLevel に合成メソッド {@code rkKillSweep} を注入し、tick の HEAD から呼ぶ。
 * 本クラスはその合成メソッドから呼ばれ、対象エンティティのスナップショットを
 * 収集して返す。キル実行 ({@code setRemoved(KILLED)}) は合成メソッド内
 * (net.minecraft.server.level.ServerLevel フレーム) で行われるため、
 * pig2mod のスタック検査 (isCalledFromOtherModWithin1/5) を素通りする。</p>
 *
 * <p><b>重要な制約:</b> このクラスのフレームはキル呼び出しのスタックに
 * 残してはならない。収集・前処理は全てこのメソッド内で完結し、
 * 返却後に初めて合成メソッド側でキルが実行される。</p>
 *
 * <p>MC クラスへは直接依存しない (agent は Forge 非依存)。
 * エンティティ列挙は "Server thread" のコンテキストクラスローダー経由の
 * リフレクションで行う (KillDaemon と同方式)。</p>
 */
public final class ServerLevelSweepHelper {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Sweep");

    private static final String[] HEALTH_FIELDS = {"f_20963_", "health"};
    private static final String[] INVULN_FIELDS = {"f_19802_", "invulnerableTime"};

    private ServerLevelSweepHelper() {
    }

    /**
     * 対象エンティティのスナップショットを収集する。
     * 収集中にエンティティ状態を変更しない (CME 防止: キルは合成メソッド側で実行)。
     *
     * @param serverLevel ServerLevel インスタンス (リフレクションで全エンティティ列挙)
     * @return 対象 LivingEntity のスナップショット (変更可能な ArrayList)。失敗時は空リスト
     */
    public static List<Object> collectSnapshot(Object serverLevel) {
        List<Object> snapshot = new ArrayList<>();
        try {
            // 高速パス: ワールド即死フラグ OFF かつ UUID 個別対象が空なら
            // 毎 Tick の全エンティティ列挙コストを回避して即返す。
            if (!Boolean.parseBoolean(System.getProperty("rk.should_kill", "false"))
                    && KillTargetRegistry.targets().isEmpty()) {
                return snapshot;
            }

            Iterable<?> entities = invokeIterable(serverLevel, "getAllEntities", "m_8583_");
            if (entities == null) return snapshot;

            for (Object entity : entities) {
                try {
                    if (entity == null) continue;
                    if (!isLivingEntity(entity)) continue;
                    if (!KillTargetRegistry.isMarkedEntity(entity)) continue;
                    // キル前処理 (invulnerableTime リセット + health 0 直書き)。
                    // 既存の汎用キル機構 (UnsafeUtils) のみ使用 — pig2mod 内部フィールドには触れない。
                    preKill(entity);
                    snapshot.add(entity);
                } catch (Throwable ignored) {
                    // 個別エンティティの失敗は無視して次へ
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("collectSnapshot error: {}", t);
        }
        return snapshot;
    }

    /**
     * キル前処理: invulnerableTime (f_19802_) を 0 にし、health 実体フィールドへ 0 を直書きする。
     * SyncedEntityData は一切通らない (既存 KillDaemon.forceKill と同方式)。
     */
    private static void preKill(Object entity) {
        try {
            UnsafeUtils.writeIntField(entity, 0, INVULN_FIELDS);
        } catch (Throwable ignored) {
        }
        try {
            UnsafeUtils.writeFloatField(entity, 0.0f, HEALTH_FIELDS);
        } catch (Throwable ignored) {
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
}
