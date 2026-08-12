package com.randomkillentity.agent;

import com.randomkillentity.agent.hook.LivingEntityMethodImpl;
import com.randomkillentity.utils.UnsafeUtils;

/**
 * フォールバック独自死亡処理 (設計v5: 上位即死の最終保険)。
 *
 * <p>vanilla の死亡チェーン (die / setRemoved / 構造除去) が防御mod の mixin
 * で完全に塞がれた場合でも、エンティティの「死亡状態」を Unsafe 直書きで
 * 強制的に確定させる。フック注入に依存しないため、フック除去検知後の
 * 再適用待ちの間も機能する。</p>
 *
 * <p>MC クラスへの直接参照は持たない (agent は隔離クラスローダー)。
 * フィールド名は SRG 名 (f_XXXXX_) と通常名の両対応 (既存方針と同一)。</p>
 */
public final class FallbackDeathHandler {

    /** LivingEntity.dead のフィールド名候補 (tsrg 120934行で f_20890_ 実証済み) */
    private static final String[] DEAD_FIELDS = {"f_20890_", "dead"};
    /** LivingEntity.health のフィールド名候補 */
    private static final String[] HEALTH_FIELDS = {"f_20963_", "health"};
    /** Entity.removed のフィールド名候補 (tsrg 未マッピング = SRG/通常名とも "removed") */
    private static final String[] REMOVED_FIELDS = {"removed"};

    private FallbackDeathHandler() {
    }

    /**
     * 対象の「死亡状態」が確定しているか (dead フィールドを Unsafe 直読み)。
     * KillDaemon の最終保険判定に使う。
     */
    public static boolean isDeadFlagged(Object entity) {
        if (entity == null) return false;
        return UnsafeUtils.readBooleanField(entity, DEAD_FIELDS);
    }

    /**
     * 死亡状態を Unsafe 直書きで強制する。
     * dead=true + health=0 + removed=true を実体フィールドへ直接書き込み、
     * その後ワールド管理構造からの除去 (purgeFromWorld) に委譲する。
     */
    public static void forceDeath(Object entity) {
        if (entity == null) return;
        UnsafeUtils.writeBooleanField(entity, true, DEAD_FIELDS);
        UnsafeUtils.writeFloatField(entity, 0.0f, HEALTH_FIELDS);
        UnsafeUtils.writeBooleanField(entity, true, REMOVED_FIELDS);
        boolean structural = !LivingEntityMethodImpl.isPlayer(entity);
        WorldStructureDestroyer.purgeFromWorld(entity, structural);
    }
}
