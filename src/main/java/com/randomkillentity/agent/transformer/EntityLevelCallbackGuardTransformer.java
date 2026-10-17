package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * 汎用防御: Entity の levelCallback 呼び出し全般に null チェックを追加。
 * levelCallback が null の状態で onMove/onRemove 等が呼ばれると NPE になる問題を回避する。
 */
public class EntityLevelCallbackGuardTransformer implements ITransformerModule {
    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    private static final String ENTITY_CLASS_SLASH = "net/minecraft/world/entity/Entity";

    @Override
    public boolean matchesClass(String className) {
        return ENTITY_CLASS.equals(className) || ENTITY_CLASS_SLASH.equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // 全メソッド対象（頻度低いが確実）
        return method.desc != null;
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
                // levelCallback のメソッド呼び出しか判定（owner/desc）
                if (m.owner != null && (m.owner.endsWith("EntityInLevelCallback") || m.owner.contains("EntityInLevelCallback"))) {
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
