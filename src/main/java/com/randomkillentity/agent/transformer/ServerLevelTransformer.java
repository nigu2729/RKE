package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * ServerLevel の addFreshEntity に条件付き早期リターンを注入 (汎用復活ブロック)。
 * 専用対策ではなく、死亡直後ウィンドウのみブロック。
 *
 * <p>設計v6 のコメント (合成メソッド rkKillSweep) は本クラスでは実装していない。
 * 本クラスは ServerLevel の {@code addFreshEntity} オーバーライドへの
 * {@link RevivalBlocker} 注入のみを担う。</p>
 *
 * <p><b>重複排除:</b> 基底クラス {@code net.minecraft.world.level.Level} の
 * {@code addFreshEntity} は {@link LevelAddFreshEntityTransformer} が処理する。
 * 本クラスは ServerLevel のみを対象とし、同一メソッドへの二重注入を避ける。</p>
 */
public class ServerLevelTransformer implements ITransformerModule {
    private static final String REVIVAL_BLOCKER = "com/randomkillentity/agent/RevivalBlocker";
    private static final String SHOULD_BLOCK = "shouldBlockRevival";
    private static final String SHOULD_BLOCK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    @Override
    public boolean matchesClass(String className) {
        // Level は LevelAddFreshEntityTransformer が担当するため対象外 (重複排除)。
        if ("net.minecraft.server.level.ServerLevel".equals(className)) return true;
        if ("net/minecraft/server/level/ServerLevel".equals(className)) return true;
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
        // 冪等ガード: shouldBlockRevival 呼び出しが既にあれば再注入しない
        // (ClassIntegrityGuard の周期 retransform で積み重なるのを防ぐ)
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
