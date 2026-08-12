package com.randomkillentity.agent;

import com.randomkillentity.agent.hook.LivingEntityMethodImpl;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * 防御mod によるフック除去への対抗措置 (設計v2 項目8)。
 *
 * <p>防御mod が LivingEntity を再トランスフォームして RETURN フックを
 * 除去した場合、フック呼び出しカウンタ (LivingEntityMethodImpl.hookCalls)
 * の停滞で検出し、retransformClasses でフックを再適用する。
 * RKTransformer は LivingEntity の再トランスフォーム時に自動的に再注入する。</p>
 *
 * <p>retransformClasses は JVM 全体のセーフポイント (全スレッド停止) を伴うため、
 * 過剰発動はスパイクの原因になる。特にメニュー画面ではエンティティが存在せず
 * フック自体が呼ばれないためカウンタは常に 0 のまま停滞し、単純な停滞検出では
 * 2秒ごとに retransform が走り続ける。そのため以下3つのゲートで発動を制限する:</p>
 * <ol>
 *   <li>カウンタが一度でも動いた実績がある (メニュー等の「動いて当然でない」停滞を除外)</li>
 *   <li>停滞が連続 {STALL_THRESHOLD} 回 (約6秒) 継続 (単発の停滞を無視)</li>
 *   <li>前回の再適用から {REAPPLY_BACKOFF_MS}ms 経過 (再適用が効けばカウンタが
 *       動き出すため、バックオフ中に動けば以降の再適用は自然に走らない)</li>
 * </ol>
 */
public final class CountermeasureService {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Countermeasure");
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";
    private static final String PLAYER = "net.minecraft.world.entity.player.Player";
    private static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer";
    // 設計v6: ServerLevel 合成メソッド (rkKillSweep) もストリップされたら再適用する
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final long CHECK_INTERVAL_MS = 2000L;
    /** フック除去と判定するまでの連続停滞回数 (2s × 3 = 約6秒の停滞で初めて疑う) */
    private static final int STALL_THRESHOLD = 3;
    /** 再適用後の最低間隔。ストリップ合戦時も高々 1回/30s に抑える。 */
    private static final long REAPPLY_BACKOFF_MS = 30000L;

    private static volatile long lastHookCalls = -1L;
    private static volatile long lastCheckTime = 0L;
    private static volatile int stallStreak = 0;
    private static volatile long lastReapplyTime = 0L;

    private CountermeasureService() {
    }

    /**
     * フック生存確認。KillDaemon ループから定期的に呼ばれる。
     * フック呼び出しカウンタが「動いた実績の後に」連続停滞していたら
     * retransformClasses で再適用する (メニュー画面等の自然な停滞では発動しない)。
     */
    public static void runCheck() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        long calls = LivingEntityMethodImpl.hookCalls();
        if (calls == lastHookCalls) {
            stallStreak++;
        } else {
            stallStreak = 0;
        }
        lastHookCalls = calls;

        // ゲート1: 一度も動いたことがない = フックが発火する場面が無い (メニュー等)。
        // この状態での retransform は無意味な全スレッド停止を繰り返すだけなので除外。
        if (lastHookCalls <= 0) return;
        // ゲート2: 単発の停滞 (エンティティが一時的に居ない等) では再適用しない。
        if (stallStreak < STALL_THRESHOLD) return;
        // ゲート3: バックオフ。再適用が効けばカウンタが動いて stallStreak が
        // リセットされるため、通常は一度の再適用で収束する。
        if (now - lastReapplyTime < REAPPLY_BACKOFF_MS) return;

        reapplyHooks();
        lastReapplyTime = now;
    }

    /** 対象クラスを1回の retransformClasses 呼び出しで再適用する (セーフポイントは1回) */
    private static void reapplyHooks() {
        Instrumentation inst = KillTargetRegistry.getInstrumentation();
        if (inst == null) return;

        List<Class<?>> targets = new ArrayList<>(4);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!isTargetClass(c.getName())) continue;
            if (inst.isModifiableClass(c)) {
                targets.add(c);
            } else {
                LOGGER.warn(c.getName() + " is not modifiable, cannot re-apply hooks");
            }
        }
        if (targets.isEmpty()) return;

        try {
            // 4クラスを個別に呼ぶとセーフポイントが4回発生する。1回にまとめる。
            inst.retransformClasses(targets.toArray(new Class<?>[0]));
            LOGGER.info("Re-applied hooks to " + targets.size() + " class(es) (countermeasure)");
        } catch (Throwable t) {
            LOGGER.error("Failed to re-apply hooks", t);
        }
    }

    private static boolean isTargetClass(String className) {
        return LIVING_ENTITY.equals(className)
                || PLAYER.equals(className)
                || SERVER_PLAYER.equals(className)
                || LOCAL_PLAYER.equals(className)
                || SERVER_LEVEL.equals(className);
    }
}
