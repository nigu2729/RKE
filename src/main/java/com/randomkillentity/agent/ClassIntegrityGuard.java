package com.randomkillentity.agent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 設計v12: バニラ中核クラスのフック再主張 (クラス置き換え戦への対抗)。
 *
 * <p>競合 mod は {@code Instrumentation.addTransformer(canRetransform=true)} +
 * {@code retransformClasses} によりロード済みクラスを実行時に書き換えられる
 * (実装例: 他 mod のクラスを jar 走査で収集し自前バイトコードで再定義する
 * ClassFileTransformer)。この戦術がバニラ中核クラスに向けられると、当方の
 * 注入フックが剥奪される。</p>
 *
 * <p><b>設計原則 (バイト巻き戻し方式の廃止):</b> 敵対改変を「定義時バイトの
 * スナップショットで巻き戻す」方式は、正当な再直列化 (別 transformer による定数プール
 * 再番号付け) まで破壊し、第三者サブクラスのリンケージを壊す
 * (実機実証: IncompatibleClassChangeError によるレジストリ失敗)。
 * そこで本ガードは「フック再注入」方式を採る:</p>
 * <ol>
 *   <li><b>チェーン末尾化</b>: ClassFileTransformer は登録順に呼ばれ、後の transformer
 *       ほど最終バイトを決定できる。敵対 transformer は当方より後 (mod 起動後 attach) に
 *       登録されるため、周期タスクで当方 transformer を remove → re-add して
 *       常にチェーン末尾に置く。同一イベント内で敵対改変が起きても当方が最後に書く。</li>
 *   <li><b>周期 retransform</b>: ガード対象クラスを定期的に retransform する。
 *       これにより敵対がフックを剥奪しても、冪等化済みの当方トランスフォーマー群が
 *       敵対バイトの「上に」フックを再適用する (バイト差し替えには干渉しない)。</li>
 * </ol>
 *
 * <p>汎用性: 対象はバニラのエンティティ管理中核のみ (mod 名のハードコードなし)。</p>
 */
public final class ClassIntegrityGuard {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Integrity");

    /** 周期 retransform の対象: バニラ中核クラス (スラッシュ形式)。 */
    private static final Set<String> CORE_CLASSES = new HashSet<>(Arrays.asList(
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/player/Player",
            "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/client/player/LocalPlayer",
            "net/minecraft/server/level/ServerLevel",
            "net/minecraft/network/syncher/SynchedEntityData"
    ));

    /** エンティティ管理パッケージ一括 (PersistentEntitySectionManager / EntitySection /
     *  EntityTickList / EntitySectionStorage / LevelEntityGetterAdapter 等を包含)。 */
    private static final String ENTITY_MANAGEMENT_PACKAGE = "net/minecraft/world/level/entity/";

    private static volatile long reassertCount = 0;

    private ClassIntegrityGuard() {
    }

    /** 周期 retransform の対象か (ドット/スラッシュ両対応)。 */
    public static boolean isGuarded(String className) {
        if (className == null) return false;
        String slash = className.indexOf('/') >= 0 ? className : className.replace('.', '/');
        return CORE_CLASSES.contains(slash) || slash.startsWith(ENTITY_MANAGEMENT_PACKAGE);
    }

    /**
     * 周期整合チェック (DefenseDisarmService の 3 秒周期から呼ばれる):
     * 1) 当方 transformer をチェーン末尾へ移動 (remove → re-add)。
     * 2) ガード対象クラスを retransform して冪等トランスフォーマー群にフックを再適用させる。
     *    敵対改変でフックが剥奪されていても、この周期で復旧する。
     */
    public static void reassertIntegrity() {
        Instrumentation inst = KillTargetRegistry.getInstrumentation();
        if (inst == null) return;
        try {
            moveTransformerLast(inst);
        } catch (Throwable ignored) {
        }

        boolean any = false;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!isGuarded(c.getName())) continue;
            try {
                if (inst.isModifiableClass(c)) {
                    inst.retransformClasses(c);
                    any = true;
                }
            } catch (Throwable ignored) {
                // 未サポート / リンケージエラー等は無視 (次周期で再試行)
            }
        }
        reassertCount++;
        if (any && reassertCount % 200 == 1) {
            LOGGER.info("Hook re-assert cycle " + reassertCount + " completed");
        }
    }

    /**
     * 当方 ClassFileTransformer をチェーンの末尾へ移動する。
     * ClassFileTransformer は登録順に適用され、後のものが最終バイトを決定できるため、
     * ロード後に attach してきた敵対 transformer より後で動くことが重要。
     */
    private static void moveTransformerLast(Instrumentation inst) {
        java.lang.instrument.ClassFileTransformer ours = RKAgent.INSTALLED_TRANSFORMER;
        if (ours == null) return;
        if (inst.removeTransformer(ours)) {
            inst.addTransformer(ours, true);
        }
    }
}
