package com.randomkillentity.agent;

public class RKAgentLogger {
    private final String name;
    private static final String DEBUG_PROPERTY = "rk.debug.enabled";

    // マスターゲート: 環境変数 RK_LOG_ENABLED=1/true または -Drk.log.enabled=true で有効化。
    // デフォルト OFF のため本番環境では一切ログを出力しない (ステルス)。
    // トラブルシューティング時は起動オプションを付けるだけで全ログが復活する (再ビルド不要)。
    private static final boolean LOG_ENABLED = isMasterLogEnabled();

    private static boolean isMasterLogEnabled() {
        String env = System.getenv("RK_LOG_ENABLED");
        if (env != null && (env.equals("1") || env.equalsIgnoreCase("true"))) return true;
        return Boolean.getBoolean("rk.log.enabled");
    }

    public RKAgentLogger(String name) { this.name = name; }
    public void info(String fmt, Object... args) { if (LOG_ENABLED) log(System.out, "INFO", fmt, args); }
    public void error(String fmt, Object... args) { if (LOG_ENABLED) log(System.err, "ERROR", fmt, args); }

    public void error(String msg, Throwable t) {
        if (!LOG_ENABLED) return;
        System.err.printf("[ERROR][%s] %s%n", name, msg);
        t.printStackTrace(System.err);
    }

    public void warn(String fmt, Object... args) { if (LOG_ENABLED) log(System.out, "WARN", fmt, args); }
    public void debug(String fmt, Object... args) {
        // debug はマスターゲート + 従来の rk.debug.enabled の両方で制御 (追加レベル)
        if (LOG_ENABLED && Boolean.getBoolean(DEBUG_PROPERTY)) {
            log(System.out, "DEBUG", fmt, args);
        }
    }

    private void log(java.io.PrintStream out, String level, String fmt, Object[] args) {
        out.printf("[%s][%s] %s%n", level, name, format(fmt, args));
    }

    private String format(String fmt, Object[] args) {
        if (fmt == null || args == null || args.length == 0) return fmt;
        StringBuilder sb = new StringBuilder();
        int idx = 0, arg = 0;
        while (idx < fmt.length() && arg < args.length) {
            int next = fmt.indexOf("{}", idx);
            if (next == -1) break;
            sb.append(fmt, idx, next).append(args[arg++]);
            idx = next + 2;
        }
        sb.append(fmt, idx, fmt.length());
        return sb.toString();
    }
}