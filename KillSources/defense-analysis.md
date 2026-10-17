# KillSources 防御機構・突破分析

対象: RandomKillEntity(以下「攻撃側」)が KillSources の 5 Mod の防御・即死機構を
**完全に汎用的な手段(ASM/Mixin/低レイヤー)のみで**突破できるかの分析。

- 攻撃側の全レイヤー: `LivingEntityTransformer`(ASM RETURN 再注入・tick/m_8119_ 注入) / `WorldStructureDestroyer`(EntityLookup/EntitySectionStorage/EntityTickList 直接除去) / `StateDestroyer`(防御フラグ反転・数値 MIN_VALUE・vanilla mixin 防御フィールド破壊) / `FallbackDeathHandler`(独自死亡処理: dead/health/removed を Unsafe 直書き) / `KillTargetRegistry`(静的コレクション掃除・should_kill honor) / `GlobalKillFlag`(should_kill)
- 解析方法: Windows javap.exe でバイトコード直接読解(`workdir` + `-classpath .`)。Mixin は `mixins.<mod>.json` の設定と一致確認。
- 検証レベル: バイトコード解析(実機未検証)。`[要検証]` 印は実機確認が必要な点。

---

## 共通の攻撃側カウンター(本分析の前提)

| 攻撃レイヤー | 何をするか | 影響 |
|---|---|---|
| LivingEntityTransformer | LivingEntity/Player/ServerPlayer/LocalPlayer の getHealth/setHealth/hurt/die/heal/addEffect/handleDamageEvent/checkTotemDeathProtection を**全 RETURN で上書き** | 対象 Mod の method 改変より**後段**なら勝つ(TRANSFORM 順に依存) |
| WorldStructureDestroyer | EntityTickList(`f_143243_`) / EntitySectionStorage / EntityLookup / ServerChunkCache から直接除去、removalReason(`f_146795_`)=KILLED、setRemoved、void テレポート | 対象 Mod の「remove キャンセル系」が**entity 側メソッド**なら回避できる |
| StateDestroyer | mod サブクラスの数値→MIN_VALUE、防御フラグ boolean を名前で反転(dead/die/kill/alive/valid/invul/protect/immortal/muteki/invincible/fumetsu/safe/novel)、SynchedEntityData 破壊 | フィールド実体ベースの防御を直接無効化 |
| KillTargetRegistry | ロード済みクラスの静的 List/Set/Map から対象 UUID 掃除(5s 間隔・深さ4) | DisableRemoveSet 等の「remove no-op 化」に無力化される |

---

## 1. lal (lal-1.1.1) — 最も重い三重防御

### 構成(三重)

1. **Mixin 層**: `mixins.lal.json` — mixinPriority = Integer.MAX_VALUE の全 Entity 対象 mixin。
2. **Coremod ASM 層**: `coremods/lal_transformer.js` — MAPPINGS リストで LivingEntity 系メソッドを
   `EntityMethodHooks` のスタティックへ**ヘッド差し替え**(headStyle `cancel_return` / `cancel_void`)。
   対象: getHealth(`m_21223_`)/isDeadOrDying(`m_21224_`)/isAlive(`m_6084_`)/isRemoved(`m_213877_`)/
   getRemovalReason(`m_146911_`)/tick(`m_8119_`)/setPose/setRemoved/kill/discard/remove/move/setPosRaw/
   setDeltaMovement/push/die/setHealth/tickDeath/actuallyHurt/knockback/setNoGravity/baseTick(`m_6075_`) 等。
3. **ネイティブ層(JVMTI)**: `native/lal.c`(Windows DLL、JNI+JVMTI)。
   - `ClassFileLoadHookCB`: Entity/LivingEntity ロード時にフィールド解決マーク+再定義検出。
   - `FieldModificationCB`: **health/dead/deathTime/removalReason の書き込みを即時修正**。
     health: kill セット→0.0f / immortal→maxHp 固定。dead: immortal→false。deathTime→0。removalReason: immortal→null / kill→KILLED。
   - `BreakpointCB` + `EnforcementThread`(50ms ループ): JNI AttachCurrentThreadAsDaemon で
     gKillProgress 上の entity のフィールドを強制。`isBypass()`(TLS)で自前書換を保護。
   - `LALAgent`: premain/agentmain、`PROTECTED_CLASSES`、`tryLoadNative`、`tryInstallHiddenTransformer`、`retransformTargetClasses`。

### 核心ロジック(バイトコード読解)

