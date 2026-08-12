package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * LivingEntity への注入モジュール。
 *
 * <p>設計v2「RK真・即死」: RETURN 再注入 (キャンセル不能)。
 * 防御mod が HEAD で cancel しても、すべての RETURN 出口で vanilla の
 * 戻り値をフックが上書きし直すため、キャンセルが無効化される。</p>
 */
public class LivingEntityTransformer implements ITransformerModule {

    private static final String METHOD_HOOK_CLASS = "com/randomkillentity/agent/hook/LivingEntityMethodImpl";
    private static final String METHOD_HOOK_IFACE = "com/randomkillentity/agent/hook/ILivingEntityMethodHook";
    private static final String METHOD_HOOK_DESC = "L" + METHOD_HOOK_IFACE + ";";
    private static final String HOOK_FIELD = "INSTANCE";

    // ⭕ 汚染を避けるため、引数のphaseの型を java.lang.Object (String) に見立てて処理
    private static final String PHASE_RETURN = "RETURN";

    @Override
    public boolean matchesClass(String className) {
        // ⭕ 設計v3: プレイヤーも即死対象にするため、Player/ServerPlayer の
        // オーバーライド (hurt/die 等) にもフックを注入する。
        // LivingEntity 基底クラスへの注入だけではプレイヤーは死なない (設計v2 の欠陥)。
        if ("net.minecraft.world.entity.LivingEntity".equals(className)) return true;
        if ("net/minecraft/world/entity/LivingEntity".equals(className)) return true;
        if ("net.minecraft.world.entity.player.Player".equals(className)) return true;
        if ("net/minecraft/world/entity/player/Player".equals(className)) return true;
        if ("net.minecraft.server.level.ServerPlayer".equals(className)) return true;
        if ("net/minecraft/server/level/ServerPlayer".equals(className)) return true;
        if ("net.minecraft.client.player.LocalPlayer".equals(className)) return true;
        return "net/minecraft/client/player/LocalPlayer".equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // 読み取り系 (既存)
        if (("getHealth".equals(method.name) || "m_21223_".equals(method.name)) && "()F".equals(method.desc)) return true;
        if (("isDeadOrDying".equals(method.name) || "m_21224_".equals(method.name)) && "()Z".equals(method.desc)) return true;
        if (("isAlive".equals(method.name) || "m_6084_".equals(method.name)) && "()Z".equals(method.desc)) return true;
        if (("isRemoved".equals(method.name) || "m_213877_".equals(method.name)) && "()Z".equals(method.desc)) return true;
        if (("setHealth".equals(method.name) || "m_21153_".equals(method.name)) && "(F)V".equals(method.desc)) return true;

        // 設計v2: キャンセル不能 RETURN 再注入 (MC 1.20.1 SRG名 = m_XXXXX_)
        if (("hurt".equals(method.name) || "m_6469_".equals(method.name))
                && "(Lnet/minecraft/world/damagesource/DamageSource;F)Z".equals(method.desc)) return true;
        if (("die".equals(method.name) || "m_6667_".equals(method.name))
                && "(Lnet/minecraft/world/damagesource/DamageSource;)V".equals(method.desc)) return true;
        if (("heal".equals(method.name) || "m_5634_".equals(method.name)) && "(F)V".equals(method.desc)) return true;
        if (("addEffect".equals(method.name) || "m_147207_".equals(method.name))
                && "(Lnet/minecraft/world/effect/MobEffectInstance;)Z".equals(method.desc)) return true;
        if (("handleDamageEvent".equals(method.name) || "m_6476_".equals(method.name))
                && "(Lnet/minecraft/world/damagesource/DamageSource;)V".equals(method.desc)) return true;
        if (("checkTotemDeathProtection".equals(method.name) || "m_21226_".equals(method.name))
                && "(Lnet/minecraft/world/damagesource/DamageSource;)Z".equals(method.desc)) return true;

        // 設計v5 (プレイヤー殺害拡大 項目2): 毎 tick の RETURN 再注入。
        // m_8119_ ()V tick は LivingEntity / Player / ServerPlayer / LocalPlayer の
        // 全対象クラスに存在 (srg_to_official_1.20.1.tsrg で実証済み)。
        if (("tick".equals(method.name) || "m_8119_".equals(method.name)) && "()V".equals(method.desc)) return true;
        return false;
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        boolean modified = false;

        if ("getHealth".equals(method.name) || "m_21223_".equals(method.name)) {
            modified |= injectReturnHook(method, "getHealth", "(FLjava/lang/Object;Ljava/lang/Object;)F", Opcodes.FRETURN);
        }
        else if ("isDeadOrDying".equals(method.name) || "m_21224_".equals(method.name)) {
            modified |= injectReturnHook(method, "isDeadOrDying", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("isAlive".equals(method.name) || "m_6084_".equals(method.name)) {
            modified |= injectReturnHook(method, "isAlive", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("isRemoved".equals(method.name) || "m_213877_".equals(method.name)) {
            modified |= injectReturnHook(method, "isRemoved", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("setHealth".equals(method.name) || "m_21153_".equals(method.name)) {
            // HEAD: 引数書き換え (既存)
            AbstractInsnNode first = method.instructions.getFirst();
            if (first != null) {
                InsnList injection = new InsnList();
                injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
                injection.add(new VarInsnNode(Opcodes.FLOAD, 1));
                injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        METHOD_HOOK_CLASS,
                        "setHealthHook",
                        "(Ljava/lang/Object;F)F", false));
                injection.add(new VarInsnNode(Opcodes.FSTORE, 1));

                method.instructions.insertBefore(first, injection);
                modified = true;
            }
            // RETURN: 不死身化ブロック (実体フィールドへ 0 を書き戻す)
            modified |= injectVoidReturnHook(method, "setHealth", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }
        // ── 設計v2: キャンセル不能 RETURN 再注入 ──
        else if ("hurt".equals(method.name) || "m_6469_".equals(method.name)) {
            modified |= injectReturnHook(method, "hurt", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("die".equals(method.name) || "m_6667_".equals(method.name)) {
            modified |= injectVoidReturnHook(method, "die", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }
        else if ("heal".equals(method.name) || "m_5634_".equals(method.name)) {
            modified |= injectVoidReturnHook(method, "heal", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }
        else if ("addEffect".equals(method.name) || "m_147207_".equals(method.name)) {
            modified |= injectReturnHook(method, "addEffect", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("handleDamageEvent".equals(method.name) || "m_6476_".equals(method.name)) {
            modified |= injectVoidReturnHook(method, "handleDamageEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }
        else if ("checkTotemDeathProtection".equals(method.name) || "m_21226_".equals(method.name)) {
            modified |= injectReturnHook(method, "checkTotemDeathProtection", "(ZLjava/lang/Object;Ljava/lang/Object;)Z", Opcodes.IRETURN);
        }
        else if ("tick".equals(method.name) || "m_8119_".equals(method.name)) {
            modified |= injectVoidReturnHook(method, "tick", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        }

        return modified;
    }

    /**
     * 戻り値ありメソッドの RETURN 再注入。
     * すべての {returnOpcode} 出口の直前に
     * {@code INSTANCE.hook(戻り値, this, "RETURN")} を挿入する。
     * フックの戻り値がそのまま実際の戻り値になる (キャンセル不能)。
     */
    private boolean injectReturnHook(MethodNode method, String hookMethod, String hookDesc, int returnOpcode) {
        boolean injected = false;
        // 解析指摘対応: SWAP は JVM 仕様上 カテゴリ1 (1スロット) 同士にしか使えない。
        // double/long (2スロット) を返すメソッドをフックすると VerifyError になるため、
        // 戻り値のスロット数に応じてスタック操作を分岐する。
        // 現在のフック対象は全て 1スロット (F/Z) だが、将来の追加で事故らないよう防御する。
        boolean twoSlotReturn = returnOpcode == Opcodes.DRETURN || returnOpcode == Opcodes.LRETURN;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != returnOpcode) continue;
            InsnList injection = new InsnList();
            injection.add(new FieldInsnNode(Opcodes.GETSTATIC, METHOD_HOOK_CLASS, HOOK_FIELD, METHOD_HOOK_DESC));
            if (twoSlotReturn) {
                // [retLo, retHi, INSTANCE] → DUP_X2 → [INSTANCE, retLo, retHi, INSTANCE] → POP → [INSTANCE, retLo, retHi]
                // (DUP_X2 の Form 2: カテゴリ2 の値の上にカテゴリ1 を複製して下へ差し込む)
                injection.add(new InsnNode(Opcodes.DUP_X2));
                injection.add(new InsnNode(Opcodes.POP));
            } else {
                injection.add(new InsnNode(Opcodes.SWAP));
            }
            injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
            injection.add(new LdcInsnNode(PHASE_RETURN));
            injection.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    METHOD_HOOK_IFACE, hookMethod, hookDesc, true));

            method.instructions.insertBefore(insn, injection);
            injected = true;
        }
        return injected;
    }

    /**
     * void メソッドの RETURN 再注入。
     * すべての RETURN 出口の直前に {@code INSTANCE.hook(this, "RETURN")} を挿入する。
     */
    private boolean injectVoidReturnHook(MethodNode method, String hookMethod, String hookDesc) {
        boolean injected = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList injection = new InsnList();
            injection.add(new FieldInsnNode(Opcodes.GETSTATIC, METHOD_HOOK_CLASS, HOOK_FIELD, METHOD_HOOK_DESC));
            injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
            injection.add(new LdcInsnNode(PHASE_RETURN));
            injection.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    METHOD_HOOK_IFACE, hookMethod, hookDesc, true));

            method.instructions.insertBefore(insn, injection);
            injected = true;
        }
        return injected;
    }
}
