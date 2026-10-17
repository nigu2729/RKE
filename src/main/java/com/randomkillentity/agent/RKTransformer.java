package com.randomkillentity.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class RKTransformer implements ClassFileTransformer {
    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Transformer");

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return null;

        // 設計v12: バイト巻き戻し方式は廃止 (正当な再直列化まで破壊し、サブクラスの
        // リンケージを壊した — IncompatibleClassChangeError 実機実証)。
        // 代わりに「フック再注入」方式: 敵対改変が当方フックを剥奪した場合でも、
        // DefenseDisarmService の周期 retransform (ClassIntegrityGuard.reassertIntegrity)
        // がこのメソッドを再実行させ、全トランスフォーマー (冪等化済み) がフックを
        // 再適用する。バイト差し替えには干渉せず、自らの能力だけを常に再主張する。
        boolean pipeline = isTargetClass(className)
                || containsAscii(classfileBuffer, "isThisName")
                || com.randomkillentity.agent.transformer.DefenseNeutralizerTransformer
                        .isDefenseCandidate(classfileBuffer);
        return pipeline ? TransformerCore.transformForByte(className, classfileBuffer) : null;
    }

    /** フック注入対象クラス (slash 形式)。設計v3: プレイヤー系も含む。設計v6: ServerLevel 追加。 */
    private static boolean isTargetClass(String className) {
        return "net/minecraft/world/entity/LivingEntity".equals(className)
                || "net/minecraft/world/entity/player/Player".equals(className)
                || "net/minecraft/server/level/ServerPlayer".equals(className)
                || "net/minecraft/client/player/LocalPlayer".equals(className)
                || "net/minecraft/server/level/ServerLevel".equals(className);
    }

    /**
     * クラスファイルバイト配列内に ASCII 文字列が含まれるかを単純走査で判定する。
     * 定数プールの UTF-8 エントリに "isThisName" があれば、スタック検査を行う
     * 第三者クラスとみなし詳細変換対象とする (クラス名ハードコード回避)。
     * O(n*m) だがクラスロード時のみ実行され、定数プールは小さいため無視できる。
     */
    private static boolean containsAscii(byte[] bytes, String needle) {
        if (bytes == null || needle == null || needle.isEmpty()) return false;
        byte[] needleBytes = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int n = bytes.length;
        int m = needleBytes.length;
        if (m > n) return false;
        for (int i = 0; i <= n - m; i++) {
            boolean match = true;
            for (int j = 0; j < m; j++) {
                if (bytes[i + j] != needleBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader cr, int flags, ClassLoader loader) { super(cr, flags); }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception e) {
                LOGGER.warn("Failed to get common superclass for " + type1 + " and " + type2 + ", defaulting to Object");
                return "java/lang/Object";
            }
        }
    }
}
