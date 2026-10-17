package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * 設計v13: スタック検査の中和 (最上位の Java 層防御対策・汎用)。
 *
 * <p>競合防御 mod は「呼び出し元スタックに外部 mod のフレームが含まれるか」を
 * 実行時に検査し、含まれていれば自 mod の防御判定を ON にする (免疫化) ことが多い。
 * 典型形:</p>
 * <pre>
 *   static boolean isBadFrame() {           // スタック全域を走査する関門
 *       return WALKER.walk(f -&gt; f.anyMatch(x -&gt; !x.getClassName().startsWith(...)));
 *   }
 *   static boolean notCalledFromOtherMod(int start, int end) { ... isBadFrame() ... }
 * </pre>
 *
 * <p>本トランスフォーマは、第三者のクラスのうち「スタック検査 API を本体で直接
 * 呼び出している boolean 判定メソッド」だけを構造 (呼び出し先の所有者 + 戻り型) で
 * 検出し、本体を {@code return false} (= 不良フレーム無し) に置換する。関門となる
 * 最深部の判定が false に固定されるため、それを呼ぶ上位のラッパ (allowed / notCalledFrom
 * / checkDespawnImmunity 等) も連鎖的に「外部 mod フレーム無し」と評価する。</p>
 *
 * <p><b>検出対象のスタック検査 API (所有者のみで判定):</b>
 * {@code java/lang/StackWalker} (StackFrame 含む) / {@code sun/reflect/Reflection.getCallerClass}
 * / {@code java/lang/Thread.getStackTrace} / {@code java/lang/Throwable.getStackTrace}。
 * これらは本物のスタック走査であり、boolean を返すなら防御判定以外の用途はほぼ無い。</p>
 *
 * <p><b>汎用性 (専用対策の禁止):</b> 特定の mod 名・パッケージ名・クラス名を
 * 一切ハードコードしない。バニラ/Forge/ライブラリ/自 mod 以外のクラス
 * ({@link DefenseNeutralizerTransformer#isDefenseClass}) のみを対象とし、かつ
 * スタック検査 API を直接呼ぶ boolean メソッドに限定する。</p>
 *
 * <p>副作用は大きい (第三者 mod の「外部呼び出し検知」が常に false になる) が、
 * 到達範囲と突破力を優先する方針に沿う。</p>
 */
public class StackInspectionNeutralizerTransformer implements ITransformerModule {

    /**
     * クラスロード時に ASM 解析を行うかを安価に判定するための特徴トークン。
     * これらはスタック検査 API のシグネチャに現れるため、定数プール走査で
     * 「スタック検査を行う可能性のあるクラス」を素早く絞り込める。
     */
    private static final String[] CANDIDATE_TOKENS = {
            "StackWalker", "getCallerClass", "getStackTrace"
    };

    /** スタックフレーム系の所有者接頭辞 (StackWalker 本体 + 内部インタフェース StackFrame)。 */
    private static final String STACK_WALKER_PREFIX = "java/lang/StackWalker";

    /**
     * クラスファイルバイト列がスタック検査の候補か (定数プールの簡易走査)。
     * 特定の mod 名ではなく、スタック検査 API のトークンの有無だけで判定する。
     */
    public static boolean isStackInspectionCandidate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        for (String token : CANDIDATE_TOKENS) {
            if (containsAscii(bytes, token)) return true;
        }
        return false;
    }

    @Override
    public boolean matchesClass(String className) {
        return DefenseNeutralizerTransformer.isDefenseClass(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        if (!DefenseNeutralizerTransformer.isDefenseClass(className)) return false;
        if (method.name == null || method.name.equals("<init>") || method.name.equals("<clinit>")) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        // boolean を返す判定メソッドのみ (void ログ・参照返しのユーティリティは対象外)
        if (method.desc == null || !method.desc.endsWith(")Z")) return false;
        return directlyInspectsStack(method);
    }

    /** メソッド本体がスタック検査 API を直接呼び出しているか。 */
    private static boolean directlyInspectsStack(MethodNode method) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode m)) continue;
            String owner = m.owner;
            if (owner == null) continue;
            if (owner.startsWith(STACK_WALKER_PREFIX)) return true;
            if ("sun/reflect/Reflection".equals(owner) && "getCallerClass".equals(m.name)) return true;
            if ("java/lang/Thread".equals(owner) && "getStackTrace".equals(m.name)) return true;
            if ("java/lang/Throwable".equals(owner) && "getStackTrace".equals(m.name)) return true;
        }
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        if (!DefenseNeutralizerTransformer.isDefenseClass(classNode.name)) return false;
        // 冪等: 既に return false だけになっていれば何もしない
        if (isAlreadyNeutralized(method)) return false;
        return neutralizeToFalse(method);
    }

    private static boolean isAlreadyNeutralized(MethodNode method) {
        if (method.instructions == null || method.instructions.size() != 2) return false;
        AbstractInsnNode first = method.instructions.getFirst();
        AbstractInsnNode second = first == null ? null : first.getNext();
        return first != null && second != null
                && first.getOpcode() == Opcodes.ICONST_0
                && second.getOpcode() == Opcodes.IRETURN;
    }

    /** 本体を {@code return false} に置換する。 */
    private static boolean neutralizeToFalse(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (method.name == null || method.name.equals("<init>") || method.name.equals("<clinit>")) return false;

        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));

        method.instructions.clear();
        method.instructions.add(insns);
        if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
        if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
        method.maxStack = 1;
        return true;
    }

    private static boolean containsAscii(byte[] bytes, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int m = n.length;
        int limit = bytes.length - m;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < m; j++) {
                if (bytes[i + j] != n[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }
}