- `EntityMethodHooks.shouldReplaceMethod`: `CombatRegistry.isInKillSet || isInImmortalSet || isDeadConfirmed`
  なら置換対象 → getHealth を差し替え。`BYPASS`(ThreadLocal)・baseTickFired/normalTickSeen/forcedTickThisTick の ConcurrentHashMap 群。
- `CombatRegistry`: `DisableRemoveSet` 4 個(KILL_SET / IMMORTAL_SET / IMMORTAL_SET_BACKUP / DEAD_CONFIRMED)
  + FORCED_HEALTH / KILL_ATTACKERS / KILL_START_TICK / LOOT_DROPPED / KILL_HISTORY + **native メソッド群**。
- `DisableRemoveSet`: **remove/clear/removeAll/removeIf/retainAll が全て no-op(iconst_0 / return)、
  iterator は内部 Set を素通し**、internalRemove/internalClear のみ実体削除
  → **KillTargetRegistry のレジストリスイープを完全無力化**する直接カウンター。
- `LifePolicyEngine`: `adminKillBypassesImmortal=false`(static 初期値)。requestKill は immortal セット内なら
  bypass フラグ必須、requestImmortal は kill セットから除去して immortal へ。

### 突破可否: **条件付き突破可能**

| 攻撃手段 | lal の反応 | 判定 |
|---|---|---|
| ASM RETURN 再注入(getHealth 等) | **ヘッド差し替えが先に実行** → メソッド本体(＝当方の RETURN 注入先)に到達しない可能性 | ⚠️ 要実機検証。coremod の差し替えは「メソッド冒頭で hook 呼び出し→条件成立なら即 return」なので、当方注入コードが未実行になり得る |
| Unsafe 直書き health | FieldModificationCB が JVMTI レベルで検知 → **immortal なら maxHp へ即時復元** / kill セットなら 0 化(殺害側なので不都合なし) | ⚠️ FieldModification は JNI/Unsafe 書き込みも報告する(JVMTI 仕様) — [要検証] |
| WorldStructureDestroyer 直接除去 | **entity 側 remove/discard/setRemoved は cancel されるが、EntityLookup/EntitySectionStorage の map 直接除去は hook 対象外** | ✅ 突破経路 |
| StateDestroyer フラグ反転 | immortal 等のフラグは static private + native 層が維持 → 反転は即時復元の可能性 | ⚠️ 要検証 |
| KillTargetRegistry スイープ | DisableRemoveSet が no-op → **無効** | ❌ 単体では無理 |

**推奨突破経路**:
1. `WorldStructureDestroyer` による map 直接除去(lal の entity メソッド hook を素通り)。
2. `CombatRegistry` の `DisableRemoveSet.internalRemove/internalClear` をリフレクションで呼ぶ
   (no-op 化を回避してセットから実体削除 → ネイティブ層の対象外にする)。汎用化: 「クラスパス上で
   `DisableRemoveSet` 相当(remove 系が no-op の Set)を検出して internal* を呼ぶ」のは汎用の範囲内だが
   実装コスト高。もっと汎用的には、StateDestroyer で `BYPASS` ThreadLocal を立ててから当方の書換を行う。
3. `[要検証]` EnforcementThread が gKillProgress から外れるまではフィールド強制が続く → 除去後に
   entity を `KILL_ATTACKERS`/`KILL_HISTORY` からも除外するか、EnforcementThread 自体の停止を試みる。

---

## 2. Hyperlink (Hyperlink-1201.2.4) — muteki / novel / fumetsu / chronicle

### 構成

- パッケージ: `com.sakurafuld.hyperdaimc`。Mixin + Forge イベント + パケット(SimpleChannel)。
- **muteki(無敵)**: `mixin/muteki/` 以下。
  - `LivingEntityMixin`: private `muteki`(boolean) + `lastHealth`。`tickMuteki` が毎 tick
    `MutekiHandler.checkMuteki` で再評価し forced=true。`dieMuteki$LivingEntity` = `!novelized && muteki` なら die を cancel。
  - `MutekiHandler.muteki()` = `ILivingEntityMuteki.muteki()`(mixin フィールド)を返すだけ。
    `checkMuteki` = ENABLE_MUTEKI config && `Check.isMuteki`。
  - `EntityRemovalUtilMixin.deleteEntity`: muteki ならダメージソースで hurt させて cancel(生存強制)。
  - `EntityUtilMixin`: forceSetDeltaMovement を cancel(落下・動き操作対策)。
  - `SynchedEntityDataMixin.set`: **DATA_HEALTH_ID セットを intercept**、`muteki && !mutekiForced` ならセット拒否/復元
    → ラストヒットの DataWatcher 健康値書換を無効化(**当方は Unsafe 直書きなので非該当**)。
