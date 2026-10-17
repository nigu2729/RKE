package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Level.addFreshEntity にも同様の復活ブロックを注入 (汎用)。
 */
public class LevelAddFreshEntityTransformer implements ITransformerModule {
    private static final String REVIVAL_BLOCKER = "com/randomkillentity/agent/RevivalBlocker";
    private static final String SHOULD_BLOCK = "shouldBlockRevival";
    private static final String SHOULD_BLOCK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    @Override
    public boolean matchesClass(String className) {
        if ("net.minecraft.world.level.Level".equals(className)) return true;
        if ("net/minecraft/world/level/Level".equals(className)) return true;
        return false;
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        if (("addFreshEntity".equals(method.name) || "m_8847_".equals(method.name) || "m_8836_".equals(method.name))
                && method.desc != null) {
            if (method.desc.startsWith("(L") && method.desc.endsWith(")Z")) {
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
        inj.add(new VarInsnNode(Opcodes.ALOAD, 1)); // entity
        inj.add(new VarInsnNode(Opcodes.ALOAD, 0)); // level
        inj.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                REVIVAL_BLOCKER,
                SHOULD_BLOCK,
                SHOULD_BLOCK_DESC,
                false));
        LabelNode skip = new LabelNode();
        inj.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        inj.add(new InsnNode(Opcodes.ICONST_0));
        inj.add(new InsnNode(Opcodes.IRETURN));
        inj.add(skip);

        method.instructions.insertBefore(first, inj);
        return true;
    }
}
