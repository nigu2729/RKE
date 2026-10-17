package com.randomkillentity.agent.hook;

/**
 * LivingEntity メソッドへの注入フック契約。
 *
 * <p>設計v2「RK真・即死」: RETURN再注入 (キャンセル不能)。
 * 防御modが HEAD でキャンセルしても、RETURN フックが vanilla の戻り値を
 * 上書きし直すため、キャンセル自体が無効化される。</p>
 *
 * <p>phase 引数には {@code "RETURN"} 等の定数文字列が入る
 * (Enum参照によるModクラス依存を切断するため String にしている)。</p>
 */
public interface ILivingEntityMethodHook {

    // ── 既存フック (読み取り系) ──
    float getHealth(float original, Object entity, Object phase);
    boolean isDeadOrDying(boolean original, Object entity, Object phase);
    boolean isAlive(boolean original, Object entity, Object phase);
    boolean isRemoved(boolean original, Object entity, Object phase);

    // ── 設計v2: キャンセル不能 RETURN 再注入 ──

    /**
     * hurt(DamageSource,float)Z の RETURN フック。
     * 対象ならば true を返し、damage が「実際に通った」ことにして
     * vanilla のダメージ処理 (armor/effects/health 減少) を強制継続させる。
     */
    boolean hurt(boolean original, Object entity, Object phase);

    /**
     * die(DamageSource)V の RETURN フック。
     * 実死亡チェーンの保証。対象ならば vanilla の死亡処理が
     * 完了するまで状態を確認し、必要ならレジストリ掃除を促す。
     */
    void die(Object entity, Object phase);

    /**
     * setHealth(F)V の RETURN フック。
     * 不死身化ブロック: 対象の health が 0 より大きい値に戻されていたら
     * 実体フィールドへ直接 0 を書き戻す (SyncedEntityData 非依存)。
     */
    void setHealth(Object entity, Object phase);

    /**
     * heal(F)V の RETURN フック。
     * 対象への回復を無効化する (health 実体フィールドへ 0 を書き戻す)。
     */
    void heal(Object entity, Object phase);

    /**
     * addEffect(MobEffectInstance)Z の RETURN フック。
     * 対象が不死身化に使うエフェクト (吸収/耐性/火炎耐性 etc.) を
     * 得るのを防ぐため false を返す。
     */
    boolean addEffect(boolean original, Object entity, Object phase);

    /**
     * handleDamageEvent(DamageSource)V の RETURN フック。
     * ダメージ後のイベント処理を保証する。
     */
    void handleDamageEvent(Object entity, Object phase);

    /**
     * checkTotemDeathProtection(DamageSource)Z の RETURN フック。
     * トーテム不死の発動を防ぐため false を返す。
     */
    boolean checkTotemDeathProtection(boolean original, Object entity, Object phase);

    /**
     * tick()V の RETURN フック (設計v5: プレイヤー殺害拡大 項目2)。
     * <p>毎 tick の終端で対象の health 実体フィールドへ 0 を書き戻す。
     * 防御mod が「毎 tick 体力復元」型で死亡を打ち消しても、
     * RETURN 再注入により復元直後に 0 へ戻されるため、永遠に生かし続けられない。
     * 対象判定は {@code rk.should_kill} ワールドフラグ + 除外設定のみ。</p>
     */
    void tick(Object entity, Object phase);
}
