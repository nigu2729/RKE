package com.randomkillentity.utils;

import java.io.*;
import java.nio.file.*;

public class NativeLoader {

    private static volatile boolean loaded = false;

    /**
     * initializeNativeSweeper() が KillTargetRegistry を参照するための橋渡しフィールド。
     *
     * onLoad() 時点のクラスローダーコンテキスト (earlyboot CL) では
     * JNI FindClass("com/randomkillentity/agent/KillTargetRegistry") が失敗する。
     * agent JAR を system CL に追加した直後に Java 側でここへセットし、
     * C++ 側は FindClass の代わりに GetStaticObjectField でこのフィールドを読む。
     */
    public static volatile Class<?> agentKillTargetRegistry;

    public static boolean loadNativeLibrary() {
        if (loaded) return true;

        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            String libName;
            String resourcePath;

            if (osName.contains("win")) {
                libName = "random_kill_native.dll";
                resourcePath = "/META-INF/native/windows/" + libName;
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                libName = "librandom_kill_native.dylib";
                resourcePath = "/META-INF/native/macos/" + libName;
            } else if (osName.contains("nix") || osName.contains("nux")) {
                libName = "librandom_kill_native.so";
                resourcePath = "/META-INF/native/linux/" + libName;
            } else {
                RKLog.LOGGER.error("[NativeLoader] Unsupported OS: " + osName);
                return false;
            }

            // リソースからネイティブライブラリを展開
            InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                RKLog.LOGGER.error("[NativeLoader] Native library not found: " + resourcePath);
                return false;
            }

            File tempFile = File.createTempFile("random_kill_native", getLibExtension(osName));
            tempFile.deleteOnExit();

            try (FileOutputStream os = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }

            // 🔥 Windowsの場合、jvm.dllを明示的にロード
            if (osName.contains("win")) {
                loadJvmDll();
            }

            // ネイティブライブラリをロード
            System.load(tempFile.getAbsolutePath());

            loaded = true;
            RKLog.LOGGER.info("[NativeLoader] Native library loaded: " + tempFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            RKLog.LOGGER.error("[NativeLoader] Failed to load native library", e);
            return false;
        }
    }

    /**
     * 🔥 jvm.dllを明示的にロードして依存関係エラーを解決
     */
    private static void loadJvmDll() {
        try {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null) {
                RKLog.LOGGER.warn("[NativeLoader] java.home is null");
                return;
            }

            // jvm.dllのパスを探索
            File[] searchPaths = {
                    new File(javaHome, "bin/server/jvm.dll"),
                    new File(javaHome, "bin/client/jvm.dll"),
                    new File(javaHome, "jre/bin/server/jvm.dll"),
                    new File(javaHome, "jre/bin/client/jvm.dll"),
                    new File(javaHome, "../bin/server/jvm.dll"),
                    new File(javaHome, "../bin/client/random_kill_native.dll")
            };

            for (File jvmDll : searchPaths) {
                if (jvmDll.exists()) {
                    try {
                        System.load(jvmDll.getAbsolutePath());
                        RKLog.LOGGER.info("[NativeLoader] Loaded jvm.dll from: " + jvmDll.getAbsolutePath());
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        RKLog.LOGGER.debug("[NativeLoader] Failed to load jvm.dll from: " + jvmDll.getAbsolutePath());
                    }
                }
            }

            RKLog.LOGGER.warn("[NativeLoader] jvm.dll not found in standard locations");

        } catch (Exception e) {
            RKLog.LOGGER.warn("[NativeLoader] Could not load jvm.dll", e);
        }
    }

    public static native boolean initializeNativeAgent();

    /**
     * スイープ層のみの軽量初期化 (Java Agent 経路用)。
     * ClassFileLoadHook は登録しない (Instrumentation が変換を担当するため二重変換を防ぐ)。
     * JVMTI env と KillTargetRegistry.isMarkedEntity のキャッシュのみ行い、
     * {@link #sweepEntitiesAndKill()} を機能させる。
     */
    public static native boolean initializeNativeSweeper();

    // ── 設計v11: 防御mod中和層 ──

    /**
     * Java Agent 経路用: premain/agentmain 中和用の ClassFileLoadHook を
     * 中和専用モードで登録する。起動後にロードされる競合エージェントの
     * agentmain/premain を無力化し、ネイティブ防御初期化を阻害する。
     */
    public static native boolean initializeAgentNeutralizer();

    /**
     * 競合防御mod の JVMTI breakpoint / field modification watch を解除する。
     * 返り値は解除に成功した watch/breakpoint の総数。
     */
    public static native int neutralizeRivalDefenses();

    /**
     * 事前に {@code System.setProperty("rk.agent.jar", <agent jarの絶対パス>)} が必要。
     */
    public static native boolean forceLaunchJavaAgent();

    // ── 設計v2 項目11: ネイティブ執行層 (最終執行者) ──
    // Java リフレクションを一切使わず JNI 直呼びでフィールドを書き換える。
    // 防御mod が Java 側のリフレクション/Unsafe を妨害してもブロック不能。

    public static native boolean forceWriteFloatField(Object target, String fieldName, float value);

    public static native boolean forceWriteIntField(Object target, String fieldName, int value);

    /**
     * JVMTI ヒープ全域スイープ: LivingEntity の全インスタンスをタグ付け → 対象 UUID 照合 → 直書き + kill() 直呼び。
     *
     * @return 殺害したエンティティ数
     */
    public static native int sweepEntitiesAndKill();

    private static String getLibExtension(String osName) {
        if (osName.contains("win")) return ".dll";
        if (osName.contains("mac") || osName.contains("darwin")) return ".dylib";
        return ".so";
    }
}