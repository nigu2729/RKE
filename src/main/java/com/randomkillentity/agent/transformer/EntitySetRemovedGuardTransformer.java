package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * Entity.setRemoved 内で levelCallback.onRemove が呼ばれる際の NPE 防止。
 */
public class EntitySetRemovedGuardTransformer implements ITransformerModule {
    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    private static final String ENTITY_CLASS_SLASH = "net/minecraft/world/entity/Entity";

    @Override
    public boolean matchesClass(String className) {
        return ENTITY_CLASS.equals(className) || ENTITY_CLASS_SLASH.equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        if (method.name == null) return false;
        if ("setRemoved".equals(method.name) || method.name.equals("m_6089_") || method.name.equals("m_142687_")) {
            if (method.desc != null && method.desc.contains("RemovalReason")) {
                return true;
            }
            if (method.desc != null && method.desc.endsWith("V")) {
                // try anyway
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
            if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (m.name != null && ("onRemove".equals(m.name) || m.name.startsWith("onRemove") || "m_141958_".equals(m.name))) {
                    AbstractInsnNode prev = m.getPrevious();
                    if (prev != null && prev.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode f = (FieldInsnNode) prev;
                        if (("levelCallback".equals(f.name) || "f_146801_".equals(f.name))) {
                            LabelNode skip = new LabelNode();
                            InsnList repl = new InsnList();
                            repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            repl.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, f.name, f.desc));
                            repl.add(new JumpInsnNode(Opcodes.IFNULL, skip));
                            repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            repl.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, f.name, f.desc));
                            repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, m.owner, m.name, m.desc, false));
                            repl.add(skip);
                            method.instructions.insertBefore(prev, repl);
                            method.instructions.remove(prev);
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