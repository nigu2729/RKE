package com.randomkillentity.agent.transformer;

import com.randomkillentity.agent.StateDestroyer;
import com.randomkillentity.agent.TransformerCore;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * LivingEntity.tick へのインラインキル注入 (設計v8 拡張: 柱2→柱3 順序化、設計v9: フラグゲート)。
 *
 * <p>ワールド全体即死フラグ rk.should_kill が "true" の間、<b>全 LivingEntity</b> を
 * 自身の tick 冒頭でバニラ公開 API (setRemoved(KILLED) + kill()) だけで除去する。
 * (旧 KILL_RULES によるクラス名/表示名の絞り込みは「専用対策禁止」に反するため削除 —
 * セレクタはフラグのみ。pig2 固有名は一切ハードコードしない)。</p>
 *
 * <p>v6 (ServerLevel スイープ) が Pig2 に効かなかった理由と、本方式の違い:</p>
 * <ul>
 *   <li><b>列挙不要</b>: v6 の collectSnapshot は ServerLevel.getAllEntities (m_8583_) を
 *       使うが、これは EntityLookup を読むため、pig2mod 等が EntityLookup から除去した
 *       「幽霊エンティティ」を列挙できない。本方式は対象エンティティ自身の tick 内で
 *       判定するため、Lookup 除去の影響を一切受けない (Lookup から消されても tick は継続)。</li>
 *   <li><b>完全バニラフレーム</b>: 注入コードはエンティティ自身の tick 内で
 *       setRemoved / kill / getDisplayName 等のバニラメソッドだけを呼ぶ。mod クラスの
 *       フレームを積まないため、pig2mod のスタック検査 (isCalledFromOtherModWithin1/5) を
 *       素通りする (v6 の設計思想「バニラフレームからのキル」をエンティティ単位へ拡張)。</li>
 *   <li><b>フラグゲート (設計v9)</b>: rk.should_kill (ワールド全体即死フラグ) が "true" の
 *       時のみキルが発動する。フラグ OFF (未設定含む) 中は通常 tick へフォールスルーし、
 *       スポーンエッグ産 Pig2 等は生存する (ユーザー意図: 常時即死はしない)。</li>
 * </ul>
 *
 * <p><b>専用対策禁止の遵守:</b> pig2mod 内部フィールド (mIsJisatsu / f_146795_ 直書き等) には
 * 一切触れない。setRemoved(KILLED) をバニラ API 経由で呼ぶだけで RemovalReason フィールドが
 * 正しく立ち、reviveInChunk の復活条件 (RemovalReason == null) を自然に遮断できる。</p>
 *
 * <p><b>設計v8 拡張 (3本柱の因果連鎖):</b> 注入シーケンスを次の順序に拡張する
 * (LivingEntity.tick HEAD、スタック中立、全バニラフレーム):</p>
 * <ol>
 *   <li>{@code if (!"true".equals(System.getProperty("rk.should_kill"))) return;} // 設計v9 フラグゲート (OFF なら何もしない)</li>
 *   <li>{@code StateDestroyer.flipSelfDestructFlags((Object) this);}   // 柱2: 一般名詞自爆フラグ反転 (isEnding ロック)</li>
 *   <li>{@code this.setRemoved(RemovalReason.KILLED);}                 // 柱3: バニラ API 除去 (removalReason = KILLED)</li>
 *   <li>{@code this.kill();}                                           // 保険 (内部で remove→setRemoved)</li>
 * </ol>
 * <p>この順序により、同一 tick 内の {@code Pig2.tick()} → {@code myRevive()} → {@code revive()}
 * (removalReason を null 化) → {@code reviveInChunk()} (再登録) の連鎖を遮断する。
 * flipSelfDestructFlags はリフレクションのみで MC ガードを呼ばないためフレーム非残留。
 * setRemoved は呼び出し元フレームが LivingEntity.tick (バニラ) なのでスタック検査を素通り。
 * 注入コード復帰後、Pig2.tick は isEnding()==true で即リターンし、removalReason=KILLED が
 * 維持されたままバニラの除去機構がエンティティを世界から取り除く。</p>
 *
 * <p>注入位置は tick の HEAD。注入シーケンスはスタック中立 (push/pop が相殺) のため、
 * COMPUTE_FRAMES でも VerifyError にならない。再トランスフォーム時はマーカー LDC
 * ("rk.should_kill") の存在チェックで二重注入を防ぐ。</p>
 */