- **novel(ノベル化＝殺傷手段)**: `mixin/novel/` + `NovelHandler`。
  - `LivingEntityMixin.novelize`: NovelDamageSource、muteki チェック、`setNovelized`、novelSetHealth、
    getHealthNovel/isDeadOrDyingNovel/isAliveNovel で状態改変、`novelDead()`。
  - `EntityMixin`: `DATA_NOVELIZED`(SynchedEntityData Boolean)、removalReason/EntityInLevelCallback の shadow。
  - `NovelHandler.novelize(entity, attacker, isClient)`: ENABLE_NOVEL なら ClientboundNovelize パケット送信、
    PartEntity は親へ再帰、`IEntityNovel.novelize()` 呼び出し。ノベル化後は `DieNovelized` ログ+`m_6667_(die)`、
    FumetsuEntity 以外は mutekiForce(true) → setHealth → SynchedEntityData に f_20961_ を書く。
- **fumetsu(不死化エンティティ)**: `mixin/fumetsu/`。
  - `EntityLookupMixin.add`: IFumetsu なら本来の add を cancel し**自前で byUuid/byId へ登録**(重複 UUID 警告+再登録)。
  - `LivingEntityMixin.getMaxHealthFumetsu`: FumetsuEntity なら FUMETSU_HEALTH(config)を返す。
  - `PersistentEntitySectionManager$CallbackMixin`: onRemoveFumetsu$0/$1(除去キャンセル)、onMoveFumetsu$BEFORE/AFTER、
    processUnloadsFumetsu$HEAD/RETURN(アンロード対策)。
  - `TransientEntitySectionManager$CallbackMixin`: 同様の onRemoveFumetsu / onMoveFumetsu。
  - `ChunkMapMixin`: `entityMap2`(Int2ObjectMap)追加、moveFumetsu / addEntityFumetsu$0/$1 / removeEntityFumetsu$0/$1 /
    tickFumetsu$0 / broadcastFumetsu — **ChunkMap の tracked entity 登録も独自管理**。
- **chronicle(ワールド改変保護)**: `mixin/chronicle/` 以下(未詳細 — ワールドブロック改変の保護)。

### 突破可否: **突破可能(経路次第)**

| 攻撃手段 | Hyperlink の反応 | 判定 |
|---|---|---|
| die/hurt 経由の殺害 | muteki なら die cancel / hurt キャンセル / EntityRemovalUtilMixin で生存強制 | ❌ muteki 対象には効かない |
| Unsafe 直書き health | SynchedEntityDataMixin は DataWatcher のみ intercept → **Unsafe 直書きは非該当** | ✅ |
| WorldStructureDestroyer map 直接除去 | fumetsu は EntityLookup/ChunkMap 双方を独自管理 → **fumetsu 専用 entity は再登録される恐れ** | ⚠️ 通常 entity は ✅ / fumetsu は [要検証] |
| StateDestroyer フラグ反転 | `muteki`/`novelized` は**mixin フィールド(private)** → 名前で反転可能。ただし tickMuteki が毎 tick 再評価 → 恒久化には checkMuteki 側の無効化(ENABLE_MUTEKI config の反転)が要る | ⚠️ 条件付き |
| KillTargetRegistry スイープ | 防御はフィールド/ワールド構造ベース → 静的コレクションは無関係 | — |

**推奨突破経路**:
1. 通常 entity: `WorldStructureDestroyer`(map 直接除去) + Unsafe 直書きで問題なし。
2. muteki 対象: まず StateDestroyer で `muteki` フィールド + `HyperCommonConfig.ENABLE_MUTEKI`/`MUTEKI_NOVEL`
   (ForgeConfigSpec.BooleanValue)を反転 → その後の kill は素通り。
3. fumetsu: [要検証] EntityLookup と ChunkMap の**両方**から外せるか。fumetsu は `IEntityFumetsu.fumetsuExtinction(RemovalReason)`
   API(公開)がある — 汎用化したければ「インターフェース名に fumetsu が含まれる場合は fumetsuExtinction をリフレクションで呼ぶ」
   のは汎用の範囲だが、まずは map 除去で試す。

---

## 3. No-Sugar (No-Sugar-1.7.7-all) — sugar_sword / sugar_totem / snackprotector

