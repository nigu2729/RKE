# Pig2 完全殺害 設計書 (設計v8)

> 対象: `kakiku.pig2mod.entity.Pig2` (ボス) の「復活不能な完全殺害」
> 根拠: `/tmp/opencode/pig2jar/{MyLib2,Pig2,MyHelper}.javap.txt` (1.20.1 実バイトコード)

---

## 1. 目的

Pig2 ボスを一度のキルで**同一ティック内に復活不能**にし、以後の再スポーン経路
(復活・リバイブ・ロード復元) を全て遮断する。対象は Pig2 に限らず、同じ
「スタック検査 + 毎tickリバイブ」パターンを使う防御/ボスmod 全般に汎用で効く
設計とする。

## 2. 制約 (ユーザー指示)

1. **専用対策禁止**: pig2mod 内部フィールド (`mIsJisatsu` / `f_146795_` 直書き等) に
   クラス名・完全フィールド名ベースで直接触れない。バニラ公開 API
   (`setRemoved(KILLED)` 等) 経由で RemovalReason を正しく立てる。
2. **ユーザー決定 (B採用)**: 「jisatu の Boolean を true にするなどであれば一般名詞での
   判定だから専用対策とは言えない」「whitelist、Mod といった**文字列があるもの**に
   追加するのはあり」。→ **一般名詞ヒューリスティック** (自爆系/防御系キーワード) と
   **構造的メソッドパターン検出** (isThisName*MOD 系) は許容。ただし「柔軟に対応
   できるようにしないと実質的に Pig2 への専用対策になってしまう」ため、**pig2mod の
   クラス名・完全メソッド名・完全フィールド名をハードコードしない**こと。

## 3. Pig2 の復活機構の全容 (javap 検証)

### 3.1 毎tickリバイブ: `Pig2.tick()` の構造 (Pig2.javap L995-1094)

```
Pig2.tick():
  if (MyLib2.isClientThread() && level instanceof ServerLevel) return;   // L996-1008
  if (MyLib2.isServerThread() && level instanceof ClientLevel)  return;  // L1002
  super.tick();                        // Pig.tick → LivingEntity.tick (L1010)
  checkAndAvoidClientGhost();          // L1012 (クライアント幽霊対策)
  if (isEnding()) return;              // ★ L1014-1016 (mIsJisatsu なら即リターン)
  correctMovement();                   // L1018
  myRevive();                          // ★ L1020 (毎tick復活!)
  if (isClientThread()) { if (gLevel4PigIDs.contains(id)) tickLevel4Client(); return; }  // L1021-1031
  if (isServerThread()) {
    switch (mAttackLevel) { case 1: tickLevel1(); ... case 5: tickLevel4(); }  // L1032-1063
    persistenceRequired = true;                     // L1064-1066
    if (getMaxHealth() != 10.0f)                    // L1067-1071
        getAttribute(MAX_HEALTH).setBaseValue(10.0d);  // L1072-1080
    Pig2Mod.gMyHelper.reviveInChunk(this);          // ★★ L1081-1083 (毎tickサーバー復活!)
    if (++mTickCounter % 4 == 0) ...                // L1084-1094
  }
```

### 3.2 `myRevive()` (Pig2.javap L3952-3967)

```
private void myRevive():
  setHealth(10.0f);   // 固定値で回復 (入力値は無視 — 防御側の setHealth オーバーライド)
  revive();           // バニラ: removalReason = null にリセット!!
  deathTime = 0;
```

**最重要事実**: `revive()` はバニラ `Entity.revive()` で `removalReason` を `null` に戻す。
つまり **v7 が setRemoved(KILLED) で removalReason を立てても、同一ティックの
`myRevive()` (L1020) がそれを消す**。v7 単体は原理的に Pig2 に効かない。

### 3.3 `reviveInChunk()` (MyHelper.javap L1958-1976)

```
public void reviveInChunk(Pig2 pig):
  if (pig.isEnding()) return;              // L1960-1963 (mIsJisatsu なら不発)
  if (pig.removalReason != null) return;   // L1964-1967 (RemovalReason が立ってれば不発)
  ServerLevel.getChunkSource().addEntity(pig);  // L1968-1973 (復活)
```

復活条件は **「isEnding()==false かつ removalReason==null」** の2つだけ。
→ **この2つのどちらかを恒久に true / 非null にすれば復活経路は全滅する**。

### 3.4 生リバイブ経路 (ロード復元)

