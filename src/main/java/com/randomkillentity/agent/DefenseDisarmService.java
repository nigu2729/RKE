package com.randomkillentity.agent;

import com.randomkillentity.utils.NativeLoader;

/**
 * 設計v11: 防御mod中和の常駐サービス。
 *
 * <p>競合防御 mod のネイティブ層は遅延ロードされ得るため、
 * {@link NativeLoader#neutralizeRivalDefenses()} を周期的に呼び、
 * breakpoint / field modification watch が再設置されても解除する。
 * これは特定の mod を指定しない汎用の JVMTI 操作である。</p>
 *
 * <p>Java バイトコードレベルの中和 (ガード/復元メソッドの no-op 化) は
 * クラスロード時に Instrumentation トランスフォーマ側で行われるため、
 * ここでは行わない (重い再トランスフォームを避ける)。</p>
 *
 * <p>副作用の大きさは許容する。到達範囲・突破力を優先する方針に沿う。</p>
 */
public final class DefenseDisarmService {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Disarm");

    /** ループ周期 (ms)。ネイティブ解除のみで軽量。 */
    private static final long INTERVAL_MS = 1000L;

    private static volatile boolean started = false;

    private DefenseDisarmService() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(DefenseDisarmService::loop, "rk-defense-disarm");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        LOGGER.info("DefenseDisarmService started");
    }

    private static void loop() {
        long cycle = 0;
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            cycle++;

            // JVMTI 防御 (breakpoint / field watch) の解除を再適用
            try {
                NativeLoader.neutralizeRivalDefenses();
            } catch (Throwable ignored) {
                // 未初期化・アンロード直後など
            }

            // 設計v12 (3秒周期): クラス置き換え戦への対抗。
            // 当方 transformer をチェーン末尾へ移動し、バニラ中核クラスの
            // 改変 (敵対的 retransform/redefine) を検出して強制復元する。
            if (cycle % 3 == 0) {
                try {
                    ClassIntegrityGuard.reassertIntegrity();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