### 構成

- パッケージ: `com.test.nosugar`。LaunchPlugin(ILaunchPluginService) + TransformerCore(Phase 付き MODULES)+ Mixin。
- `transformer/hook/livingentity/LivingEntityMethodsImpl`: **getHealth/isDeadOrDying/isAlive が
  `MinecraftForge.EVENT_BUS` に `LivingEntityMethodEvent` を post し、その戻り値を返す**。
- `NoSugarLaunchPlugin` = `cpw.mods.modlauncher.serviceapi.ILaunchPluginService`。`TransformerCore` = MODULES + Phase + classNodeCache。
- **sugar_totem(防御)**: `mixin/sugar_totem/EntityMixin` — Player && `SugarTotem.hasTotem` なら
  modifyHurtAmount が死亡直前 damage→0、sugartotem$cancelDie で die cancel。
- **snackprotector(防御)**: `mixin/snackprotector/LivingEntityMixin` — Player && `SnackArmor$SnackProtector.isFullSet`
  なら hurt を cancel、handleDamageEvent を cancel。
- **sugar_sword(掃討=攻撃側)**: 
  - `EntityLookupMixin.eraseEntity(T)`: UUID+ID で `getByUuid`(Map)と `getById`(Int2ObjectMap)から**直接 remove**して set し直す
    (= WorldStructureDestroyer と同じ方式!)。 `eraseAll` 系もあり。
  - `EntityAccessor`: getRemovalReason/setRemovalReason/getLevelCallBack/setlevelCallback(levelCallback を差し替え可能に)。
  - `LivingEntityAccessor`: getDataHealthId/setLastHurtByPlayer/invokeDropExperience/invokeGetKillCredit/
    invokeDropAllDeathLoot/isDeadFlag/setDeadFlag/callDie 等 — **private メソッドの呼び出し口**。
  - `EntityMixin(nosugar$onTick)`: `ILivingEntity.isErased()` なら処理スキップ。`nosugar$onisRemoved`: isErased なら true 固定。
  - `EntityTickListMixin`(forEach 時除去/onAdd)、`EntitySectionMixin`、`ChunkSerializerMixin`(protoChunk 読込時フィルタ)。

### 突破可否: **突破可能**

| 攻撃手段 | No-Sugar の反応 | 判定 |
|---|---|---|
| Forge イベント経由の改変 | getHealth 等が EVENT_BUS post → **当方の WorldStructureDestroyer が他 Mod のイベントハンドラを全解除済み**なので、post されても当方の結果が通る。むしろ当方がハンドラとして狙える | ✅ |
| die/hurt 経由の殺害 | sugar_totem/snackprotector は **Player 限定** → NPC/entity には無関係 | ✅ |
| WorldStructureDestroyer map 直接除去 | sugar_sword と**同一方式**。但し sugar_sword は EntityLookup 除去+levelCallback 差し替えまでやるので、当方も levelCallback(`f_146801_`)を差し替え/無効化するのが確実 | ✅(levelCallback 差し替えを追加推奨) |
| StateDestroyer | `isErased` フラグが sugar_sword 側。防御側フィールドは Player 依存で NPC には無し | ✅ |
| KillTargetRegistry スイープ | 攻撃側機構なので無関係 | — |

**推奨突破経路**:
1. 通常 entity: WorldStructureDestroyer そのままで突破可。
2. `EntityAccessor.setlevelCallback` 相当(levelCallback を null/no-op に差し替え)を WorldStructureDestroyer に追加すると
   「除去後の再登録/コールバック復活」を確実に防げる — **全 Mod 共通の強化ポイント**。
3. getHealth 経由の確実な 0 化: 当方 LivingEntityTransformer の RETURN 注入が効く(No-Sugar は method 全体差し替えでなく
   イベント post なので、注入コードは post の外側に残る)。

---

## 4. TheTrialMonolith (TheTrialMonolith-1.20.1-Forge-1.4.9) — ソウル保護

### 構成

- パッケージ: `io.github.kosianodangoo.trialmonolith`。Mixin + Transformer(method 置換)+ Item。
- `EntityHelper.cannotBeRemoved(entity)` = **`isSoulProtected && !shouldBypassProtection && isAlive()`** の 3 条件
  (うち isAlive が false なら除去許容 → **当方の ASM RETURN 注入で isAlive を false にすれば突破可能**)。