- `Pig2` L392: `PersistentEntitySectionManager.addEntityWithoutEvent` (mod AT で
  `public-f` 化) — チャンクロード時の直接再登録。
- `MyHelper.correctServerLevelEntitiesNow` L1476 周辺: `ServerChunkCache.addEntity`。
- `Pig2.load` L3870: 呼び出し元が `DataCommands` ならロードを**拒否**
  (セーブデータ復元 = セーブスカム対策)。→ ディスク永続化経路は元々塞がれている。

### 3.5 ガード構造: `setRemoved` (Pig2.javap L4234-4265)

```
public void setRemoved(RemovalReason reason):
  if (mIsSetRemoveding) return;                     // L4236-4239 (再入ガード)
  if (MyLib2.isCalledFromOtherModWithin5())         // L4240-4243 (スタック検査)
      { seeFighting(); return; }                    //     他MOD からの呼び出しはキャンセル
  if (levelCallback == NULL && mLevelCallbackBackup != NULL)
      levelCallback = mLevelCallbackBackup;         // L4244-4255 (コールバック復元)
  mIsSetRemoveding = true;                          // L4256-4258
  super.setRemoved(reason);                         // L4259-4261 (バニラ: removalReason = reason)
  mIsSetRemoveding = false;                         // L4262-4264
```

`kill` / `remove` / `hurt` / `addDeltaMovement` / `setPosRaw` も同様に
`isCalledFromOtherModWithin1/5` + `seeFighting()` でガードされている。

### 3.6 スタック検査の収束構造 (MyLib2.javap)

```
isCalledFromOtherModWithin1  (L738-756):  frame[3] のみ判定
isCalledFromOtherModWithin5  (L758-807):  frame[3..7] を判定 (jdk.internal.reflect.* は即true、
                                          EventBus 特殊ケース: frame[i-1] が EventBus かつ同名メソッドならskip)
isCalledFromOtherModWithin10 (L809-837):  frame[3..12] を判定
      └─ いずれも frame[i].getClassName() を isThisNameOtherMOD(String) へ委譲

isThisNameOtherMOD(String) (L367-384):
  if (s.startsWith("kakiku.pig2mod.MyAttacker")) return true;   // L370-374 (例外)
  if (isThisNameMinecraftVanilla(s)) return false;              // L376 (vanilla は他MODではない)
  if (isThisNameMyMOD(s))           return false;               // L379 (自mod は他MODではない)
  return true;                                                 // それ以外 = 他MOD

isThisNameMyMOD(String) (L335-344):
  return s.startsWith("kakiku.pig2mod.");                       // ★ 単一の収束点

isThisNameWhiteListedMOD(String) (L201): ホワイトリスト接頭辞群
isThisNameOtherBadMOD(String) (L407-416):
  return !isThisNameWhiteListedMOD(s) && isThisNameOtherMOD(s); // stopBadThreads 等で使用
```

**収束点**: `isThisNameMyMOD` が false を返す限り `isThisNameOtherMOD` は true
(他MOD扱い) になり、Within1/5/10 が全て発動する。→ **ここへ「自mod」判定を1本
注入すれば、以後の全スタック検査が素通りになる**。

## 4. なぜ既存設計 (v6/v7) では倒せないか

| 設計 | 機構 | Pig2 に効かない理由 |
|---|---|---|
| v6 ServerLevel スイープ | `ServerLevel.getAllEntities` スナップショット → setRemoved | EntityLookup から除去済みの「幽霊エンティティ」を列挙できない。かつ myRevive が同一tick で removalReason を null 化 |
| v7 インライン (現状) | `LivingEntity.tick` HEAD に `setRemoved(KILLED)+kill()` を焼き込み | **スタック検査は素通りできる (バニラフレーム) が、同一tick の `myRevive()` (L1020) → `revive()` が removalReason を null に戻し、`reviveInChunk()` (L1083) が再登録する**。除去と復活が毎tick ループするだけ |

→ 「除去を実行すること」は v7 で既にできている。**足りないのは「removalReason を
維持するロック」= `isEnding()` を恒久 true にすること**。

## 5. 完全殺害の設計 (3本柱)

### 柱1: 自クラス化 (構造的 isThisName*MOD 注入) — ユーザー決定B

**ターゲット**: 任意のクラスが持つ `isThisName*MOD(String)` 系スタティックメソッド
(構造パターンで検出。クラス名・完全メソッド名はハードコードしない)。

