package com.randomkillentity.event;

import com.randomkillentity.config.RandomKillConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * ワールド全体即死判定 (設計v3)。
 *
 * <p>設計v2 までは「エンティティ単位」— 抽選ヒット時にランダム1体の UUID を
 * KillTargetRegistry へ登録し、KillDaemon が UUID 照合で個別殺害していた。</p>
 *
 * <p>設計v3 では「ワールド全体判定」— サーバーティック毎に確率ロールし、
 * ヒットしたらワールド全体フラグ {@code rk.should_kill=true} を立てる。
 * フラグON中は全ワールドの全 LivingEntity (プレイヤー含む) が即死対象となる。
 * 掃討完了後に KillDaemon がフラグを OFF する (次の抽選まで新スポーンは生存)。</p>
 */
public class GlobalKillFlag {

    /** ワールド全体即死フラグのシステムプロパティキー */
    public static final String WORLD_KILL_FLAG = "rk.should_kill";

    /**
     * フラグ自動OFFの安全網: KillDaemon がクラッシュ等でフラグを戻せない場合でも、
     * このティック数 (20TPS × 10秒 = 200) 経過で強制的に OFF にする。
     * フラグが true のまま残るとリスポーン即死が永続化する事故を防ぐ。
     */
    private static final int AUTO_RESET_AFTER_TICKS = 200;

    private static final Random random = new Random();
    private static int flagOnTicks = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // ⭕ 安全のため「SERVER側」かつ「STARTフェーズ」の時のみ判定する
        if (event.side.isServer() && event.phase == TickEvent.Phase.START) {
            boolean flagOn = Boolean.parseBoolean(System.getProperty(WORLD_KILL_FLAG, "false"));
            if (flagOn) {
                flagOnTicks++;
                if (flagOnTicks >= AUTO_RESET_AFTER_TICKS) {
                    System.setProperty(WORLD_KILL_FLAG, "false");
                    flagOnTicks = 0;
                }
            } else {
                flagOnTicks = 0;
            }

            double prob = RandomKillConfig.getKillProbability();

            boolean roll = false;
            double rollValue = Double.NaN;
            if (prob > 0.0) {
                if (prob >= 1.0) {
                    roll = true;
                } else {
                    rollValue = random.nextDouble();
                    roll = rollValue < prob;
                }
            }

            if (roll) {
                // ⭕ 設計v3: ワールド全体判定。
                // エンティティ単位の UUID 登録は行わず、ワールド全体フラグを立てる。
                // KillDaemon が全ワールドの全 LivingEntity へ即死を適用し、掃討後に OFF する。
                System.setProperty(WORLD_KILL_FLAG, "true");
                flagOnTicks = 0;
            }
        }
    }
}