- `shouldBypassProtection` = ISoulBypass mixin フィールド。`setBypassProtection` は **isRemoved 中の false 化を拒否**。
- `mixin/LivingEntityMixin`: getMaxHealth を TrialMonolithConfig.trialMonolithHealth に固定、**tickDeath を cannotBeRemoved で cancel**。
- `PersistentEntitySectionManager$CallbackMixin.onRemoveMixin`: cannotBeRemoved で cancel。
- `transformer/method/EntityMethods`:
  - `shouldReplaceHealthMethod` = isSoulProtected || soulDamage>=1。
  - `replaceGetHealth` = soulDamage>=1→0.0f / soulProtected→max(health,1) / 他→20.0f。
  - `modifyHealth` / `shouldOverrideTick`(isSoulProtected || TrialMonolithEntity)/ `updateLastTicks`(ServerLevel.f_143243_ 走査)/ `shouldForceTick`(ITickTracker 前回 tick 比較)。
- `mixin/PlayerMixin`: getCooldownMixin / getAttackStrengthScaleMixin / getCurrentItemAttackStrengthDelayMixin —
  `isOverClocked` なら 0 化(攻撃速度調整、防御ではない)。
- `mixin/ServerLevelMixin`: removePlayerImmediately 前後で `setBypassProtection(true→false)`。
- Item: SoulProtectorItem / SoulBypassItem 系(装備で isSoulProtected 発動、bypass アイテムで解除)。

### 突破可否: **突破可能(最も脆い)**

| 攻撃手段 | TrialMonolith の反応 | 判定 |
|---|---|---|
| die/tickDeath 経由 | tickDeath cancel は **cannotBeRemoved = isAlive() 依存** → 当方 RETURN 注入で isAlive を false にすれば cancel 条件が外れる | ✅ |
| getHealth 経由 | replaceGetHealth が method 置換 → 当方の RETURN 注入より後段なら当方が勝つ(TRANSFORM 順) / 先段でも getHealth 自体を経由しない殺害なら無関係 | ✅ |
| WorldStructureDestroyer map 直接除去 | onRemoveMixin cancel は **コールバック経由の除去**のみ → map 直接除去は素通り | ✅ |
| StateDestroyer | isSoulProtected/soulDamage フラグを反転すれば cannotBeRemoved が初めから false | ✅ |

**推奨突破経路**:
1. **当方の既存能力でそのまま突破可能**。isAlive の RETURN 注入 + WorldStructureDestroyer が決定打。
2. soulDamage 系の即死機構は攻撃側ツールなので不要。

---

## 5. pig2mod (pig2mod-1.20.1-2.4.3) — 委譲解析済み

- root pkg `kakiku.pig2mod`。MxLivingEntity / Pig2 / ATTACK_LEVEL / MAX_FIGHT / MyMixin プラグイン / MyLib / MyHelper / MyXformData / MyUnsafe を解析。
- 詳細は委譲レポート(bg_32b3befd)参照。**突破可能の見立て**。
- `[要補完]` 委譲レポートの出力詳細は一部圧縮で未確認 — 必要なら `background_output(bg_32b3befd)` で再取得して本節を拡充。

---

## 6. プレイヤー殺害拡大(上位即死 + 独自死亡処理) — 実装済み(未実機検証)

### 目的・制約

- 上記 5 Mod の防御は NPC/entity 対象だった既存機構を、**プレイヤーにも**適用する(「1+2+3 実装」+「上位即死 + 独自死亡処理」方針)。
- 制約(ユーザー明言): **専用対策禁止**(他 Mod の Class 名・Item 名を参照して処理を変えるのは禁止)。完全に汎用対策(ASM/Mixin/低レイヤー)のみで突破。リスポーン挙動(2 回死後 Respawn ボタン)は修正しない。

### 新レイヤー(設計v5)

