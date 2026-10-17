package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * 設計v11: 防御mod中和 (最上位 JVMTI / ネイティブ防御対策)。
 *
 * <p>競合防御 mod は MC クラスへ ASM で「ガード呼び出し」を注入する。典型形:</p>
 * <pre>
 *   // 例: 対象メソッド HEAD
 *   if (XxxMethodHooks.shouldBlockSomething(this)) return;
 *   // 例: 状態復元ループ (毎 tick)
 *   XxxEnforcement.enforceImmortal(entity);
 * </pre>
 *
 * <p>本トランスフォーマは、注入された呼び出し「先」にあたる第三者のクラスの
 * ガード/復元メソッドを構造 (メソッド命名パターン) のみで判定して no-op 化する。
 * 呼び出し先が常に中立値 ({@code false} / 既定値) を返すため、注入済みフックは
 * 全て無力化される。呼び出し先の改変は防御mod 自身のクラスにあり復元対象外のため、
 * フックを再注入されても恒久的に無効となる。</p>
 *
 * <p><b>汎用性 (専用対策の禁止):</b> 特定の mod 名・パッケージ名・クラス名を
 * 一切ハードコードしない。バニラ/Forge/ライブラリ/自 mod 以外の「第三者の
 * クラス」を対象とし、その中の防御的な命名のメソッドのみを中和する。
 * これにより、同じ構造パターンを使うあらゆる mod に対して機能する。</p>
 *
 * <p>副作用は大きいが (防御mod の自己修復・状態復元・移動拘束が全て停止する)、
 * 到達範囲と突破力を優先する方針に沿う。</p>
 */
public class DefenseNeutralizerTransformer implements ITransformerModule {

    /**
     * バニラ / Forge / 標準ライブラリ / 自 mod のパッケージ接頭辞 (スラッシュ形式)。
     * これらに属するクラスは中和対象から除外する。
     * ここに列挙しない第三者のクラスは「mod クラス」とみなす。
     */
    private static final String[] SAFE_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
            "net/minecraft/", "net/minecraftforge/", "cpw/mods/",
            "com/mojang/", "org/", "io/", "it/", "com/google/",
            // 自 mod のクラス (ガード/復元メソッドを誤って中和しないための自己除外)
            "com/randomkillentity/"
    };

    /**
     * ガード系メソッド名の接頭辞。boolean を返す認可・保護判定で、
     * 防御mod が注入したフックの「呼び出し先」になるもの。
     * {@code false} を返すように中和すると、注入済みフックが素通りする。
     */
    private static final String[] GUARD_PREFIXES = {
            "shouldBlock", "shouldPrevent", "shouldReplace",
            "shouldCancel", "shouldDeny",
            "isImmortal", "isProtected", "isBypass", "isExempt",
            "checkImmortal", "checkKill", "checkProtected"
    };

    /**
     * 復元/執行ループ系メソッド名に含まれるトークン。
     * 毎 tick で状態を書き戻す自己修復ループや、トランスフォーマ復元処理を
     * 既定値 return で停止させる。
     */
    private static final String[] REPAIR_TOKENS = {
            "restoreIntegerDataItems", "restoreTransformer", "syncImmortal",
            "enforceImmortal", "checkHook", "ensureRunning", "processEntities",
            "onBaseTick", "onLivingTick", "onServerTick", "onEntityTick",
            "executeBaseTick", "cleanupStale", "reRegister", "retransform",
            "restore", "revert", "repair", "revive", "respawn", "resurrect",
            "reincarnate", "reinforce", "reenforce", "reapply", "reassert",
            "reinstall", "rearm", "purge", "sanitize", "quarantine", "watchdog"
    };

    /**
     * クラスロード時に ASM 解析を行うかどうかを安価に判定するための
     * 特徴トークン。クラスファイルの定数プール (UTF-8) にこれらのいずれかが
     * 含まれていれば「防御の候補」とみなす。
     *
     * <p>一般的な語 (restore / enforce 単体など) は誤検出が多いため使わず、
     * 防御実装に特徴的な複合語のみを採用する。これにより通常の mod を
     * 巻き込む確率を抑えつつ、防御エンフォーサを確実に捕捉する。</p>
     */
    private static final String[] CANDIDATE_TOKENS = {
            "Immortal", "shouldBlock", "shouldReplace", "shouldPrevent",
            "shouldCancel", "checkHook", "isProtected", "isBypass",
            "enforceImmortal", "syncImmortal", "restoreIntegerDataItems",
            "restoreTransformer", "onBaseTick", "onLivingTick"
    };

    /** バニラ / Forge / ライブラリ / 自 mod のクラスか (ドット/スラッシュ両対応)。 */
    public static boolean isFrameworkClass(String name) {
        if (name == null) return true;
        String n = name.indexOf('/') >= 0 ? name : name.replace('.', '/');
        for (String p : SAFE_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /** 中和対象になり得る第三者の mod クラスか。 */
    public static boolean isDefenseClass(String name) {
        return !isFrameworkClass(name);
    }

    /**
     * クラスファイルバイト列が防御の候補か (定数プールの簡易走査)。
     * 特定の mod 名ではなく、防御的な命名トークンの有無だけで判定する。
     */
    public static boolean isDefenseCandidate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        for (String token : CANDIDATE_TOKENS) {
            if (containsAscii(bytes, token)) return true;
        }
        return false;
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

    @Override
    public boolean matchesClass(String className) {
        return isDefenseClass(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        if (!isDefenseClass(className)) return false;
        String n = method.name;
        if (n == null || n.equals("<init>") || n.equals("<clinit>")) return false;
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        return isGuardName(n) || isRepairName(n);
    }

    /** ガード命名 (boolean を false に固定する)。 */
    private static boolean isGuardName(String n) {
        for (String p : GUARD_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return n.contains("isCaller");
    }

    /** 復元/執行ループ命名。 */
    private static boolean isRepairName(String n) {
        for (String t : REPAIR_TOKENS) {
            if (n.contains(t)) return true;
        }
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        if (!isDefenseClass(classNode.name)) return false;
        return neutralize(method);
    }

    /**
     * メソッド本体を既定値 return に置換する。
     * boolean を返すガードは false、数値は 0、参照は null、void は即 return となる。
     */
    private static boolean neutralize(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (method.name == null || method.name.equals("<init>") || method.name.equals("<clinit>")) return false;

        InsnList insns = new InsnList();
        switch (Type.getReturnType(method.desc).getSort()) {
            case Type.VOID:
                insns.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
                break;
        }

        method.instructions.clear();
        method.instructions.add(insns);
        if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        if (method.visibleLocalVariableAnnotations != null) method.visibleLocalVariableAnnotations.clear();
        if (method.invisibleLocalVariableAnnotations != null) method.invisibleLocalVariableAnnotations.clear();
        method.maxStack = 2;

        return true;
    }
}
