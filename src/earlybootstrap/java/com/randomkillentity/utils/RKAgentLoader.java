package com.randomkillentity.utils;

import java.io.*;
import java.lang.reflect.Method;

public class RKAgentLoader {

    private static volatile boolean loaded = false;

    public static boolean load() {
        if (loaded) return true;

        // 1. Java Agent を試す
        if (loadJavaAgent()) {
            loaded = true;
            return true;
        }

        // 2. Java Agent が失敗した場合、ネイティブエージェントを試す
        RKLog.LOGGER.warn("[RK] Java Agent failed, trying native agent...");
        if (loadNativeAgent()) {
            loaded = true;
            return true;
        }

        RKLog.LOGGER.error("[RK] Both Java Agent and Native Agent failed");
        return false;
    }

    private static boolean loadJavaAgent() {
        try {
            // 🔥 Java Agentを起動するためのネイティブDLLをロード (セルフアタッチ方式)
            if (!NativeLoader.loadNativeLibrary()) {
                RKLog.LOGGER.error("[RK] Failed to load native library for self-attach");
                return false;
            }

            File agentJar = extractAgent();
            if (agentJar == null) {
                RKLog.LOGGER.error("[RK] Failed to extract embedded agent");
                return false;
            }
            agentJar.deleteOnExit();

            System.setProperty("rk.agent.jar", agentJar.getAbsolutePath());

            String pid = getProcessId();
            RKLog.LOGGER.info("[RK] Force-launching Java Agent via native self-attach (PID: " + pid + ")");

            // 🔥 jdk.attach (VirtualMachine) を使わず、C++ が JVM の Dynamic Attach 機構に直接アクセス
            boolean success = NativeLoader.forceLaunchJavaAgent();
            if (success) {
                RKLog.LOGGER.info("[RK] Java Agent loaded successfully");

                // initializeNativeSweeper() を onLoad() から呼ぶために必要な前処理。
                // agent JAR が appendToSystemClassLoaderSearch() で system CL に追加された直後なので、
                // system CL 経由で KillTargetRegistry を解決できる。
                // C++ の FindClass は earlyboot CL を使うため直接解決できないが、
                // NativeLoader.agentKillTargetRegistry フィールドに渡しておけば
                // C++ 側が GetStaticObjectField で読める。
                try {
                    NativeLoader.agentKillTargetRegistry =
                            ClassLoader.getSystemClassLoader()
                                       .loadClass("com.randomkillentity.agent.KillTargetRegistry");
                    RKLog.LOGGER.info("[RK] KillTargetRegistry pre-loaded into bridge field");
                } catch (ClassNotFoundException e) {
                    RKLog.LOGGER.warn("[RK] Could not pre-load KillTargetRegistry: " + e.getMessage());
                }

                NativeLoader.initializeNativeSweeper();
                return true;
            }

            RKLog.LOGGER.error("[RK] Native self-attach failed");
            return false;

        } catch (Throwable t) {
            RKLog.LOGGER.error("[RK] Failed to load Java Agent", t);
            return false;
        }
    }

    private static String getProcessId() {
        try {
            Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
            Method currentMethod = processHandleClass.getMethod("current");
            Object currentProcess = currentMethod.invoke(null);
            Method pidMethod = processHandleClass.getMethod("pid");
            long pid = (long) pidMethod.invoke(currentProcess);
            return String.valueOf(pid);
        } catch (Exception e) {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        }
    }

    private static boolean loadNativeAgent() {
        try {
            if (!NativeLoader.loadNativeLibrary()) {
                return false;
            }

            // 🔥 C++に渡す前に、TransformerCoreクラスを現在のクラスローダーで強制的にロードしておく
            try {
                Class.forName("com.randomkillentity.agent.TransformerCore");
                RKLog.LOGGER.info("[RK] TransformerCore pre-loaded successfully.");
            } catch (ClassNotFoundException e) {
                RKLog.LOGGER.warn("[RK] TransformerCore not found before native init!");
            }

            boolean success = NativeLoader.initializeNativeAgent();
            if (success) {
                RKLog.LOGGER.info("[RK] Native Agent loaded successfully");
                // ネイティブ経路では agentmain → KillDaemon.start() が呼ばれないため、
                // フラグ (rk.should_kill) を OFF に戻すデーモンをここで起動する。
                // 起動しないとフラグが true のまま残り、リスポーン即死が続く。
                startKillDaemon();
            } else {
                RKLog.LOGGER.error("[RK] Failed to initialize native agent");
            }
            return success;
        } catch (Throwable t) {
            RKLog.LOGGER.error("[RK] Failed to load native agent", t);
            return false;
        }
    }

    private static void startKillDaemon() {
        try {
            Class.forName("com.randomkillentity.agent.KillDaemon")
                    .getMethod("start").invoke(null);
            RKLog.LOGGER.info("[RK] KillDaemon started (native path)");
        } catch (Throwable t) {
            RKLog.LOGGER.error("[RK] Failed to start KillDaemon", t);
        }
    }

    private static File extractAgent() throws IOException {
        InputStream is = RKAgentLoader.class.getResourceAsStream("/META-INF/jarjar/randomkill-agent.jar");
        if (is == null) {
            RKLog.LOGGER.error("[RK] Agent JAR not found in resources");
            return null;
        }
        File tmp = File.createTempFile("randomkill-agent", ".jar");
        tmp.deleteOnExit();
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            is.transferTo(os);
        }
        RKLog.LOGGER.info("[RK] Extracted agent to: " + tmp.getAbsolutePath());
        return tmp;
    }

    public static boolean isLoaded() {
        return loaded;
    }
}