| レイヤー | ファイル | 何をするか |
|---|---|---|
| tick オーバーライド | `hook/LivingEntityMethodImpl.java` | `isKillTarget()` のプレイヤー除外を撤廃しプレイヤーも対象化。`tick()` オーバーライドで毎 tick `forceHealthZero` |
| tick ASM 注入 | `transformer/LivingEntityTransformer.java` | tick / `m_8119_ ()V` をマッチし void RETURN でフック注入 |
| vanilla 防御破壊 | `agent/StateDestroyer.java` | `corruptVanillaDefensiveFields()`: フィールド名に `$` を含む + 防御キーワード一致(alive/valid/invul/protect/immortal/muteki/invincible/fumetsu/safe)の二重条件で特定。boolean 死亡系→true / 防御系→false / 数値→0 / Map→clear |
| プレイヤー専用破壊 | `agent/StateDestroyer.java` | `destroyPlayerDefenses()` = corruptVanillaDefensiveFields + corruptSynchedEntityData。プレイヤーはクラス階層が全 vanilla のため従来の corruptNumericFields/corruptProtectionFlags は即 break → 専用経路 |
| 独自死亡処理 | `agent/FallbackDeathHandler.java`(新規) | vanilla 死亡チェーン妨害時の最終保険。`dead`=true / `health`=0 / `removed`=true を Unsafe 直書き + 非プレイヤーは構造除去委譲。候補名: DEAD_FIELDS={f_20890_,dead} / HEALTH_FIELDS={f_20963_,health} / REMOVED_FIELDS={removed} |
| デーモン分岐 | `agent/KillDaemon.java` | プレイヤー経路は `destroyPlayerDefenses()` / モブ経路は `destroy()` に分岐。discard 後 dead フラグ未成立なら `FallbackDeathHandler.forceDeath()`。ワールド即死(rk.should_kill)中はネイティブスイープを**フラグリセット前に**実行 |
| should_kill honor | `agent/KillTargetRegistry.java` | `isMarkedEntity()` が `rk.should_kill=true` を honor → C++ ネイティブ(random_kill_agent.cpp がキャッシュ)も全対象化。除外判定は `isEntityExcluded` 委譲 |

### 検証状況

- [x] `dead` = `f_20890_`(tsrg LivingEntity 節、~120934 行で確認)
- [x] `Entity.removed` は unmapped → 通常名 `"removed"` が有効(tsrg Entity 節 119026-120909 に該当マッピング無し)
- [x] `forceHealthZero` は LivingEntityMethodImpl.java:218 に存在(isKillTarget:182 / isPlayer:196)
- [x] `gradle compileJava` 成功(19s / Mixin AP 0.8.5 が transformer アノテーションも検証)
- [ ] 実機検証(未実施)

---

## 7. 設計v10 (パケット層 + 非 Living 対象拡大) — 2026-10-09 実装

### 経緯

- ユーザー要件: 「改変するものは Mod のみではなく Minecraft 本体に対しても行うべき (パケットの改変など)」
  「倒すべきエンティティ (正確にはエンティティではないのだが) には ExtraBossRush のようなものも存在」
- 推奨汎用強化 4 項目 (末尾総合結論) は 2026-10-09 時点で**全て実装済み**だった:
  1. levelCallback (`f_146801_`) no-op 差し替え → `WorldStructureDestroyer.clearLevelCallback` + `NoOpEntityInLevelCallback` ✅
  2. isAlive (`m_6084_`) RETURN 注入 → `LivingEntityTransformer.matchesMethod` ✅
  3. フラグ反転対象キーワード (soulProtected/soulDamage/isErased/novelized/muteki 等) →
     `StateDestroyer.DEFENSE_KEYWORDS` に alive/valid/invul/protect/immortal/muteki/invincible/
     fumetsu/safe/novel/erased/soul/forced/phase/shield/guard/barrier 等を実装済み ✅
  4. no-op Set 検出フォールバック → `KillTargetRegistry.tryForceInternalRemove`
     (internalRemove/erase/discard 系 20 メソッド名候補をリフレクション列挙) ✅

### 新規実装 1: PacketDestroyBroadcaster (パケット層・MC 本体介入)

- 構造除去 (WorldStructureDestroyer) は vanilla tracking (ChunkMap/ServerEntity) を経由しないため、
  サーバー側で消えても `ClientboundRemoveEntitiesPacket` がクライアントへ届かずゴーストが残る経路を堵塞。
- 除去確定時 (`purgeFromWorld` 内) に `Level.players()` (`m_6907_`) → 各 `ServerPlayer.connection` (`f_8906_`) →
  `Connection.send(Packet)` (`m_129512_`) で `ClientboundRemoveEntitiesPacket(int...)` を直接配信。
- SRG 名は `build/createMcpToSrg/output.tsrg` で実証 (パケットクラス名自体は dev/本番同一)。
- プレイヤー自身は除外 (vanilla 死亡画面を壊さない)。専用対策なし (vanilla クラス/API のみ参照)。

### 新規実装 2: 世界掃討の非 Living Entity 拡大 (ExtraBossRush 型対策)

- `KillDaemon.sweepWorldAndKill` と `ServerLevelSweepHelper.collectSnapshot` のフィルタを
  「LivingEntity のみ」→「vanilla Entity サブクラス全て」へ拡大 (`ClassValue` キャッシュ判定)。
