package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * 設計v8 柱1: 自クラス化 (構造的 isThisName*MOD 注入)
 *
 * <p>スタック検査クラスが持つ {@code isThisName*MOD(String)} 系
 * スタティックメソッドを構造パターンで検出し、HEAD に自パッケージ判定を注入する。
 *
 * <p>検出条件 (クラス名・完全メソッド名はハードコードしない):
 * <ul>
 *   <li>static メソッド ({@code ACC_STATIC})</li>
 *   <li>戻り値 {@code boolean} (Z)</li>
 *   <li>引数が {@code String} 1つ ({@code Ljava/lang/String;})</li>
 *   <li>メソッド名に {@code "isThisName"} と {@code "MOD"} を含む (大文字小文字区別)</li>
 *   <li>本体に {@code String.startsWith} 呼び出しがある</li>
 * </ul>
 *
 * <p>注入内容 (冪等: マーカー LDC {@code "com.randomkillentity."} で二重注入防止):
 * <ul>
 *   <li>{@code isThisNameMyMOD} / {@code isThisNameWhiteListedMOD} 系 (true 系):
 *       {@code if (s != null && s.startsWith("com.randomkillentity.")) return true;}</li>
 *   <li>{@code isThisNameOtherMOD} 系 (false 系):
 *       {@code if (s != null && s.startsWith("com.randomkillentity.")) return false;}</li>
 * </ul>
 *
 * <p>効果: {@code isThisNameOtherMOD("com.randomkillentity....")} が false を返すようになり、
 * {@code isCalledFromOtherModWithin1/5/10} が全て素通りする。これによりバニラ/エージェント
 * 双方のキル API が実行可能になる。また {@code isThisNameOtherBadMOD} も false になり
 * {@code stopBadThreads} がエージェントスレッドを殺さなくなる。
 *
 * <p>汎用性: 特定 mod 固有の名前は一切使わない。同じ構造パターンを使うあらゆる mod に
 * 自動で効く。実行経路は C++ premain CFLH が Forge mod クラスロード時に
 * {@link TransformerCore#transformForByte} を呼ぶため、検査クラスもロード直後に変換される。
 */
public class SelfClassifierTransformer implements ITransformerModule {

    /** 自パッケージプレフィクス (マーカー兼用) */
    private static final String SELF_PREFIX = "com.randomkillentity.";

    @Override
    public boolean matchesClass(String className) {
        // RKTransformer のゲート (isTargetClass || containsAscii("isThisName")) が
        // 事前にフィルタリングするため、ここでは無条件 true を返す。
        return true;
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // static boolean (String) のみ
        // 注意: desc は完全ディスクリプタ (例 "(Ljava/lang/String;)Z")。単独 "Z" と比較すると
        // 常に false になり matchesMethod が全メソッドで失敗する (タスク5 で検出したデッドコードバグ)。
        if ((method.access & Opcodes.ACC_STATIC) == 0) return false;
        if (!method.desc.equals("(Ljava/lang/String;)Z")) return false;

        String name = method.name;
        // メソッド名に "isThisName" と "MOD" を含む (大文字小文字区別)
        // isThisNameMinecraftVanilla は "MOD" を含まないため自然に除外される
        if (!name.contains("isThisName")) return false;
        if (!name.contains("MOD")) return false;

        // 本体に String.startsWith 呼び出しがあるか確認
        boolean hasStartsWith = false;
        if (method.instructions != null) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if ("startsWith".equals(min.name)
                            && "java/lang/String".equals(min.owner)
                            && "(Ljava/lang/String;)Z".equals(min.desc)) {
                        hasStartsWith = true;
                        break;
                    }
                }
            }
        }
        return hasStartsWith;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        // 冪等チェック: 既に SELF_PREFIX の LDC があれば注入済み
        if (method.instructions != null) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc) {
                    if (SELF_PREFIX.equals(ldc.cst)) {
                        return false; // 既に注入済み
                    }
                }
            }
        }

        AbstractInsnNode first = method.instructions.getFirst();
        if (first == null) return false;

        // true 系 (MyMOD / WhiteListedMOD) か false 系 (OtherMOD) かをメソッド名で判定
        boolean isOtherMod = method.name.contains("OtherMOD");

        InsnList injection = new InsnList();
        LabelNode original = new LabelNode();

        // null チェック + startsWith チェック
        // ALOAD 0 (s)
        injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // IFNULL original
        injection.add(new JumpInsnNode(Opcodes.IFNULL, original));
        // ALOAD 0 (s)
        injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // LDC SELF_PREFIX
        injection.add(new LdcInsnNode(SELF_PREFIX));
        // INVOKEVIRTUAL java/lang/String.startsWith (Ljava/lang/String;)Z
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                "(Ljava/lang/String;)Z", false));
        // IFEQ original
        injection.add(new JumpInsnNode(Opcodes.IFEQ, original));
        // ICONST_1 (true) または ICONST_0 (false for OtherMOD)
        injection.add(new InsnNode(isOtherMod ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
        // IRETURN
        injection.add(new InsnNode(Opcodes.IRETURN));
        // original ラベル (元の最初の命令の直前に配置)
        injection.add(original);

        method.instructions.insertBefore(first, injection);
        return true;
    }
}