public class EntityInlineKillTransformer implements ITransformerModule {

    private static final String LIVING_ENTITY_CLASS = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY_CLASS = "net/minecraft/world/entity/Entity";
    private static final String REMOVAL_REASON_CLASS = "net/minecraft/world/entity/Entity$RemovalReason";

    @Override
    public boolean matchesClass(String className) {
        return "net.minecraft.world.entity.LivingEntity".equals(className)
                || LIVING_ENTITY_CLASS.equals(className);
    }

    @Override
    public boolean matchesMethod(String className, MethodNode method) {
        // LivingEntity.tick()V — dev: tick / SRG: m_8119_ (tsrg 実証済み)
        return ("tick".equals(method.name) || "m_8119_".equals(method.name)) && "()V".equals(method.desc);
    }

    @Override
    public boolean transform(TransformerCore.Phase phase, ClassNode classNode, MethodNode method) {
        if (!matchesMethod(classNode.name, method)) return false;
        // 再トランスフォーム時に二重注入しないようガード
        if (hasInlineKillInjected(method)) return false;

        // SRG 環境か dev 環境かは、マッチしたメソッド名で判定する
        boolean srg = "m_8119_".equals(method.name);
        String setRemovedName = srg ? "m_142467_" : "setRemoved";
        String killName = srg ? "m_6074_" : "kill";

        AbstractInsnNode first = method.instructions.getFirst();
        if (first == null) return false;

        InsnList injection = new InsnList();
        LabelNode endLabel = new LabelNode();

        // 設計v9 フラグゲート: ワールド全体即死フラグ rk.should_kill が "true" の時のみキルを発動する。
        // フラグ OFF (未設定含む) ならキルをスキップし、通常の tick へフォールスルーする
        // (ユーザー意図: 常時即死はしない。スポーンエッグ産 Pig2 はフラグ ON まで生存)。
        // スタック中立: push 2 / pop 2。java.lang.System / java.lang.String のみ参照するため
        // pig2mod のスタック検査 (mod フレーム検出) を素通りできる。
        // if (!"true".equals(System.getProperty("rk.should_kill"))) goto endLabel;
        // Safe ordering: push "true" then call System.getProperty and invoke equals on the constant
        injection.add(new LdcInsnNode("true"));
        injection.add(new LdcInsnNode("rk.should_kill"));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        injection.add(new JumpInsnNode(Opcodes.IFEQ, endLabel));

        // フラグ ON → 全 LivingEntity を除去 (KILL_RULES によるクラス名/表示名の絞り込みなし:
        // 専用対策禁止のため、セレクタはフラグのみ)。設計v8 柱2→柱3 順序:
        // 1. StateDestroyer.flipSelfDestructFlags(this)  // 自爆フラグ反転 (isEnding ロック)
        // 2. this.setRemoved(RemovalReason.KILLED);       // バニラ API 除去
        // 3. this.kill();                                // 保険
        // flipSelfDestructFlags は void なのでスタック中立 (ALOAD 0 のみ push/pop 相殺)
        injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injection.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/randomkillentity/agent/StateDestroyer", "flipSelfDestructFlags",
                "(Ljava/lang/Object;)V", false));

        // this.setRemoved(RemovalReason.KILLED);
        injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injection.add(new FieldInsnNode(Opcodes.GETSTATIC, REMOVAL_REASON_CLASS, "KILLED",
                "L" + REMOVAL_REASON_CLASS + ";"));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY_CLASS, setRemovedName,
                "(L" + REMOVAL_REASON_CLASS + ";)V", false));
        // this.kill();   // 保険 (vanilla 内部で remove→setRemoved を呼ぶ)
        injection.add(new VarInsnNode(Opcodes.ALOAD, 0));
        injection.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY_CLASS, killName, "()V", false));

        injection.add(endLabel);
        method.instructions.insertBefore(first, injection);
        return true;
    }

    /** マーカー (フラグゲートの LDC 定数) が既に存在すれば注入済みとみなす。 */
    private boolean hasInlineKillInjected(MethodNode method) {
        final String marker = "rk.should_kill";
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode && marker.equals(((LdcInsnNode) insn).cst)) {
                return true;
            }
        }
        return false;
    }
}