- ExtraBossRush 系がボスの部品として使う非 Living エンティティ (マーカー/フェーズ管理/盾/
  EnderCrystal 型など) が `rk.should_kill` 世界掃討から漏れない。
- `forceKill` は全経路リフレクション (hurt/die/setHealth は非 Living では安全スキップ) で、
  最終的に `WorldStructureDestroyer.purgeFromWorld` (任意 Entity で機能) で確定するため互換性問題なし。
- ItemEntity / ExperienceOrb 等も掃討対象になるが、`excluded_entities` 設定で調整可能。

### OS レイヤー (Windows/Linux 脆弱性) についての整理

- 本プロジェクトの脅威モデルでは OS 脆弱性は不要: 全 defensive mod は同一 JVM プロセス内で動作し、
  当方は既に JVM 内で最大権限 (Instrumentation + JVMTI + JNI + Unsafe) を持つ。OS 層を突いても
  権限は何も増えない (same-process)。したがって汎用対策は JVM レイヤ (現行) で完結する。
- 既存の「低レイヤー」資産: ネイティブ DLL (Dynamic Attach 直接呼び出し `JVM_EnqueueOperation` /
  JVMTI ClassFileLoadHook / ヒープスイープ / フィールド強制書込) が実質的なネイティブ执行力。
  外部プロセス経路 (署名付き java.exe ヘルパー JVM + 標準 attach) も実装済み (`attachToPid`)。

---

## 5. OmniMobs (omnimobs-0.3.5.3) — 自己アタッチ agent + スタック検査の二重ゲート

### 構成(解析: 生 SRG jar を vineflower 1.10.1 で逆コンパイル)

- パッケージ: `flashfur.omnimobs`。
- **防御の本体は Mixin ではない**。`coremod/CoreModMain` が自己アタッチした Java agent の
  `agentmain` で JVMTI `ClassFileLoadHook` を登録し、`getHealth` 系・Mixin 除去等を**実行時
  retransform** で適用する。`CommonConfig.coremod=false` で全パッチを無効化できる(最高レバレッジ設定)。
- 全防御の関門は **スタック検査**:
  - `entities/anticheat/StackChecker.java`: `java/lang/StackWalker` でスタック全域を走査。
    深部の boolean 判定 (スタックフレームの `getClassName()` を見て外部 mod フレームを検出) が
    最も下位の関門。
  - `entities/anticheat/AccessChecker.java`: `checkAccess` は `ThreadLocal` の DEPTH と
    `StackChecker` の判定を組み合わせ、`notCalledFrom*` 系の結果で
    `performPrivilegedAction`(実ダメージ適用)と `checkDespawnImmunity`(despawn 免疫)を分岐。
  - `java.lang.reflect.` / `java.lang.invoke.` フレームは無条件で「不良」判定。
    `checkForMixins=true` では `@MixinMerged.mixin()=com.randomkillentity.mixin.*` が不良判定。
    → **当方の Mixin 経路・リフレクション経由キルは素通りしない**。

### 唯一通り得る経路

- **ASM 注入 (net.minecraft クラス内、`@MixinMerged` なし)**、
- または **内部 Map/List の直操作・native 直書き** (スタック検査の窓に当方フレームが入らない)。
- `BossEntity.hurt` の実ダメージ適用は `AccessChecker.performPrivilegedAction` 内で、
  `iFrames` / `weakDamageLimit` / `damageCap` を持つ。

### 突破(設計v13/v14)

| 攻撃レイヤー | 何をするか | 効果 |
|---|---|---|
| `StackInspectionNeutralizerTransformer` (v13) | 第三者のクラスで**本体がスタック検査 API を直接呼ぶ boolean メソッド**を構造検出し `return false` 固定 | `isBadCall`/`isBadFrame` 系の最深部関門を false 化 → `notCalledFrom*` が全て素通り → `checkDespawnImmunity` 無効化・`performPrivilegedAction` 通過 |
| `WorldStructureDestroyer` (v13) | フックされた `EntityTickList/EntityLookup/ClassInstanceMultiMap.remove` を呼ばず**内部 Map/Collection を直操作** | 呼び出し元検査の窓に当方フレームが入らず、除去が確実 |
| `AsmMonopolyGuard`/`AsmMonopolyInstaller` (v14) | 共有 `org.objectweb.asm.ClassWriter.toByteArray()` を計装し、**第三者フレーム起点**の変換を元バイト列でパススルー | 自己アタッチ agent の retransform パッチ(getHealth 改変・Mixin 除去等)を**無効化** |

