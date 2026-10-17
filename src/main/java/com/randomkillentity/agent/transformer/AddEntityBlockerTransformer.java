package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * addEntity 系メソッドにも復活ブロックを注入 (汎用)。
 * 防御mod が addEntity 経由で再登録する場合に備える。
 */
public class AddEntityBlockerTransformer implements ITransformerModule {
    private static final String REVIVAL_BLOCKER = "com/randomkillentity/agent/RevivalBlocker";
    private static final String SHOULD_BLOCK = "shouldBlockRevival";
    private static final String SHOULD_BLOCK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    @Override
    public boolean matchesClass(String className) {
        if ("net.minecraft.server.level.ServerLevel".equals(className)) return true;
        if ("net/minecraft/server/level/ServerLevel".equals(className)) return true;
        if ("net.minecraft.world.level.Level".equals(className)) return true;
        if ("net/minecraft/world/level/Level".equals(className)) return true;
        return false;
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        if (method.name == null || method.desc == null) return false;
        // addEntity 系を対象（addFreshEntity は別トランスフォーマで処理済み）
        if (method.name.contains("addEntity") || method.name.equals("m_7967_") || method.name.equals("m_47205_")) {
            // Entity 1引数っぽい場合のみ
            if (method.desc.startsWith("(L")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return false;
        // 冪等ガード: shouldBlockRevival 呼び出しが既にあれば再注入しない (再変換で積み重なるのを防ぐ)
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode m
                    && m.getOpcode() == Opcodes.INVOKESTATIC
                    && REVIVAL_BLOCKER.equals(m.owner)
                    && SHOULD_BLOCK.equals(m.name)) {
                return false;
            }
        }
        AbstractInsnNode first = method.instructions.getFirst();
        if (first == null) return false;

        InsnList inj = new InsnList();
        // 引数0はthis, 引数1はentity想定（最も一般的）
        inj.add(new VarInsnNode(Opcodes.ALOAD, 1));
        inj.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inj.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                REVIVAL_BLOCKER,
                SHOULD_BLOCK,
                SHOULD_BLOCK_DESC,
                false));
        LabelNode skip = new LabelNode();
        inj.add(new JumpInsnNode(Opcodes.IFEQ, skip));

        // 返り値型に応じて適切な値を返す
        String desc = method.desc;
        int retIdx = desc.indexOf(')');
        String retType = retIdx >= 0 && retIdx + 1 < desc.length() ? desc.substring(retIdx + 1) : "V";
        if ("Z".equals(retType)) {
            inj.add(new InsnNode(Opcodes.ICONST_0));
            inj.add(new InsnNode(Opcodes.IRETURN));
        } else if ("V".equals(retType)) {
            inj.add(new InsnNode(Opcodes.RETURN));
        } else if (retType.startsWith("L")) {
            inj.add(new InsnNode(Opcodes.ACONST_NULL));
            inj.add(new InsnNode(Opcodes.ARETURN));
        } else {
            // プリミティブは0相当を返す
            if ("I".equals(retType) || "B".equals(retType) || "C".equals(retType) || "S".equals(retType)) {
                inj.add(new InsnNode(Opcodes.ICONST_0));
                inj.add(new InsnNode(Opcodes.IRETURN));
            } else if ("J".equals(retType)) {
                inj.add(new InsnNode(Opcodes.LCONST_0));
                inj.add(new InsnNode(Opcodes.LRETURN));
            } else if ("F".equals(retType)) {
                inj.add(new InsnNode(Opcodes.FCONST_0));
                inj.add(new InsnNode(Opcodes.FRETURN));
            } else if ("D".equals(retType)) {
                inj.add(new InsnNode(Opcodes.DCONST_0));
                inj.add(new InsnNode(Opcodes.DRETURN));
            } else {
                inj.add(new InsnNode(Opcodes.ACONST_NULL));
                inj.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        inj.add(skip);

        method.instructions.insertBefore(first, inj);
        return true;
    }
}
