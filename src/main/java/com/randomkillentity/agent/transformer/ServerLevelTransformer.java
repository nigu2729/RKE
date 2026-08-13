package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * ServerLevel/Level の addFreshEntity に条件付き早期リターンを注入 (汎用復活ブロック)。
 * 専用対策ではなく、死亡直後ウィンドウのみブロック。
 */
public class ServerLevelTransformer implements ITransformerModule {
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
        // addFreshEntity(Entity) returns boolean (MC 1.20.1)
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
        AbstractInsnNode first = method.instructions.getFirst();
        if (first == null) return false;

        InsnList inj = new InsnList();
        // RevivalBlocker.shouldBlockRevival(entity, this)
        inj.add(new VarInsnNode(Opcodes.ALOAD, 1)); // entity
        inj.add(new VarInsnNode(Opcodes.ALOAD, 0)); // level/this
        inj.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                REVIVAL_BLOCKER,
                SHOULD_BLOCK,
                SHOULD_BLOCK_DESC,
                false));
        LabelNode skip = new LabelNode();
        inj.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        // ブロック: 早期 return(false) - 追加しない（復活ブロック）
        inj.add(new InsnNode(Opcodes.ICONST_0));
        inj.add(new InsnNode(Opcodes.IRETURN));
        inj.add(skip);

        method.instructions.insertBefore(first, inj);
        return true;
    }
}