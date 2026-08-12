package com.randomkillentity.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 常駐ウォッチャー EXE (random_kill_watcher.exe) を gameDir/.randomkill/ へ展開し、
 * バックグラウンドで起動する (経路B: ヘルパーJVM方式)。
 *
 * watcher は WMI (Win32_ProcessStartTrace) で javaw.exe の起動を監視し、
 * mods/ に本 mod (RandomKillEntity) があれば META-INF/jarjar/randomkill-agent.jar を
 * 抽出してヘルパーJVM (署名付き java.exe + VirtualMachine.attach) で強制ロードする。
 *
 * ログ制御は C++ 側と同じ引数方式 (環境変数 RK_LOG_ENABLED) をそのまま継承するため、
 * ここでは何も設定しない (子プロセスは親の環境変数を引き継ぐ)。
 * Windows のみ。他の OS では何もしない。
 */
public final class WatcherLauncher {

    private static volatile boolean launched = false;

    private WatcherLauncher() {
    }

    /**
     * 常駐 watcher を起動する。多重起動は EXE ロックファイルで防止する。
     *
     * @return 起動した/既に起動済みなら true。非 Windows または失敗なら false。
     */
    public static boolean launch() {
        if (launched) return true;

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("win")) {
            return false;
        }

        try {
            String gameDir = System.getProperty("user.dir");
            Path instanceDir = Paths.get(gameDir != null ? gameDir : ".");
            Path rkDir = instanceDir.resolve(".randomkill");
            Files.createDirectories(rkDir);

            Path watcherExe = rkDir.resolve("random_kill_watcher.exe");

            // 【修正】抽出する前に、まず既に実行中かどうかをチェックする
            if (isWatcherRunning()) {
                RKLog.LOGGER.info("[WatcherLauncher] Watcher already running, skip launch");
                launched = true;
                return true;
            }

            // 実行されていない場合のみファイルを抽出する
            if (!extractWatcher(watcherExe)) {
                RKLog.LOGGER.error("[WatcherLauncher] Failed to extract random_kill_watcher.exe");
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(watcherExe.toString(), "--dir", instanceDir.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();

            launched = true;
            RKLog.LOGGER.info("[WatcherLauncher] Watcher launched: " + watcherExe);
            return true;

        } catch (Exception e) {
            RKLog.LOGGER.error("[WatcherLauncher] Failed to launch watcher", e);
            return false;
        }
    }

    /**
     * Mod JAR 内の watcher EXE を gameDir/.randomkill/ へ展開する。
     * 実行中の EXE は上書きに失敗するため、その場合は既存ファイルを流用する。
     */
    private static boolean extractWatcher(Path dest) throws IOException {
        String resourcePath = "/META-INF/native/windows/random_kill_watcher.exe";
        try (InputStream is = WatcherLauncher.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return false;
            }
            try {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AccessDeniedException e) {
                return Files.exists(dest);
            }
            return Files.exists(dest);
        }
    }

    /**
     * 同一 EXE (gameDir/.randomkill/random_kill_watcher.exe) で起動中の watcher が
     * いるかどうかを判定する。
     */
    private static boolean isWatcherRunning() {
        try {
            String exePath = Paths.get(System.getProperty("user.dir"), ".randomkill", "random_kill_watcher.exe")
                    .toAbsolutePath().toString();
            return ProcessHandle.allProcesses().anyMatch(p -> {
                try {
                    return p.info().command().map(cmd -> {
                        String c = cmd.replace('/', '\\');
                        return c.equalsIgnoreCase(exePath);
                    }).orElse(false);
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isLaunched() {
        return launched;
    }
}
