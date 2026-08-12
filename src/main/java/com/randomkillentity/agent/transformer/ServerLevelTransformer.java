package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * ServerLevel への注入モジュール (設計v6: バニラフレームからのキル執行)。
 *
 * <p>pig2mod のスタック検査 (MyLib2.isCalledFromOtherModWithin1/5) は、
 * kill 系メソッド (setRemoved / remove / kill 等) の呼び出し元フレームが
 * net.minecraft.* 以外 (他MOD/エージェント) だと処理をキャンセルする。
 * 従来の LivingEntity RETURN フックは呼び出し元が com/randomkillentity/...
 * になるため原理的にブロックされる。</p>
 *
 * <p>本モジュールは ServerLevel に合成メソッド {@code rkKillSweep} を追加し、
 * {@code tick} (m_8793_) の HEAD から呼び出す。スナップショット収集
 * (ServerLevelSweepHelper.collectSnapshot) はエージェントフレームで行い、
 * 返却後に合成メソッド内 (net.minecraft.server.level.ServerLevel フレーム) から
 * {@code setRemoved(KILLED)} / {@code kill()} を直接呼ぶため、
 * ガード検査時の呼び出し元は全てバニラフレームになる。</p>
 *
 * <p>専用対策禁止 (ユーザー指示): pig2mod 内部フィールド (mIsJisatsu / f_146795_
 * 直書き等) には一切触れない。setRemoved(KILLED) をバニラ API 経由で呼ぶだけで
 * f_146795_ が正しく立ち、reviveInChunk の復活条件 (f_146795_ == null) を
 * 自然に遮断できる。</p>
 */
public class ServerLevelTransformer implements ITransformerModule {

    private static final String SERVER_LEVEL_CLASS = "net/minecraft/server/level/ServerLevel";
    private static final String ENTITY_CLASS = "net/minecraft/world/entity/Entity";
    private static final String REMOVAL_REASON_CLASS = "net/minecraft/world/entity/Entity$RemovalReason";
    private static final String SWEEP_HELPER_CLASS = "com/randomkillentity/agent/ServerLevelSweepHelper";
    private static final String SYNTHETIC_METHOD = "rkKillSweep";
    private static final String SYNTHETIC_DESC = "(L" + SERVER_LEVEL_CLASS + ";)V";

    @Override
    public boolean matchesClass(String className) {
        return "net.minecraft.server.level.ServerLevel".equals(className)
                || SERVER_LEVEL_CLASS.equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // ServerLevel.tick (BooleanSupplier) — dev: tick / SRG: m_8793_ (tsrg 実証済み)
        if (("tick".equals(method.name) || "m_8793_".equals(method.name))
                && "(Ljava/util/function/BooleanSupplier;)V".equals(method.desc)) return true;
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        boolean modified = false;

        // SRG 環境か dev 環境かは、マッチしたメソッド名で判定する
        boolean srg = "m_8793_".equals(method.name);
        String setRemovedName = srg ? "m_142467_" : "setRemoved";
        String killName = srg ? "m_6074_" : "kill";

        // 1) 合成メソッド rkKillSweep をクラスに追加 (既に存在する場合は再注入しない)
        if (!hasSyntheticMethod(classNode)) {
            classNode.methods.add(buildSyntheticMethod(setRemovedName, killName));
            modified = true;
        }

        // 2) tick の HEAD に this を渡して rkKillSweep を呼び出す
        //    (再トランスフォーム時に二重注入しないようガード)
        if (!hasSyntheticCall(method)) {
            AbstractInsnNode first = method.instructions.getFirst();
            if (first != null) {
                InsnList injection = new InsnList();
                injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        SERVER_LEVEL_CLASS, SYNTHETIC_METHOD, SYNTHETIC_DESC, false));
                method.instructions.insertBefore(first, injection);
                modified = true;
            }
        }

        return modified;
    }

    private boolean hasSyntheticMethod(ClassNode classNode) {
        if (classNode.methods == null) return false;
        for (MethodNode mn : classNode.methods) {
            if (SYNTHETIC_METHOD.equals(mn.name)) return true;
        }
        return false;
    }

    private boolean hasSyntheticCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (min.getOpcode() == Opcodes.INVOKESTATIC
                        && SERVER_LEVEL_CLASS.equals(min.owner)
                        && SYNTHETIC_METHOD.equals(min.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 合成メソッド {@code rkKillSweep(ServerLevel)} を生成する。
     * {@code List snapshot = ServerLevelSweepHelper.collectSnapshot(level);
     * for (Object o : snapshot) { if (o instanceof Entity) {
     *     ((Entity)o).setRemoved(RemovalReason.KILLED);
     *     ((Entity)o).kill();
     * } }}
     * このメソッドのフレームは net.minecraft.server.level.ServerLevel なので、
     * スタック検査 (Within1/Within5) を素通りする。
     */
    private MethodNode buildSyntheticMethod(String setRemovedName, String killName) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                SYNTHETIC_METHOD, SYNTHETIC_DESC, null, null);
        InsnList il = mn.instructions;

        LabelNode retLabel = new LabelNode();
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();

        // List snapshot = ServerLevelSweepHelper.collectSnapshot((Object) level);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SWEEP_HELPER_CLASS,
                "collectSnapshot", "(Ljava/lang/Object;)Ljava/util/List;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // if (snapshot == null) return;
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNULL, retLabel));

        // Iterator it = snapshot.iterator();
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));

        // while (it.hasNext()) {
        il.add(loopStart);
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext",
                "()Z", true));
        il.add(new JumpInsnNode(Opcodes.IFEQ, loopEnd));

        //   Object o = it.next();
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));

        //   if (!(o instanceof Entity)) continue;
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new TypeInsnNode(Opcodes.INSTANCEOF, ENTITY_CLASS));
        il.add(new JumpInsnNode(Opcodes.IFEQ, loopStart));

        //   ((Entity) o).setRemoved(RemovalReason.KILLED);
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_CLASS));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, REMOVAL_REASON_CLASS, "KILLED",
                "L" + REMOVAL_REASON_CLASS + ";"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY_CLASS, setRemovedName,
                "(L" + REMOVAL_REASON_CLASS + ";)V", false));

        //   ((Entity) o).kill();   // 保険 (vanilla 内部で remove→setRemoved を呼ぶ)
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_CLASS));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY_CLASS, killName, "()V", false));

        il.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        // }
        il.add(loopEnd);
        il.add(retLabel);
        il.add(new InsnNode(Opcodes.RETURN));

        return mn;
    }
}