### 検証レベル

- バイトコード解析 + コンパイル/パッケージ成功まで。**実機 (runClient 統合サーバー) E2E は未実施**。
- 実機確認事項:
  - [ ] スタック検査中和により当方のキル/despawn が通るか(混在環境で)。
  - [ ] ASM 独占が自己アタッチ agent の retransform を実際に停止するか(共有 ASM のクラスローダー同一性)。
  - [ ] `java.lang.reflect`/`invoke` フレーム無条件 bad 判定により、当方の反射経路が依然ブロックされる範囲。
  - [ ] `CommonConfig.coremod=false` 相当の導入設定が併用可能か。

---

## 総合結論

| Mod | 防御の性格 | 突破可否 | 決定打 |
|---|---|---|---|
| lal | 三重(ヘッド差し替え+JVMTI 強制+DisableRemoveSet) | 条件付き ✅ | map 直接除去 + internalRemove / BYPASS 立上げ |
| Hyperlink | muteki フィールド + DataWatcher intercept + fumetsu 独自登録 | ✅(fumetsu 要検証) | Unsafe 直書き + map 除去 + config 反転 |
| No-Sugar | Forge イベント post + Player 限定防御 | ✅ | イベントハンドラ解除 + map 除去 + levelCallback 差し替え |
| TheTrialMonolith | isAlive 依存の cannotBeRemoved | ✅ | isAlive の RETURN 注入(既存) |
| pig2mod | 解析済み | ✅ | 委譲レポート参照 |
| OmniMobs | 自己アタッチ agent + スタック検査二重ゲート + 実行時 retransform | 条件付き ✅(要実機) | スタック検査中和 (v13) + 内部構造直操作 (v13) + ASM 独占 (v14) |

**全 Mod に共通して有効な汎用強化(推奨)**:
1. `WorldStructureDestroyer` に **levelCallback(`f_146801_`)を no-op/null に差し替える処理**を追加
   (No-Sugar の setlevelCallback と同等 — 除去後の再登録防止、全 Mod 横断で有効)。
2. `LivingEntityTransformer` の RETURN 注入対象に **isAlive(`m_6084_`)** を追加
   (TrialMonolith の cannotBeRemoved を恒久無効化 — 現状注入対象に含まれるか要確認)。
3. `StateDestroyer` のフラグ反転対象に **soulProtected / soulDamage / isErased / novelized / mutekiForced** を追加。
4. KillTargetRegistry は lal の DisableRemoveSet で無効化される → **「remove が no-op の Set を検出したら
   internalRemove/internalClear を試す」汎用フォールバック**を追加。

---

## 要実機検証リスト

- [ ] lal coremod のヘッド差し替え vs 当方 RETURN 注入の実行順(どちらが勝つか)
- [ ] lal JVMTI FieldModification が Unsafe/JNI 書き込みを検知するか
- [ ] lal EnforcementThread 停止(または gKillProgress からの除外)の有効性
- [ ] Hyperlink fumetsu entity を EntityLookup + ChunkMap 両方から外せるか
- [ ] Hyperlink ENABLE_MUTEKI config の反転で muteki が恒久解除されるか
- [ ] No-Sugar sugar_sword で掃討された entity が levelCallback 差し替え無しで復活しないか
- [ ] TrialMonolith isAlive の RETURN 注入が tickDeath cancel を確実に外すか
- [ ] プレイヤー殺害: mixin 注入防御フィールド($ 接頭辞 + キーワード)の特定精度(偽陽性/偽陰性)
- [ ] プレイヤー殺害: vanilla 死亡チェーン妨害時に FallbackDeathHandler の Unsafe 直書きで死亡確定するか
- [ ] プレイヤー殺害: rk.should_kill 中のネイティブスイープがフラグリセット前に全対象を掃討するか
- [ ] プレイヤー殺害: 2 回死リスポーン挙動が維持されるか(修正しない制約の確認)
- [ ] OmniMobs: スタック検査中和で当方のキル/despawn が通るか
- [ ] OmniMobs: ASM 独占が自己アタッチ agent の retransform を停止するか(共有 ASM のクラスローダー同一性)
- [ ] OmniMobs: `java.lang.reflect`/`invoke` 無条件 bad 判定による当方反射経路のブロック範囲
- [ ] OmniMobs: `CommonConfig.coremod=false` 相当設定との併用
