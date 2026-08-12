package com.randomkillentity.service;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import com.randomkillentity.utils.RKAgentLoader;
import com.randomkillentity.utils.WatcherLauncher;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class EarlyBootstrapService implements ITransformationService {

    private static boolean agentLoaded = false;

    // マスターゲート: RKAgentLogger と同じ条件 (環境変数 RK_LOG_ENABLED または -Drk.log.enabled=true)。
    // このソースセットは RKAgentLogger に依存できないため自前で判定する。
    private static final boolean LOG_ENABLED = isMasterLogEnabled();

    private static boolean isMasterLogEnabled() {
        String env = System.getenv("RK_LOG_ENABLED");
        if (env != null && (env.equals("1") || env.equalsIgnoreCase("true"))) return true;
        return Boolean.getBoolean("rk.log.enabled");
    }

    // 最早期の前段階起動。失敗しても onLoad / initialize が再試行するため安全
    // (tryLoadAgent が Throwable まで捕捉するので ExceptionInInitializerError でブートは止まらない)
    static {
        tryLoadAgent("static initializer");
    }

    @Override
    public String name() {
        return "randomkill_early_bootstrap";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        // 🔥 onLoadのタイミングなら、ModLauncherの基本的な準備が整っている
        if (!agentLoaded) {
            tryLoadAgent("onLoad");
        }
    }

    @Override
    public void initialize(IEnvironment environment) {
        if (!agentLoaded) {
            tryLoadAgent("initialize");
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    private static void tryLoadAgent(String phase) {
        if (LOG_ENABLED) System.out.println("[RK] Attempting agent load in " + phase);
        try {
            if (RKAgentLoader.load()) {
                agentLoaded = true;
                if (LOG_ENABLED) System.out.println("[RK] Agent loaded successfully in " + phase);
            }
            // 経路B: 常駐ウォッチャー (ヘルパーJVM方式) を起動。
            // 自己アタッチ (経路A) の成否に関わらず毎起動時に起動し、
            // 以後の javaw.exe 起動 (ゲーム再起動等) を外部から強制ロードする。
            // agentmain は二重実行ガードを持つため、自身への即時 attach と二重ロードにならない。
            WatcherLauncher.launch();
        } catch (Throwable t) {
            // Error (UnsatisfiedLinkError 等) も捕捉してブートを止めない。
            // 次のフェーズ (onLoad / initialize) が再試行する。
            if (LOG_ENABLED) System.err.println("[RK] Failed in " + phase + ": " + t);
        }
    }
}