**注入内容** (メソッド HEAD):
```
// isThisNameMyMOD / isThisNameWhiteListedMOD 系 (true 系):
if (s != null && s.startsWith("com.randomkillentity.")) return true;
// isThisNameOtherMOD 系 (false 系):
if (s != null && s.startsWith("com.randomkillentity.")) return false;
```

**効果**:
- `isThisNameOtherMOD("com.randomkillentity....")` → false (自mod扱い)
- `isCalledFromOtherModWithin1/5/10` → 全て素通り → `setRemoved` / `kill` / `remove` /
  `hurt` / `addDeltaMovement` / `setPosRaw` が他MODフレームからでも実行可能になる
- `isThisNameOtherBadMOD` → false → `stopBadThreads` がエージェントスレッドを殺さない

**汎用性の根拠**: 検出は「static boolean (String) で、メソッド名に `isThisName` と
`MOD` を含み、本体に `startsWith` 呼び出しが1つ以上ある」という**構造パターン**。
pig2mod 固有の名前は一切使わない。同じパターンを使うあらゆる mod に自動で効く。

**実行経路**: C++ `premain` CFLH (RandomKillAgent) は Forge mod クラスのロード時に
発火するため、`kakiku/pig2mod/xform/MyLib2` もクラスロード直後に変換可能
(globalTransformMethod → TransformerCore.transformForByte)。変換は冪等
(マーカー LDC で再注入防止)。

### 柱2: 自爆フラグ (一般名詞ヒューリスティック) — isEnding ロック

**ターゲット**: 対象エンティティの boolean フィールドのうち、**一般名詞キーワード**に
名前が一致するもの。pig2mod の完全フィールド名 (`mIsJisatsu`) は参照しない。

**追加キーワード** (StateDestroyer の DEFENSE_KEYWORDS 方式を拡張):
```
自爆系 (true に反転):  "jisatsu", "jisatu", "suicide", "selfkill"
防御系 (false に反転): 既存 DEFENSE_KEYWORDS (alive/valid/invul/protect/immortal/muteki/...)
```

**効果**: `mIsJisatsu` は名前中に `jisatsu` を含むため汎用ルールで true に反転される
(一般名詞判定 = 専用対策ではない、ユーザー承認済み)。
- `isEnding()` (L769-773, `return mIsJisatsu`) が true になる
- `tick()` が L1014 で即リターン → **`myRevive()` (L1020) と `reviveInChunk()` (L1083) が
  実行されなくなる** → removalReason が維持される
- `reviveInChunk` 自体のガード (L1961 `isEnding()`) も二重に効く

**注意 (既存コードとの干渉)**: `corruptProtectionFlags` は既に `"remov"` を含む
boolean を true に反転するが、これは `mIsSetRemoveding` (setRemoved 再入ガード) を
**true にして setRemoved を封じる方向**に働く。柱2 のインライン反転 (v7 拡張) は
キル**前**に走るため、**自爆系キーワードのみ**に限定し、"remov"/"dead"/"kill" 等の
除去系単語は含めない (既存 destroy() の事後破壊は従来どおり全キーワードで実行)。

### 柱3: バニラAPI除去 (v7 拡張: flip → setRemoved → kill の順序化)

`EntityInlineKillTransformer` (v7) の注入シーケンスを次の順序に拡張する
(LivingEntity.tick HEAD、スタック中立、全バニラフレーム):

```
1. StateDestroyer.flipSelfDestructFlags((Object) this);   // 柱2 (一般名詞 自爆系→true)
2. this.setRemoved(RemovalReason.KILLED);                 // バニラ API (removalReason = KILLED)
3. this.kill();                                           // 保険 (内部で remove→setRemoved)
```

**なぜこの順序か**:
- 柱2 の反転はリフレクションのみで MC ガードを呼ばないため、フレーム汚染しない
  (ヘルパーは setRemoved の呼び出し前に return 済み → スタック検査に agent フレームが残らない)
- setRemoved は `mIsSetRemoveding` ガード (L4236) と Within5 ガード (L4240) を通るが、
  呼び出し元フレームが `LivingEntity.tick` (バニラ) なので素通りする
- 注入コードが返った後、Pig2.tick は L1014 `isEnding()` → true で即リターン →
  **myRevive / reviveInChunk は同一tick 内で一度も実行されず、removalReason=KILLED が
  維持されたままバニラの除去機構がエンティティを世界から取り除く**

