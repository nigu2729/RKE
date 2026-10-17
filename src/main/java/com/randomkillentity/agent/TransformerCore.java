package com.randomkillentity.agent;

import com.randomkillentity.agent.transformer.EntityInlineKillTransformer;
import com.randomkillentity.agent.transformer.ITransformerModule;
import com.randomkillentity.agent.transformer.LivingEntityTransformer;
import com.randomkillentity.agent.transformer.SelfClassifierTransformer;
import com.randomkillentity.agent.transformer.ServerLevelTransformer;
import com.randomkillentity.agent.transformer.DefenseNeutralizerTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;

public class TransformerCore {

    public static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Core");

    private static final List<ITransformerModule> TRANSFORMERS = Arrays.asList(
            // 設計v7: エンティティ単位のインラインキル (メソッド文字列判定)。
            // LivingEntity.tick HEAD に setRemoved(KILLED)+kill() を直接焼き込み、
            // mod フレームを積まずに対象エンティティを確定的に除去する。
            new EntityInlineKillTransformer(),
            // 設計v8 柱1: 自クラス化 (構造的 isThisName*MOD 注入)。
            // スタック検査メソッドに自パッケージ判定を注入し、全スタック検査を
            // 素通しさせる。
            new SelfClassifierTransformer(),
            new LivingEntityTransformer(),
            new com.randomkillentity.agent.transformer.EntitySetPosGuardTransformer(),
            new com.randomkillentity.agent.transformer.EntitySetRemovedGuardTransformer(),
            new com.randomkillentity.agent.transformer.EntityLevelCallbackGuardTransformer(),
            new com.randomkillentity.agent.transformer.LevelAddFreshEntityTransformer(),
            new com.randomkillentity.agent.transformer.AddEntityBlockerTransformer(),
            // 設計v6: スタック検査対策。ServerLevel に合成メソッド rkKillSweep を
            // 注入し tick HEAD から呼ぶことで、キル呼び出し元を全てバニラフレームにする。
            new ServerLevelTransformer(),
            // 設計v11: 防御mod中和。第三者のガード/復元メソッドを構造 (命名) のみで
            // 判定して no-op 化し、注入済みフックを無力化する (特定 mod 非依存)。
            new DefenseNeutralizerTransformer()
    );

    // ⭕ 再入（無限再帰）を防ぐためのスレッドローカルなガードフラグ
    private static final ThreadLocal<Boolean> isTransforming = ThreadLocal.withInitial(() -> false);

    public static byte[] transformForByte(String className, byte[] classBytes) {
        if (classBytes == null || className == null) return null;

        // ⭕ 自身が起こしたクラスロードによるフックであればスルーする
        if (isTransforming.get()) {
            return null;
        }

        try {
            isTransforming.set(true); // ガード開始

            // スラッシュをドットに変換
            String dotClassName = className.replace('/', '.');

            // 🚨 ログ出力は、対象クラスの判定を「通過する可能性がある場合」のみに限定する
            // もしくは、トラブルシューティング時以外は完全にコメントアウトする
            if (className.contains("LivingEntity")) {
                LOGGER.info("[RK-NATIVE-HOOK] Hit target class: " + className);
            }

            boolean anyModified = false;
            byte[] currentBytes = classBytes;

            for (ITransformerModule transformer : TRANSFORMERS) {
                try {
                    // クラス名がマッチするか先にチェック
                    if (transformer.matchesClass(dotClassName) || transformer.matchesClass(className)) {
                        byte[] result = applyTransformer(currentBytes, transformer, dotClassName);
                        if (result != currentBytes) {
                            currentBytes = result;
                            anyModified = true;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Transformer failed: " + transformer.getClass().getSimpleName(), e);
                }
            }

            return anyModified ? currentBytes : classBytes;

        } finally {
            isTransforming.set(false); // ガード解除
        }
    }

    private static byte[] applyTransformer(byte[] classBytes, ITransformerModule transformer, String className) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode(Opcodes.ASM9);
            cr.accept(cn, ClassReader.EXPAND_FRAMES);

            if (!transformer.matchesClass(className)) return classBytes;

            boolean modified = false;
            if (cn.methods != null) {
                for (MethodNode mn : cn.methods) {
                    if (transformer.matchesMethod(className, mn)) {
                        if (transformer.transform(Phase.TRANSFORM, cn, mn)) {
                            modified = true;
                        }
                    }
                }
            }

            if (!modified) return classBytes;

            // SafeClassWriter: COMPUTE_FRAMES 時に getCommonSuperClass が失敗しても
            // Object へフォールバックして VerifyError を防ぐ (ServerLevel.tick は巨大で
            // MC 型同士の merge が発生するため必須)。
            ClassWriter cw = new RKTransformer.SafeClassWriter(
                    cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, null);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            LOGGER.error("applyTransformer failed for " + className, e);
            return classBytes;
        }
    }

    public enum Phase {
        TRANSFORM
    }
}