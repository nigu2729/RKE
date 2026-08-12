package com.randomkillentity.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.randomkillentity.agent.RKAgent.DEBUG_ENABLED;

public class AsmUtil {
    private static final Map<Integer, String> OPCODE_NAMES = new HashMap<>();

    public static void dumpInsnContext(String className, MethodNode method, AbstractInsnNode target, String label) {
        if(!DEBUG_ENABLED) return;
        AbstractInsnNode[] insns = method.instructions.toArray();
        int idx = -1;
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] == target) { idx = i; break; }
        }
        if (idx == -1) return;

        int start = Math.max(0, idx - 15);
        int end   = Math.min(insns.length, idx + 15);

        StringBuilder sb = new StringBuilder();
        sb.append("\n[RandomKill Dump] ").append(label)
                .append(" | Method: ").append(method.name).append(method.desc)
                .append(" | Class: ").append(className);

        for (int i = start; i < end; i++) {
            AbstractInsnNode insn = insns[i];
            sb.append("\n  [").append(i).append("] ");
            sb.append(i == idx ? ">>> " : "    ");

            if (insn.getOpcode() >= 0) {
                sb.append(AsmUtil.getOpcodeName(insn.getOpcode()));
            } else {
                sb.append(insn.getClass().getSimpleName());
            }

            String operand = getOperandString(insn);
            if (operand != null && !operand.isEmpty()) {
                sb.append(" ").append(operand);
            }
        }
        TransformerCore.LOGGER.info(sb.toString());
    }


    private static String getOperandString(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode m)
            return String.format("%s.%s%s", m.owner, m.name, m.desc);
        if (insn instanceof FieldInsnNode f)
            return String.format("%s.%s:%s", f.owner, f.name, f.desc);
        if (insn instanceof TypeInsnNode t) return t.desc;
        if (insn instanceof IntInsnNode ii)
            return ii.operand >= 0 && ii.operand < 256
                    ? String.valueOf(ii.operand) : "0x" + Integer.toHexString(ii.operand);
        if (insn instanceof VarInsnNode v) return "var[" + v.var + "]";
        if (insn instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof String) return "\"" + ldc.cst + "\"";
            if (ldc.cst instanceof Type)   return ((Type) ldc.cst).getDescriptor();
            return String.valueOf(ldc.cst);
        }
        if (insn instanceof JumpInsnNode j) return "label[" + j.label.getLabel() + "]";
        if (insn instanceof InvokeDynamicInsnNode id)
            return String.format("%s%s [bsm=%s]", id.name, id.desc, id.bsm);
        if (insn instanceof LabelNode l)     return "Label[" + l.getLabel() + "]";
        if (insn instanceof FrameNode)       return "Frame";
        if (insn instanceof LineNumberNode ln) return "line:" + ln.line;
        return null;
    }

    static {
        OPCODE_NAMES.put(Opcodes.NOP, "NOP");
        OPCODE_NAMES.put(Opcodes.ACONST_NULL, "ACONST_NULL");
        OPCODE_NAMES.put(Opcodes.ICONST_M1, "ICONST_M1");
        OPCODE_NAMES.put(Opcodes.ICONST_0, "ICONST_0");
        OPCODE_NAMES.put(Opcodes.ICONST_1, "ICONST_1");
        OPCODE_NAMES.put(Opcodes.ICONST_2, "ICONST_2");
        OPCODE_NAMES.put(Opcodes.ICONST_3, "ICONST_3");
        OPCODE_NAMES.put(Opcodes.ICONST_4, "ICONST_4");
        OPCODE_NAMES.put(Opcodes.ICONST_5, "ICONST_5");
        OPCODE_NAMES.put(Opcodes.LCONST_0, "LCONST_0");
        OPCODE_NAMES.put(Opcodes.LCONST_1, "LCONST_1");
        OPCODE_NAMES.put(Opcodes.FCONST_0, "FCONST_0");
        OPCODE_NAMES.put(Opcodes.FCONST_1, "FCONST_1");
        OPCODE_NAMES.put(Opcodes.FCONST_2, "FCONST_2");
        OPCODE_NAMES.put(Opcodes.DCONST_0, "DCONST_0");
        OPCODE_NAMES.put(Opcodes.DCONST_1, "DCONST_1");
        OPCODE_NAMES.put(Opcodes.BIPUSH, "BIPUSH");
        OPCODE_NAMES.put(Opcodes.SIPUSH, "SIPUSH");
        OPCODE_NAMES.put(Opcodes.LDC, "LDC");
        OPCODE_NAMES.put(Opcodes.ILOAD, "ILOAD");
        OPCODE_NAMES.put(Opcodes.LLOAD, "LLOAD");
        OPCODE_NAMES.put(Opcodes.FLOAD, "FLOAD");
        OPCODE_NAMES.put(Opcodes.DLOAD, "DLOAD");
        OPCODE_NAMES.put(Opcodes.ALOAD, "ALOAD");
        OPCODE_NAMES.put(Opcodes.ISTORE, "ISTORE");
        OPCODE_NAMES.put(Opcodes.LSTORE, "LSTORE");
        OPCODE_NAMES.put(Opcodes.FSTORE, "FSTORE");
        OPCODE_NAMES.put(Opcodes.DSTORE, "DSTORE");
        OPCODE_NAMES.put(Opcodes.ASTORE, "ASTORE");
        OPCODE_NAMES.put(Opcodes.POP, "POP");
        OPCODE_NAMES.put(Opcodes.POP2, "POP2");
        OPCODE_NAMES.put(Opcodes.DUP, "DUP");
        OPCODE_NAMES.put(Opcodes.DUP_X1, "DUP_X1");
        OPCODE_NAMES.put(Opcodes.DUP_X2, "DUP_X2");
        OPCODE_NAMES.put(Opcodes.DUP2, "DUP2");
        OPCODE_NAMES.put(Opcodes.DUP2_X1, "DUP2_X1");
        OPCODE_NAMES.put(Opcodes.DUP2_X2, "DUP2_X2");
        OPCODE_NAMES.put(Opcodes.SWAP, "SWAP");
        OPCODE_NAMES.put(Opcodes.IRETURN, "IRETURN");
        OPCODE_NAMES.put(Opcodes.LRETURN, "LRETURN");
        OPCODE_NAMES.put(Opcodes.FRETURN, "FRETURN");
        OPCODE_NAMES.put(Opcodes.DRETURN, "DRETURN");
        OPCODE_NAMES.put(Opcodes.ARETURN, "ARETURN");
        OPCODE_NAMES.put(Opcodes.RETURN, "RETURN");
        OPCODE_NAMES.put(Opcodes.GETSTATIC, "GETSTATIC");
        OPCODE_NAMES.put(Opcodes.PUTSTATIC, "PUTSTATIC");
        OPCODE_NAMES.put(Opcodes.GETFIELD, "GETFIELD");
        OPCODE_NAMES.put(Opcodes.PUTFIELD, "PUTFIELD");
        OPCODE_NAMES.put(Opcodes.INVOKEVIRTUAL, "INVOKEVIRTUAL");
        OPCODE_NAMES.put(Opcodes.INVOKESPECIAL, "INVOKESPECIAL");
        OPCODE_NAMES.put(Opcodes.INVOKESTATIC, "INVOKESTATIC");
        OPCODE_NAMES.put(Opcodes.INVOKEINTERFACE, "INVOKEINTERFACE");
        OPCODE_NAMES.put(Opcodes.INVOKEDYNAMIC, "INVOKEDYNAMIC");
        OPCODE_NAMES.put(Opcodes.NEW, "NEW");
        OPCODE_NAMES.put(Opcodes.IFEQ, "IFEQ");
        OPCODE_NAMES.put(Opcodes.IFNE, "IFNE");
        OPCODE_NAMES.put(Opcodes.IFLT, "IFLT");
        OPCODE_NAMES.put(Opcodes.IFGE, "IFGE");
        OPCODE_NAMES.put(Opcodes.IFGT, "IFGT");
        OPCODE_NAMES.put(Opcodes.IFLE, "IFLE");
        OPCODE_NAMES.put(Opcodes.IF_ICMPEQ, "IF_ICMPEQ");
        OPCODE_NAMES.put(Opcodes.IF_ICMPNE, "IF_ICMPNE");
        OPCODE_NAMES.put(Opcodes.IF_ICMPLT, "IF_ICMPLT");
        OPCODE_NAMES.put(Opcodes.IF_ICMPGE, "IF_ICMPGE");
        OPCODE_NAMES.put(Opcodes.IF_ICMPGT, "IF_ICMPGT");
        OPCODE_NAMES.put(Opcodes.IF_ICMPLE, "IF_ICMPLE");
        OPCODE_NAMES.put(Opcodes.IF_ACMPEQ, "IF_ACMPEQ");
        OPCODE_NAMES.put(Opcodes.IF_ACMPNE, "IF_ACMPNE");
        OPCODE_NAMES.put(Opcodes.GOTO, "GOTO");
    }

    public static String getOpcodeName(int opcode) {
        if (opcode < 0) return null;
        return OPCODE_NAMES.getOrDefault(opcode, "OPCODE_" + opcode);
    }

    public static void insertAtHead(MethodNode method, InsnList insns) {
        AbstractInsnNode first = method.instructions.getFirst();
        if (first != null) {
            method.instructions.insertBefore(first, insns);
        } else {
            method.instructions.add(insns);
        }
    }

    public static void insertBeforeReturn(MethodNode method, InsnList insns) {
        for (AbstractInsnNode insn : method.instructions) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.IRETURN || opcode == Opcodes.FRETURN ||
                    opcode == Opcodes.ARETURN || opcode == Opcodes.LRETURN ||
                    opcode == Opcodes.DRETURN || opcode == Opcodes.RETURN) {
                method.instructions.insertBefore(insn, insns);
            }
        }
    }

    public static List<AbstractInsnNode> findInstructionsByOpcode(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) {
                result.add(insn);
            }
        }
        return result;
    }

    public static List<MethodInsnNode> findMethodCalls(MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> result = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode methodInsn) {
                if (owner.equals(methodInsn.owner) &&
                        (name.equals(methodInsn.name)) &&
                        desc.equals(methodInsn.desc)) {
                    result.add(methodInsn);
                }
            }
        }
        return result;
    }
}