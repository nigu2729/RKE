package com.randomkillentity.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.*;
import java.util.jar.JarFile;

import com.randomkillentity.utils.NativeLoader;

public class RKAgent {
    public static RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Agent");
    public static final boolean DEBUG_ENABLED = Boolean.getBoolean("rk.debug.enabled");
    /** 登録済みトランスフォーマー (CountermeasureService が再適用するために保持) */
    public static volatile RKTransformer INSTALLED_TRANSFORMER;
    /** agentmain の二重実行ガード (自己アタッチとウォッチャーが両方走っても一度だけ初期化する) */
    private static final java.util.concurrent.atomic.AtomicBoolean AGENT_ENTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void agentmain(String args, Instrumentation inst) {
        if (!AGENT_ENTERED.compareAndSet(false, true)) {
            LOGGER.debug("agentmain already executed, skip (double-load guard)");
            return;
        }
        LOGGER.debug("!!! Agent_agentmain CALLED !!!");
        // 🔥 設計v2: レジストリスイープ用に Instrumentation を保持
        KillTargetRegistry.setInstrumentation(inst);
        String agentJarPath = getAgentJarPath();
        try {
            if (agentJarPath != null) {
                // 🔥 Bug A 修正: bootstrap への append は廃止。
                // 以前は agent JAR を bootstrap classloader に追加していたため、agent クラスが
                // bootstrap からロードされ、ASM/utils が見えず NoClassDefFoundError が発生した。
                // 現在は agent JAR に ASM + utils を同梱し、システムクラスローダ経由で解決する
                // (Dynamic Attach 時、JVM が自動で agent JAR をシステムクラスローダに追加する)。
                inst.appendToSystemClassLoaderSearch(new JarFile(agentJarPath));
                LOGGER.info("Appended agent JAR to system classloader: " + agentJarPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to append agent JAR", e);
        }

        ensureHookClassesLoaded();

        // 🔥 Bug B 修正: ネイティブ再バインド。
        // utils は earlyboot JAR と agent JAR の両方に同梱されており、NativeLoader は
        // クラスローダーごとに別クラスになる。RKAgentLoader が早期ロード時に実行した
        // System.load は earlyboot CL にしか登録されないため、agent JAR (システムCL) 側の
        // NativeLoader が native メソッド (sweepEntitiesAndKill / forceWrite*) を呼ぶと
        // UnsatisfiedLinkError になる。ここで agent JAR 内の DLL を新しい一時ファイルへ
        // 抽出して System.load し直し、システムCL にも登録する。DLL は別インスタンスとして
        // ロードされるため、RKAgentLoader と同じ順序 (load → ブリッジ → sweeper 初期化) で
        // ネイティブ側の状態も初期化する。
        try {
            if (NativeLoader.loadNativeLibrary()) {
                NativeLoader.agentKillTargetRegistry = KillTargetRegistry.class;
                NativeLoader.initializeNativeSweeper();
                LOGGER.info("Native library re-bound for system classloader");
            } else {
                LOGGER.error("Native library re-bind failed");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to re-bind native library", t);
        }

        LOGGER.info("Installing transformer");
        init(inst);

        // 設計v2 項目3: 5ms 常駐デーモン起動 (防御mod が health を書き戻しても再殺)
        KillDaemon.start();

        // 起動完了マーカー: launcher 側 (RKAgentLoader) が attach レスポンスの解析に
        // 失敗しても (例: レスポンスパイプのハンドシェイク不調)、agentmain が実行済み
        // であることを検知できるようにする。これがないと loader が誤ってフォールバック
        // 経路 (ネイティブ agent) を試み、警告ログと再アタッチ試行が連発する。
        System.setProperty("rk.agent.loaded", "true");
    }

    private static void init(Instrumentation inst) {
        try {
            ClassLoader cl = RKAgent.class.getClassLoader();
            Class<?> transformerClass = Class.forName("com.randomkillentity.agent.RKTransformer", true, cl);
            ClassFileTransformer transformer = (ClassFileTransformer) transformerClass.getDeclaredConstructor().newInstance();
            INSTALLED_TRANSFORMER = (RKTransformer) transformer;
            inst.addTransformer(transformer, true);
            LOGGER.info("RKTransformer registered successfully.");

            retransformClassTransformer(inst);
        } catch (Throwable e) {  // Error (VerifyError 等) も捕捉して agentmain クラッシュを防ぐ
            LOGGER.error("Failed to initialize transformer", e);
        }
    }

    private static void ensureHookClassesLoaded() {
        ClassLoader loader = RKAgent.class.getClassLoader();
        String[] classes = {
                "com.randomkillentity.agent.TransformerCore",
                "com.randomkillentity.agent.KillTargetRegistry",
                "com.randomkillentity.agent.KillDaemon",
                "com.randomkillentity.agent.StateDestroyer",
                "com.randomkillentity.agent.FallbackDeathHandler",
                "com.randomkillentity.agent.CountermeasureService",
                "com.randomkillentity.agent.ClientEraser",
                "com.randomkillentity.agent.ServerLevelSweepHelper",
                "com.randomkillentity.agent.hook.ILivingEntityMethodHook",
                "com.randomkillentity.agent.hook.LivingEntityMethodImpl"
        };
        for (String className : classes) {
            try {
                Class.forName(className, true, loader);
                LOGGER.info("Successfully loaded: " + className);
            } catch (ClassNotFoundException e) {
                LOGGER.error("Failed to load: " + className, e);
            }
        }
    }

    private static void retransformClassTransformer(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if ("cpw.mods.cl.ModuleClassLoader".equals(c.getName())) {
                LOGGER.info("Found ClassTransformer: " + c.getName());

                if (inst.isModifiableClass(c)) {
                    LOGGER.info("Retransforming: " + c.getName());
                    try {
                        inst.retransformClasses(c);
                    } catch (Exception e) {
                        LOGGER.error("Retransform failed", e);
                    }
                } else {
                    LOGGER.warn("ClassTransformer is not modifiable");
                }
                break;
            }
        }
    }

    private static String getAgentJarPath() {
        String path = System.getProperty("rk.agent.jar");
        if (path != null && new File(path).exists()) return path;
        return null;
    }
}