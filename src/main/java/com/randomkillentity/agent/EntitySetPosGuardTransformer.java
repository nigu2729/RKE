package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * 防御的: Entity.setPosRaw 内の levelCallback.onMove() 呼び出しに null チェックを追加 (汎用)。
 * levelCallback が null の状態でパケット処理が走ると NPE になる問題を回避する。
 */
public class EntitySetPosGuardTransformer implements ITransformerModule {
    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    private static final String ENTITY_CLASS_SLASH = "net/minecraft/world/entity/Entity";

    @Override
    public boolean matchesClass(String className) {
        return ENTITY_CLASS.equals(className) || ENTITY_CLASS_SLASH.equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // setPosRaw(double, double, double)
        if ("setPosRaw".equals(method.name) || "m_32607_".equals(method.name)) {
            if (method.desc != null && method.desc.equals("(DDD)V")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        if (method.instructions == null) return false;
        int patched = 0;
        ListIterator<AbstractInsnNode> it = method.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            // levelCallback.onMove() パターン: GETFIELD levelCallback, INVOKEVIRTUAL onMove
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (("onMove".equals(m.name) || "m_141973_".equals(m.name))
                        && m.desc != null && m.desc.equals("()V")) {
                    // 前が GETFIELD levelCallback か確認
                    AbstractInsnNode prev = m.getPrevious();
                    if (prev != null && prev.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode f = (FieldInsnNode) prev;
                        if (("levelCallback".equals(f.name) || "f_146801_".equals(f.name))
                                && ("net/minecraft/world/level/entity/EntityInLevelCallback".equals(f.desc)
                                || "Lnet/minecraft/world/level/entity/EntityInLevelCallback;".equals(f.desc))) {
                            LabelNode skip = new LabelNode();
                            AbstractInsnNode getFieldNode = prev;
                            InsnList repl = new InsnList();
                            repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            repl.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, f.name, f.desc));
                            repl.add(new JumpInsnNode(Opcodes.IFNULL, skip));
                            repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            repl.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, f.name, f.desc));
                            repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, m.owner, m.name, m.desc, false));
                            repl.add(skip);
                            method.instructions.insertBefore(getFieldNode, repl);
                            method.instructions.remove(getFieldNode);
                            method.instructions.remove(m);
                            patched++;
                            break;
                        }
                    }
                }
            }
        }
        return patched > 0;
    }
}