### 3本柱の因果連鎖 (完全殺害の証明)

```
柱1 (MyLib2 自クラス化)
  └─ 全スタック検査 (Within1/5/10) 素通り → バニラ/エージェント双方のキル API が実行可能
柱3 (v7 拡張, tick HEAD)
  ├─ 柱2 flipSelfDestructFlags → mIsJisatsu=true → isEnding()=true   [ロック]
  ├─ setRemoved(KILLED) → removalReason=KILLED                        [除去]
  └─ kill()                                                           [保険]
Pig2.tick 続行 (L1010 super.tick から復帰)
  ├─ L1014 isEnding()==true → return                                  [myRevive 不実行]
  ├─ reviveInChunk ガード (L1961 isEnding) → 不発                     [再登録なし]
  └─ removalReason=KILLED 維持 → バニラが世界から除去                  [完全死亡]
以後: スポーン/リバイブ/チャンクロード復元 (L392 addEntityWithoutEvent) は
      isEnding() ガード付き経路を通るため全て不発
```

## 6. 実装詳細

### 6.1 新規: `SelfClassifierTransformer` (柱1)

- 実装: `ITransformerModule` の新モジュール。`TransformerCore.TRANSFORMERS` に追加。
- `matchesClass`: 全クラスを対象 (コスト回避のため、クラスバイトに `isThisName` の
  ASCII 文字列が含まれる場合のみ詳細走査)。
- `matchesMethod`: 次の構造パターンで判定
  - `ACC_STATIC && return Z && 引数が String 1つ`
  - メソッド名に `isThisName` と `MOD` を含む
  - メソッド本体に `String.startsWith` 呼び出しがある
- `transform` (冪等): マーカー LDC (`com.randomkillentity.` の注入チェック) が無ければ
  HEAD に以下を注入
  - true 系 (`MyMOD` / `WhiteListedMOD`): `if (s != null && s.startsWith(SELF)) return true;`
  - false 系 (`OtherMOD`): `if (s != null && s.startsWith(SELF)) return false;`
- 実行経路: C++ premain CFLH が Forge mod クラスロード時に
  `TransformerCore.transformForByte` を呼ぶため、`MyLib2` もロード直後に変換される。
- 注意: `TransformerCore.isTransforming` 再入ガードはそのまま使用。

### 6.2 変更: `StateDestroyer` (柱2)

- `flipSelfDestructFlags(Object entity)` を新設 (キル前専用・軽量):
  - 対象: エンティティ階層の非 static boolean フィールド
  - 自爆系キーワード `{"jisatsu","jisatu","suicide","selfkill"}` → true
  - 防御系キーワード (既存 DEFENSE_KEYWORDS) → false
  - 「remov / dead / die / kill / dying / destroy / discard」は**含めない**
    (mIsSetRemoveding を true にすると setRemoved が封じられるため)
- 既存 `corruptProtectionFlags` / `corruptVanillaDefensiveFields` は事後破壊用として
  変更なし (自爆系キーワード追加は任意。既存の "remov"→true は KillDaemon の
  事後処理では無害)。

### 6.3 変更: `EntityInlineKillTransformer` (柱3)

- KILL_RULES 一致時の注入シーケンスを
  `flipSelfDestructFlags → setRemoved(KILLED) → kill()` の順に変更。
- 呼び出しは `INVOKESTATIC com/randomkillentity/agent/StateDestroyer.flipSelfDestructFlags
  (Ljava/lang/Object;)V` (void なのでスタック中立)。
- マーカー判定は従来どおり (先頭ルールの LDC で冪等)。

### 6.4 適用順序まとめ

```
クラスロード時 (C++ premain CFLH):
  MyLib2 → 柱1 (isThisNameMyMOD / isThisNameWhiteListedMOD に self 判定注入)
  バニラ → 柱3 (LivingEntity.tick HEAD に flip → setRemoved → kill を注入)
実行時:
  tick HEAD で 柱2 (一般名詞自爆フラグ反転) → 柱3 (バニラAPI除去) → isEnding ロック
  KillDaemon 5ms ループ: 既存 forceKill チェーン (柱1 によりガード素通り) + 事後 destroy
```

## 7. javap 突合検証表 (タスク3)

