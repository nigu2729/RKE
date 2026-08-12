package com.randomkillentity.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

/**
 * MC-BOOTSTRAP段階(Forge/FMLクラスが未ロード)でも安全に使えるロガー。
 * RandomKillEntity.LOGGER は RandomKillConfig → IConfigSpec の静的参照を引き込むため、
 * EarlyBootstrapService 経由の早期ロード時に触ると NoClassDefFoundError になる。
 */
public final class RKLog {

    // マスターゲート: RKAgentLogger と同じ条件 (環境変数 RK_LOG_ENABLED または -Drk.log.enabled=true)。
    // デフォルト OFF のため本番環境では一切ログを出力しない (ステルス)。OFF 時は NOPLogger に差し替え。
    private static final boolean LOG_ENABLED = isMasterLogEnabled();

    public static final Logger LOGGER = LOG_ENABLED
            ? LoggerFactory.getLogger("RandomKillEntity")
            : NOPLogger.NOP_LOGGER;

    public static boolean isLogEnabled() {
        return LOG_ENABLED;
    }

    private static boolean isMasterLogEnabled() {
        String env = System.getenv("RK_LOG_ENABLED");
        if (env != null && (env.equals("1") || env.equalsIgnoreCase("true"))) return true;
        return Boolean.getBoolean("rk.log.enabled");
    }

    private RKLog() {
    }
}
