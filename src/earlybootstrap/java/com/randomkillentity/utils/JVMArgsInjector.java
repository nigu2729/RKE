package com.randomkillentity.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class JVMArgsInjector {

    private static volatile boolean injected = false;

    /**
     * user_jvm_args.txt を毎起動時に書き換える。
     *
     * 1. ネイティブDLLを永続パス (gameDir/.randomkill/) に展開 (次回起動の -agentpath 用。
     *    NativeLoader の tempFile は deleteOnExit で消えるため、ここで別途永続化する)
     * 2. 行を並べ替え: 自 -agentpath → 他 -agentpath → -javaagent → その他
     *    (自 -agentpath を無条件で先頭に置き、他 Agent より先に Agent_OnLoad を発火させる)
     * 3. --add-modules jdk.attach 等が無ければ末尾に追記
     */
    public static boolean inject() {
        if (injected) return true;

        try {
            String gameDir = System.getProperty("user.dir");
            if (gameDir == null) {
                gameDir = ".";
            }
            Path instanceDir = Paths.get(gameDir);

            // 1. ネイティブDLLを永続パスに展開
            Path nativeDir = instanceDir.resolve(".randomkill");
            Files.createDirectories(nativeDir);
            Path nativeLib = nativeDir.resolve(getNativeLibFileName());
            boolean dllReady = extractNativeLibrary(nativeLib);

            // 2. 既存のJVM引数を行ごとに読み込み、分類する
            List<String> lines = new ArrayList<>();
            if (Files.exists(instanceDir.resolve("user_jvm_args.txt"))) {
                for (String line : Files.readAllLines(instanceDir.resolve("user_jvm_args.txt"))) {
                    if (!line.trim().isEmpty()) {
                        lines.add(line);
                    }
                }
            }

            String agentPathLine = "-agentpath:" + nativeLib.toAbsolutePath();
            lines.removeIf(l -> l.trim().equals(agentPathLine)); // 重複防止

            List<String> agentPathLines = new ArrayList<>();
            List<String> javaAgentLines = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("-agentpath:")) {
                    agentPathLines.add(line);
                } else if (t.startsWith("-javaagent:")) {
                    javaAgentLines.add(line);
                } else {
                    rest.add(line);
                }
            }

            // DLLが無いのに -agentpath を書くと JVM が起動不能になるため、存在時のみ先頭に置く
            List<String> ordered = new ArrayList<>();
            if (dllReady) {
                ordered.add(agentPathLine);
            }
            ordered.addAll(agentPathLines);
            ordered.addAll(javaAgentLines);
            ordered.addAll(rest);

            // 3. 必須JVM引数を確認し、無ければ追記
            String requiredArgs = "--add-modules jdk.attach --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED";

            String joined = String.join("\n", ordered);
            if (!joined.contains("--add-modules jdk.attach")) {
                if (!joined.isEmpty()) {
                    joined += "\n";
                }
                joined += requiredArgs;
            }

            Path jvmArgsFile = instanceDir.resolve("user_jvm_args.txt");
            Files.writeString(jvmArgsFile, joined + "\n");

            injected = true;
            RKLog.LOGGER.info("[RK] Injected JVM args. -agentpath line: " + (dllReady ? agentPathLine : "(dll not ready)"));
            return true;

        } catch (Exception e) {
            RKLog.LOGGER.error("[RK] Failed to inject JVM args", e);
            return false;
        }
    }

    /**
     * Mod JAR 内のネイティブライブラリを永続パスへ展開する。
     * 実行中の JVM が DLL をロード済みの場合は上書きに失敗するため、その場合は既存ファイルを流用する。
     */
    private static boolean extractNativeLibrary(Path dest) {
        String osName = System.getProperty("os.name").toLowerCase();
        String resourcePath;
        if (osName.contains("win")) {
            resourcePath = "/META-INF/native/windows/random_kill_native.dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            resourcePath = "/META-INF/native/macos/librandom_kill_native.dylib";
        } else if (osName.contains("nix") || osName.contains("nux")) {
            resourcePath = "/META-INF/native/linux/librandom_kill_native.so";
        } else {
            return false;
        }

        try (InputStream is = JVMArgsInjector.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return false;
            }
            Files.createDirectories(dest.getParent());
            try {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (AccessDeniedException e) {
                return Files.exists(dest);
            }
            return Files.exists(dest);
        } catch (IOException e) {
            RKLog.LOGGER.warn("[RK] Failed to extract native library", e);
            return false;
        }
    }

    private static String getNativeLibFileName() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) return "random_kill_native.dll";
        if (osName.contains("mac") || osName.contains("darwin")) return "librandom_kill_native.dylib";
        return "librandom_kill_native.so";
    }

    /**
     * jdk.attachモジュールが有効かどうかをチェック
     */
    public static boolean isJdkAttachAvailable() {
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