| # | 設計上の主張 | 根拠 (javap 行) | 状態 |
|---|---|---|---|
| 1 | `isThisNameOtherMOD` は vanilla と自mod 以外を他MOD扱い | MyLib2 L367-384 (L376 vanilla / L379 MyMOD) | ✅ |
| 2 | `isThisNameMyMOD` = `startsWith("kakiku.pig2mod.")` 単一収束点 | MyLib2 L335-344 | ✅ |
| 3 | Within1 = frame[3] のみ判定 | MyLib2 L738-756 | ✅ |
| 4 | Within5 = frame[3..7] + jdk.internal.reflect + EventBus 特殊ケース | MyLib2 L758-807 | ✅ |
| 5 | Within10 = frame[3..12] | MyLib2 L809-837 | ✅ |
| 6 | `isThisNameOtherBadMOD` = !whitelist && otherMOD | MyLib2 L407-416 | ✅ |
| 7 | tick は isEnding() で即リターン | Pig2 L1014-1016 | ✅ |
| 8 | tick は毎回 myRevive() を実行 | Pig2 L1020 | ✅ |
| 9 | tick はサーバー側で毎回 reviveInChunk() を実行 | Pig2 L1081-1083 | ✅ |
| 10 | myRevive = setHealth(10)+revive()+deathTime=0 (removalReason を null 化) | Pig2 L3952-3967 | ✅ |
| 11 | isEnding() = return mIsJisatsu | Pig2 L769-773 | ✅ |
| 12 | setRemoved は mIsSetRemoveding / Within5 でガード | Pig2 L4236-4243 | ✅ |
| 13 | setRemoved 成功時 super.setRemoved で removalReason が立つ | Pig2 L4256-4265 | ✅ |
| 14 | reviveInChunk は isEnding() / removalReason で不発 | MyHelper L1960-1967 | ✅ |
| 15 | 生リバイブ経路 (addEntityWithoutEvent) が存在 | Pig2 L392 | ✅ |
| 16 | セーブ復元は DataCommands 呼び出しを拒否 (永続化復活は元々塞がれている) | Pig2 L3870 | ✅ |
| 17 | isRemoved() は mIsAddingNewPig2 中は false を偽装 | Pig2 L883-891 | ✅ |

## 8. ハーネス検証計画 (タスク5)

1. **柱1 検証**: サーバー起動後、MyLib2 の `isThisNameOtherMOD("com.randomkillentity.X")`
   が false を返すこと (リフレクションで確認)。
2. **柱2 検証**: Pig2 生成後、`StateDestroyer.flipSelfDestructFlags(pig2)` 実行 →
   `isEnding()` が true、`tick()` が即リターンすることを確認。
3. **柱3 検証 (完全殺害)**: Pig2 をスポーン → 1 tick 後に
   - `isRemoved() == true` (removalReason が KILLED のまま)
   - `isEnding() == true`
   - 60 ticks (3秒) 経過後も世界に存在しない (reviveInChunk / addEntityWithoutEvent 不発)
   - `getAliveServerPigs()` が空
4. **回帰**: Slime (KILL_RULES 1件目) が従来どおり倒れること。バニラ mob への
   誤爆なし。
5. **停止確認**: `kill @e` 等のバニラコマンドが Pig2 に対して通るようになったこと
   (柱1 の副次効果)。

## 9. リスクと対策

| リスク | 対策 |
|---|---|
| `mIsSetRemoveding` を誤って true 化 → setRemoved 封鎖 | 柱2 は自爆系キーワードのみ。除去系単語はキル前に含めない |
| 注入フレームがスタック検査に残る | 柱2 ヘルパーは setRemoved 呼び出し前に return する void メソッド (フレーム非残留) |
| 他 mod の同名パターン誤爆 | 構造パターン (isThisName+MOD+startsWith) が厳密なので、該当するのは同種の判定クラスのみ。誤爆しても「自パッケージを自mod扱い」するだけ = 無害 |
| 再トランスフォームによる二重注入 | マーカー LDC チェックで冪等化 |
| クライアント/サーバー両側の挙動差 | 柱2/3 はサーバースレッドの tick 内で完結。クライアント幽霊 (gLevel4PigIDs) は checkAndAvoidClientGhost が isEnding 後に実行されるため干渉なし |
| `stopBadThreads` がエージェントスレッドを殺す | 柱1 の isThisNameOtherBadMOD → false 化で解消 (副次効果) |

---

*設計v8 / 2026-08-10 / 根拠: javap 実バイトコード検証済み (タスク3 完了)*
