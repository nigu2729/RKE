# RandomKillEntity 作業進捗ログ

> このファイルは作業の経緯・現在地・次の行動を記録するためのものです。
> セッションが変わっても、このファイルを見ればどこからでも再開できます。
> **作業を進めたら必ずこのファイルを更新すること。**

## 2026-10-17: 解析対象 防御mod の再読解 + 設計v13 (スタック検査中和) / v14 (ASM 独占層) 実装 — 完了

### 解析 (KillSources 内の生 SRG jar を逆コンパイルして再読解)

- 防御の本体は **Mixin ではなく「自己アタッチ Java agent + `Instrumentation.retransformClasses`」**。
  agentmain が JVMTI `ClassFileLoadHook` を登録し、getHealth 系・Mixin 除去等を実行時に行う。
- 全防御の関門は **スタック検査** (`java/lang/StackWalker` 系)。「呼び出し元スタックに外部 mod の
  フレームがあれば免疫/拒否」する二重ゲート (`checkDespawnImmunity` / `performPrivilegedAction`)。
- `java.lang.reflect.` / `java.lang.invoke.` フレームは無条件で「不良」判定。
  → 当方の Mixin 経路・リフレクション経由キルは素通りできない。
- 実測: `DefenseNeutralizerTransformer.CANDIDATE_TOKENS` は当該防御mod に対し `isBypass` の
  1 件しか一致せず、主要防御は全く中和できていなかった (構造トークン不足)。

### 実装 (設計v13): スタック検査の中和 — `StackInspectionNeutralizerTransformer`

- 第三者のクラスのうち、**本体でスタック検査 API を直接呼ぶ boolean 判定メソッド**を
  構造のみで検出し、`return false` (= 不良フレーム無し) に固定する。
  - 検出 API: `java/lang/StackWalker` (StackFrame 含む) / `sun/reflect/Reflection.getCallerClass` /
    `Thread.getStackTrace` / `Throwable.getStackTrace`。
  - 最深部の関門を false 固定 → それを呼ぶ上位ラッパ (allowed / notCalledFrom / checkDespawnImmunity)
    が連鎖的に「外部 mod フレーム無し」と評価する。
- `RKTransformer` のパイプライン判定に `isStackInspectionCandidate` (定数プール走査) を追加。
- 汎用 (mod 名ハードコードなし)。SAFE_PREFIXES により vanilla/Forge/ライブラリ/自 mod は除外。

### 実装 (設計v13): ワールド構造の内部直操作 — `WorldStructureDestroyer`

- `purgeFromWorld` の構造除去を、**フックされたメソッドを呼ばず内部 Map/Collection を直操作**へ変更。
  - `EntityTickList` / `EntitySectionStorage`→`EntitySection.storage` (ClassInstanceMultiMap) /
    `EntityLookup` (byId/byUuid) の内部コンテナをフィールド名非依存で全走査し、id (Integer) と
    UUID の両方をキーに直接 remove。呼び出し元スタック検査の窓に当方フレームが入らない。
- `EntityTickList.remove` / `ClassInstanceMultiMap.remove` / `EntityLookup.remove` を一切呼ばない。

### 実装 (設計v13): `ServerLevelTransformer` の冪等化 + 重複排除

- `shouldBlockRevival` 呼び出しの presence check を追加 (周期 retransform での二重注入防止)。
- 基底 `Level` の addFreshEntity は `LevelAddFreshEntityTransformer` が担当するため、
  `ServerLevelTransformer` は **ServerLevel のみ**対象へ縮小 (同一メソッドへの二重注入を解消)。
- `TransformerCore` の「rkKillSweep を実装済み」という不正確コメントを実態に修正 (未実装)。

### 実装 (設計v14): ASM 独占層 — `AsmMonopolyGuard` / `AsmMonopolyInstaller`

- 狙い: 競合 mod の「自己アタッチ agent + 自前 ASM 変換」を、そもそも無効化する。
- 共有 `org.objectweb.asm.ClassWriter.toByteArray()` を計装し、**第三者 mod のフレーム起点**の
  変換では、その writer が元にした `ClassReader` の入力バイト列 (= 変換前クラス) を返して
  **完全パススルー**させる。当方 (自パッケージ) の変換だけが実際にバイトを書ける。
- 起点フレームは `StackWalker` で判定。許可接頭辞 = 自 mod / ASM / ModLauncher(cpw.mods) /
  Mixin(org.spongepowered) / バニラ・Forge・Mojang / 標準ライブラリ。
- 安全側: `ClassWriter(ClassReader,int)` 由来の writer のみ対象 (新規クラス生成 `ClassWriter(int)`
  は `classReader==null` で無影響) / 元バイト列が取れなければ通常処理へフォールバック /
  計装対象 ASM の classloader から guard クラスが解決できない場合は計装しない /
  `-Drk.asm.monopoly=false` で無効化。既定 ON。
- `RKAgent.agentmain` の `init(inst)` 直後に `AsmMonopolyInstaller.install(inst)` を配線。

### 検証 (今回)

- `compileJava` / `buildAgentJar`: BUILD SUCCESSFUL。新規クラスが agent jar に同梱されることを確認
  (`AsmMonopolyGuard` / `AsmMonopolyInstaller$AsmGuardTransformer` /
  `StackInspectionNeutralizerTransformer` / `ServerLevelTransformer` / `WorldStructureDestroyer`)。
- **未実施**: 防御mod 実機 (runClient 統合サーバー) での E2E (即死成立 / 防御復元停止 / ASM 独占の
  実効性)。導入環境での実測が必要。

### 既知の限界 (要検討)

- ASM 独占は「共有 (unshaded) `org.objectweb.asm.ClassWriter`」にのみ有効。ASM を自パッケージへ
  shade した mod、および `ClassWriter(int)` で書く変換 (元 reader 無し) には効かない。
- 起点フレーム許可リストは広め (誤爆回避優先)。`org.*` / `io.*` 等に置かれた第三者 mod の
  スタック検査・ガードは `SAFE_PREFIXES` の都合で対象外 (既存設計の制約)。
- ASM 独占は第三者 mod の read→write 変換を広く止めるため、副作用 (当該 mod の機能停止) が
  出る可能性がある。問題時は `-Drk.asm.monopoly=false` で切り分け。

## 2026-10-12: 設計v11/v12 検証 + クラス置き換え戦への対抗 (フック再注入方式) — 完了

### ユーザー実装 (設計v11) の検証結果 — 正しい (1件のみ修正)

- **JVMTI 関数ホットパッチ** (`patchJvmtiDefenseFunctions`): jvmtiEnv の関数テーブルが
  jvm.dll 内の共有実装を指す点を利用した SetBreakpoint / SetField*Watch / ForceEarlyReturn* の
  `xor eax,eax; ret` 化。実機で `pinned 9` を確認。設計判断は正しい (Clear* は故意に無傷 →
  自前の掃討処理が動き続ける)。x86 用 ret imm16 の引数バイト数に誤りがあったため修正:
  SetBreakpoint = 16 (jlocation が jlong)、ForceEarlyReturnLong/Double = 16。
  ⚠️ Linux は `rkPatchCode` が no-op (未実装) — 対応するなら mprotect + memcpy。
- **ネイティブエージェント DLL 中和** (`installNativeAgentBlock`):
  `LdrRegisterDllNotification` (reason==1) + Agent_OnLoad/OnAttach/OnUnload の return 0 化 +
  Toolhelp 全モジュール走査。ローダロック下でのヒープ確保回避、system32/SysWOW64/WinSxS/
  jvm.dll/自 DLL 除外、冪等再適用 — すべて妥当。
- **clearAllLoadedDefenseHooks**: 全ロード済みクラスの breakpoint/field watch 網羅解除 — OK。
- **DefenseNeutralizerTransformer** (Java): 第三者クラスの should*/is*Guard/restore 系メソッドを
  構造パターンで既定値 return 化。SAFE_PREFIXES で vanilla/Forge/自mod を除外 — OK。
- **DefenseDisarmService**: 1 秒周期で `NativeLoader.neutralizeRivalDefenses()` (JNI) を再適用 — OK。

### Pig2「net.minecraft パッケージで Entity を置き換え」情報の真偽 — **誤報** (2.4.3 実証)

- `pig2mod-1.20.1-2.4.3.jar` 内に `net/minecraft` 配下のクラスファイルは **ゼロ** (jar 全走査)。
- `MyXformer` (ITransformer) の target は `net/minecraft/client/Minecraft` の 1 メソッドのみ
  (m_91341_ 関連の操作で、Entity 置き換えではない)。
- `MyXformer2` (ClassFileTransformer + retransformClasses) の置き換え対象は
  **競合「抹消系」mod のクラス** (`apphhzp.lib.ClassHelper` /
  `net.apphhzp.eraserservice.EntityEraserTransformer`) — 他 mod の jar を走査して
  バイトコードを収集し自前版で再定義する「クラス置き換え戦」機構。
- バニラへの介入は **mixin 織り込み** (refmap 実証: Entity.setRemoved/isRemoved/setLevelCallback、
  LivingEntity.tick/getHealth/setHealth、ServerLevel.addEntity/tick、EntitySection.remove、
  PersistentEntitySectionManager 等) と **AT public-f 開放**。織り込み ≠ パッケージ作成/置き換え。
- 情報源は「mixin で net.minecraft クラスへ注入している」ことを「置き換え」と誤記した可能性。

### 対抗実装 (設計v12): フック再注入方式 — スナップショット巻き戻しは実機で否定

- **廃止した設計**: 定義時バイトのスナップショット保持 + 差分検出時に強制復元。
  実機で 2 つの破綻: (1) 正当な再直列化 (定数プール再番号付け) まで「敵対改変」と誤判定し
  別シリアライズのバイトを押し付け → `IncompatibleClassChangeError: Entity.getPosition is final`
  でレジストリイベント失敗 (TrialMonolith の InvaderMonolithEntity リンケージ破壊)。
  (2) RETURN 注入系が冪等でないため再変換でフックが二重注入 → バイト肥大 → 自分自身との
  復元戦 (Player 89975→92448 の成長をログで観測)。
- **採用した設計** (`ClassIntegrityGuard` + 冪等化):
  1. **チェーン末尾化**: 当方 ClassFileTransformer を周期 remove → re-add して登録順の末尾に置く。
     後発 attach の敵対 transformer が先に書いても当方が最終バイトを決定する。
  2. **周期 retransform** (3 秒): ガード対象 (バニラ エンティティ管理中核:
     Entity/LivingEntity/Player/ServerPlayer/LocalPlayer/ServerLevel/SynchedEntityData +
     `net/minecraft/world/level/entity/*` パッケージ) を再変換し、冪等トランスフォーマー群に
     フックを「敵対バイトの上に」再適用させる。バイト差し替えには干渉せず能力だけを再主張。
  3. **冪等化**: LivingEntityTransformer (RETURN 注入 + setHealthHook HEAD) に
     既存フック呼び出しの presence check、LevelAddFreshEntity / AddEntityBlocker に
     shouldBlockRevival presence check を追加。CountermeasureService の二重注入バグも根絶。
- **E2E** (runServer, TrialMonolith/No-Sugar/Curios/ApexBoss 同時ロード): `Done (13.222s)`、
  戦争ログ 0、IncompatibleClassChangeError 0。周期 retransform が TrialMonolith の
  transformer バグ (bootstrap CL の null 参照 NPE) を露呈させるが当方 catch で吸収され実害なし。

### pig2mod の dev 依存について

- ユーザーが `fg.deobf("curse.maven:pig2-1163863:6669342")` を追加したが、
  **pig2mod は専用サーバーでロード不可** (`Pig2Mod.<init>` が dist ガード無しで
  `LocalPlayer` を参照 → RuntimeException: invalid dist DEDICATED_SERVER)。
  build.gradle でコメントアウト済み (理由記載)。**Pig2 対戦テストは runClient で行うこと。**

### 観測された残課題

- Linux での JVMTI 関数ホットパッチ未実装 (`rkPatchCode` が false 返却のみ)。
- 周期 retransform が防御 mod のロード時 transformer (mixin 等) を毎回再実行させる —
  CPU コストと第三者バグの露呈トレードオフ。周期は DefenseDisarmService (3 秒) で調整可能。
- 実戦キル E2E (runClient + KillSources 5 Mod + ExtraBossRush) は未実施。

## 2026-10-09: 起動クラッシュ 3連鎖の修正 (NoSuchMethodError → NCDFE → attach誤判定) — 完了

### 1. 画像のエラー: `NoSuchMethodError: EntityType.m_142235_` — 修正済み

- **原因**: `run/mods/randomkillentity-1.0.0.jar` (本番用 **reobf/SRG名** Jar) を
  dev環境 (Mojmapランタイム) の mods フォルダに置いていたため二重ロード。
  `example.bosskiller.BossKillerMod` の static init (coverAll 走査の `type.getBaseClass()` =
  SRG `m_142225_`) が SRG 名を Mojmap 環境で呼んで NoSuchMethodError → Mod ロード失敗。
- **修正**:
  - `run/mods/` から本番 Jar を削除 (dev は build/classes からロードされる)
  - `BossKillerMod` 堅牢化:
    - static init を try/catch (例外が漏れても Mod ロード全体が落ちない)
    - `ForgeRegistries.ENTITY_TYPES` 走査を static init から **初回 EntityJoinLevelEvent 時へ遅延**
      (construct 時点では他 Mod の Entity Type が未登録のため、走査漏れも同時に修正)
    - `onJoin` 全体を catch (保険リスナーが EventBus へ例外を投げない)

### 2. 本番 Jar 単体の `NoClassDefFoundError: com/randomkillentity/utils/RKLog` (Bug C 再発) — 修正済み

- **原因**: jar タスクが `com/randomkillentity/utils/**` と `agent/**` を exclude しており、
  埋め込み jarjar は `META-INF/jarjar/metadata.json` が無いと JarJarSelector に
  ロードされない (PROGRESS.md 既知解析の通り) → 本番では utils も agent も不可視。
- **修正** (build.gradle):
  - dev 用: `runtimeOnly files(buildDir/tmp/earlyboot/randomkillentity-earlyboot.jar)` を復活
    (旧コメントは古いファイル名 `randomkill-earlyboot.jar` を指していたので現行名に修正)
  - 本番用: `generateJarJarMetadata` タスク新設 — **`META-INF/jarjar/metadata.json` を生成**し
    jar に同梱 (earlyboot + agent の2 Jar を列挙)。processResources ではなく jar タスク側に
    付与するため dev (exploded) では二重ロードしない
- 検証: `build/libs/randomkillentity-1.0.0.jar` 内に metadata.json + 2 埋め込み Jar を確認

### 3. Java Agent セルフアタッチが「成功なのに失敗扱い」 — 根本修正済み

- **症状** (runServer 実機ログ): agentmain は完全成功 (全フッククラスロード /
  RKTransformer 登録 / sweeper ready / KillDaemon 起動) しているのに、
  `NativeLoader.forceLaunchJavaAgent()` が false を返し
  「Native self-attach failed」→ 再アタッチ試行が 4 連発 + 誤ネイティブフォールバック。
- **根本原因** (RandomKillAgent/src/java_agent_launcher.cpp):
  レスポンスパイプを `FILE_FLAG_OVERLAPPED` **なし**で作成しながら
  `waitForResponse` が OVERLAPPED 構造体付きで `ConnectNamedPipe` を呼んでいた。
  同期ハンドルに非 NULL OVERLAPPED を渡すと MSDN の通り「接続成功と誤報告」し、
  実機ログどおり `ConnectNamedPipe failed: 0` (ok=TRUE → err=ERROR_SUCCESS → else 分岐) で
  即 false 返却していた。
- **修正**:
  - C++: `createResponsePipe` に `FILE_FLAG_OVERLAPPED` 追加 + `ConnectNamedPipe` が
    TRUE を返した場合も接続済みとして扱う防御
  - Java: `RKAgent.agentmain` 末尾で `System.setProperty("rk.agent.loaded","true")` マーカー設定 /
    `RKAgentLoader.loadJavaAgent` が false 返却でもマーカーが立っていれば成功扱いに変更
    (パイプ解析失敗時の誤フォールバック防止、再アタッチ連発も解消)
- **DLL リビルド**: `RandomKillAgent/rebuild_native.bat` (JAVA_HOME 要設定) →
  `out/build/x64-Release/output/random_kill_native.dll` (278,016 bytes) を
  `src/main/resources/META-INF/native/windows/` へ配置。
  ⚠️ 旧 CMakeCache が `C:/Users/coela/...` パスで残っていたため `out/build/x64-Release` は削除してから再構成。
  ⚠️ CMakeLists は `JAVA_HOME` 環境変数を要求する (env 付きで呼ぶこと)

### E2E 検証 (runServer 実機, 2026-10-09 00:30) — ALL PASS

- [x] Mod ロード: `Setup complete` + `Done (9.549s)` — BossKillerMod/NCDFE/NSME エラーゼロ
- [x] セルフアタッチ: `Force-launching Java Agent` が**1回のみ** → `Java Agent loaded successfully`
- [x] エージェント: 全フッククラスロード / sweeper ready ×2 (earlyboot+agent re-bind) /
      bridge field 設定 / KillDaemon 起動
- [x] 本番 Jar: jarjar metadata.json 同梱確認

### 観測された残課題 (起動エラーとは別論点・未着手)

- `KillDaemon.loop:83` (`ClientEraser.run()`) 時に TrialMonolith 側が
  `Failed to find super Class` をログ (捕捉済み・サーバー起動に影響なし)。
  当方のクラス再変換が TrialMonolith のトランスフォーマーと衝突した際の防御側ログ。
  今後のキル発火 E2E (KillSources 5 Mod 実戦) での要観察。

## プロジェクト概要

- **Mod**: RandomKillEntity (MC 1.20.1 / Forge 47.4.20 / Java 17)
- **目的**: `-javaagent` JVM引数や `jdk.attach` (VirtualMachine) を一切使わずに、
  C++ネイティブDLLから Java Agent (`RKAgent.agentmain`) を自己アタッチで強制起動する
- **C++プロジェクト**: `C:\Users\coela\Documents\C++\RandomKillAgent\`
- **Java Mod プロジェクト**: `C:\Users\coela\Documents\新しいフォルダー\RandomKillEntity\`
- **Modの機能**: 生存エンティティを毎tick、設定確率でランダムに殺す

## アーキテクチャ (現状)

```
EarlyBootstrapService (ITransformationService)
  └─ onLoad() @ MC-BOOTSTRAP段階 (Forge/FML未ロード)
      └─ RKAgentLoader.load()
          ├─ [Java Agent経路] loadJavaAgent()
          │    NativeLoader.loadNativeLibrary() → DLLロード
          │    → extractAgent() → /META-INF/jarjar/randomkill-agent.jar を展開
          │    → NativeLoader.forceLaunchJavaAgent()  (C++がDynamic Attachに直接アクセス)
          │         Linux: Unixソケット /tmp/.java_pid<pid> + SIGQUIT
          │         Windows: jvm.dll の JVM_EnqueueOperation + 名前付きパイプ
          │         → instrument agent を自己アタッチ → agentmain → retransform
          └─ [フォールバック] loadNativeAgent() (Java Agent失敗時)
               NativeLoader.initializeNativeAgent() (JVMTI ClassFileLoadHook)
```

- 変換対象クラス: `LivingEntity` + `Player` / `ServerPlayer` / `LocalPlayer` (設計v3で拡大)
- **ブートストラップ経路のクラスは Forge 非依存でなければならない** (重要制約)
- 殺害アーキテクチャは **設計v3 (ワールド全体フラグ `rk.should_kill`) + 設計v4 (構造破壊レイヤー)** へ移行済み
  → 末尾「設計v3/v4 (ワールド全体フラグ + 構造破壊レイヤー)」節参照

## 検証済み事項

- [x] attachプロトコル検証 (実験A/A2/B/C/D、JDK 17/21/25、Linux+Windows実機)
- [x] C++統合モジュール `java_agent_launcher.{h,cpp}` を RandomKillAgent に統合
- [x] Java統合: `NativeLoader.forceLaunchJavaAgent()` / `RKAgentLoader.loadJavaAgent()` 書き換え
- [x] ビルド検証: Linux .so (gcc) / Windows DLL (zig 894KB) / javac
- [x] 実機E2E: Windows / Linux 両方で `greet()=HELLO`
- [x] 検証済みDLLを `src/main/resources/META-INF/native/{windows,linux}/` に配置 (macOS dylib は未検証のため現状維持)

## 設計v2 (RK真・即死・11項目) 実装状況 — 2026-08-06

> 防御mod の無効化対策 (フック除去・health書き戻し・クライアント隠蔽) を突破して確実に殺す設計。
> 全11項目の実装完了。ビルド検証 (Linux .so / Windows DLL / javac 全クラス) 済み。

| # | 項目 | 実装箇所 | 状態 |
|---|------|----------|------|
| 1 | 対象UUIDレジストリ (native連携) | `agent/KillTargetRegistry.java` (isMarkedEntity は native からも JNI 呼び出し) | ✅ |
| 2 | 除外設定 (2系統: `rk.excluded_entities` / `RandomKillConfig.EXCLUDED_ENTITIES`) | `KillTargetRegistry` / `config/RandomKillConfig.java` | ✅ |
| 3 | 5ms 常駐デーモン (health書き戻し対抗) | `agent/KillDaemon.java` (CYCLE_MS=5, RESCAN_INTERVAL=10) | ✅ |
| 4 | 6段エスカレーションキルチェーン | `KillDaemon.forceKill()`: invulnerableTime=0 → hurt(genericKill,MAX) → hurt(100000) → generic() → kill() → setHealth(0)+die() | ✅ |
| 5 | 実死亡検証 (fake対策) | `isDeadOrDying`(m_21224_) / `isRemoved`(m_213877_) で各段検証 | ✅ |
| 6 | メソッドフック (RETURN注入・生存確認) | `hook/LivingEntityMethodImpl.java` (HOOK_CALLS カウンタ) / `transformer/LivingEntityTransformer.java` | ✅ |
| 7 | 状態破壊 (死亡後デスポーン保証) | `agent/StateDestroyer.java` (destroy + deletePersistenceFiles) | ✅ |
| 8 | フック除去対抗 (再注入) | `agent/CountermeasureService.java`: HOOK_CALLS 停滞検出 → retransformClasses 再適用 (2秒周期) | ✅ |
| 9 | クライアント消去 (RemovalReason.KILLED) | `agent/ClientEraser.java`: 100ms間引き・プレイヤー除外・SRG名解決 | ✅ |
| 10 | Unsafe 直接書込 (health/invulnerableTime) | `utils/UnsafeUtils.java` / `KillDaemon.forceKill()` | ✅ |
| 11 | C++ JVMTI 最終執行層 | `random_kill_agent.cpp`: sweepEntitiesAndKill (ヒープタグ走査) + forceWriteFloatField/IntField | ✅ |

### 設計v2 での配線 (RKAgent)

```
agentmain → KillTargetRegistry.setInstrumentation(inst)
         → ensureHookClassesLoaded (8クラス: TransformerCore / KillTargetRegistry / KillDaemon /
            StateDestroyer / CountermeasureService / ClientEraser / ILivingEntityMethodHook / LivingEntityMethodImpl)
         → init(): INSTALLED_TRANSFORMER 保持 + addTransformer → retransformClassTransformer
         → KillDaemon.start()

KillDaemon.loop() (5ms周期):
  CountermeasureService.runCheck()  → フック停滞検出 → 再注入
  ClientEraser.run()                → クライアント側消去
  targets 非空なら rescanAndEnforce (10サイクル毎) / enforceWatched
  100サイクル毎 (500ms) → NativeLoader.sweepEntitiesAndKill()  ← 項目11 配線済み
```

### 項目11 ネイティブ執行層の配線 (2026-08-06 追加)

- **課題**: `sweepEntitiesAndKill` / `forceWriteFloatField` / `forceWriteIntField` が Java 側から
  一切呼ばれていなかった (デッドコード) + C++ の `findLivingEntityClass` / `triggerRetransform` が
  `strstr("LivingEntity")` 部分一致で `LivingEntityRenderer` 等に誤マッチするバグ
- **修正**:
  - C++: `LIVING_ENTITY_SIG` 完全一致 (`Lnet/minecraft/world/entity/LivingEntity;`) でクラス検索/retransform
  - C++: `initializeNativeSweeper()` JNI 新設 — Java Agent 経路でも ClassFileLoadHook 二重登録なしに
    JVMTI env + `KillTargetRegistry.isMarkedEntity` キャッシュのみ初期化 (二重変換防止)
  - Java: `RKAgentLoader.loadJavaAgent()` 成功後に `initializeNativeSweeper()` 呼び出し追加
  - Java: `KillDaemon.forceKill()` 末尾に JNI 直書き保険 (`forceWriteIntField`/`forceWriteFloatField`)
  - Java: `KillDaemon.loop()` に 500ms 周期の `sweepEntitiesAndKill()` 追加
- **新エクスポート**: `initializeNativeSweeper` (計 6 JNI + 3 JVMTI = 9 シンボル)

### 設計v2 での検証

- [x] javac コンパイル検証: agent + utils + config + event + service + RandomKillEntity 全27クラス EXIT:0
  (ForgeGradle 実クラスパス `build/classpath/runClient_minecraftClasspath.txt` + ASM 9.9.1 + slf4j 2.0.1 で検証)
- [x] import 欠落 2件修正: `KillDaemon → UnsafeUtils` / `CountermeasureService → LivingEntityMethodImpl`
- [x] Linux .so 再ビルド: `librandom_kill_native.so` (1.9MB, g++ + 自作 linux jni_md.h) → `META-INF/native/linux/` 配置
- [x] Windows DLL 再ビルド: `random_kill_native.dll` (1.3MB, zig 0.14.1 x86_64-windows-gnu) → `META-INF/native/windows/` 配置
- [x] JNI エクスポート整合: NativeLoader の native 宣言 6件 ↔ C++ エクスポート 6件 完全一致
  (forceLaunchJavaAgent / forceWriteFloatField / forceWriteIntField / initializeNativeAgent / initializeNativeSweeper / sweepEntitiesAndKill)
- [x] javac 再検証 (項目11配線後): KillDaemon / NativeLoader / RKAgentLoader 変更 → 全27クラス EXIT:0 (2026-08-06 23:00)
- [x] 実機E2E (Windows runClient) は未実施 — 次セッションで確認

## 即死不発動の根本原因修正: findServer() 書き換え (2026-08-07)

### 問題

- **症状**: 設定確率でフラグは立つ (`rk.should_kill=true`) が、エンティティが即死しない
- **根本原因**: `KillDaemon.findServer()` がリフレクションで `MinecraftServer.getServer()` / `m_130759_` /
  `m_129759_` を呼んでいたが、**MC 1.20.1 の MinecraftServer に static getServer() は存在しない**
  (javap で実証) → findServer() が常に null → スイープが即 return → 殺害コード一切実行されず

### 修正 (KillDaemon.java)

- **Path 1 (primary)**: `net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()`
  - Forge 本体のクラスは reobf されないため dev/本番で名前が同一
  - 統合サーバー (シングル)・専用サーバーの両方で現在のサーバーを返す (javap 確認: public static)
- **Path 2 (fallback)**: `Minecraft.getInstance().getSingleplayerServer()` — クライアント統合サーバー専用
  - SRG: `getInstance = m_91087_` / `getSingleplayerServer = m_91092_` (srg_to_official_1.20.1.tsrg 実証)
- `invokeStatic()` ヘルパー追加 (static メソッドのリフレクション呼び出し用)
- **潜在バグ修正**: `getGenericSource()` の SRG が `m_269341_` (実体は `fellOutOfWorld()`!) だったのを
  `m_269264_` (`DamageSources.generic()`) へ修正

### 検証 (ヘッドレス専用サーバー)

- `run/world` (New World コピー) + `kill_probability=1.0` で `gradlew.bat runServer --nogui` 実行
- 1回目: `findServer: ServerLifecycleHooks -> net.minecraft.server.dedicated.DedicatedServer` 確認。
  全11エンティティが除外対象 (excluded=11) でキル未発火 → `excluded_entities=[]` に変更
- 2回目: **`forceKill Slime dead=true removed=true` ×48 全成功**
  `stages=[1invuln=true][2hurtMAX=true][3hurt100k=true][4hurtGeneric=true][5kill=true][6die=true][health0=false]`
  → 6段エスカレーションキルチェーン全段動作、実死亡 (fakeなし) 検証OK
- 証跡: `run/logs/debug.log` (バイナリ混入のため `grep -a` 必須。検証後ログは削除済み)

### クリーンアップ (2026-08-07)

- `[RK-DIAG]` 診断ログ全削除: KillDaemon.java (findServer 2箇所 + sweep/forceKill 診断 + 死んだカウンタ変数
  levelCount/entityTotal/livingTotal/excludedTotal/killFailures + stages StringBuilder) / GlobalKillFlag.java
  (tickSeen + KILL FLAG FIRED)
- `kill_probability` を 1.0 → **0.01** へ復元 (ユーザー元設定。excluded_entities は変更なし)
- テスト遺物削除: `run/world` (コピー) / `eula.txt` / `server.properties` / サーバー生成JSON / crash-reports /
  13:28以降のサーバー実行ログ (12:56以前のユーザーログは温存)
- **ビルド検証**: `gradlew.bat build` → BUILD SUCCESSFUL (RK-DIAG 除去後)

## クラッシュ修正履歴

### 1. `NoClassDefFoundError: net/minecraftforge/fml/config/IConfigSpec` — 修正済み

- **原因**: `RKAgentLoader.load()` → `JVMArgsInjector.inject()` → `RandomKillEntity.LOGGER` の
  静的参照が `RandomKillConfig` → `ForgeConfigSpec` → `IConfigSpec` を引き込み、
  MC-BOOTSTRAP段階 (Forgeクラス未ロード) で NoClassDefFoundError
- **修正**:
  - `utils/RKLog.java` 新設 (slf4j直接・Forge非依存ロガー)
  - `RKAgentLoader` / `NativeLoader` / `JVMArgsInjector` の `RandomKillEntity.LOGGER` → `RKLog.LOGGER`
  - `RKAgentLoader.load()` から `JVMArgsInjector.inject()` 呼び出しを削除 (jdk.attach不要化)
- **検証**: slf4jのみのクラスパスで4クラスがコンパイル可 / Windows実機E2E再実行で `greet()=HELLO` ✓

### 2. `ResolutionException: Modules main and randomkillentity export package com.randomkillentity.config to module minecraft` — 調査中

- **発生**: 2026-08-05 16:22 の `runClient` (IntelliJ経由)
- **症状**: ゲーム起動前に JPMS モジュール解決で失敗
  `ModuleLayerHandler.buildLayer` → `TransformationServicesHandler.buildTransformingClassLoader`
- **仮説**: 開発用 exploded出力 (module `main`) と、どこかのJAR (module `randomkillentity`) が
  同一パッケージ `com.randomkillentity.*` を export して衝突
- **2026-08-06 調査メモ**:
  - `build/libs/randomkillentity-1.0.0.jar` が存在 (8/5 16:13 ビルド由来)
  - `run/mods/` は空
  - 「JAR削除で直る」という前回診断は効果なし → ForgeGradleのモジュールパス構築を要調査
  - → **TODO: ForgeGradle 6 の runClient がモジュールパスに何を載せるかコードで確認する**

### 3. `[RK] Agent JAR not found in resources` — 非ブロッカー・未修正

- **原因**: `buildAgentJar` の成果物 `randomkill-agent.jar` は `jar` タスク (最終成果物) にのみ
  組み込まれ、開発用 `processResources` (build/resources/main) には含まれない
- **影響**: dev環境では Java Agent 自己アタッチ経路が失敗 → ネイティブJVMTIフォールバックで動作
  (ログ: `[RandomKill Native] Successfully transformed LivingEntity` → 機能は生きている)
- **修正候補**: `processResources` に agent jar のコピーを追加 (タスク順序に注意)

## 環境メモ

- **WSL (drvfs) では gradlew 実行不可** (`java.io.IOException: Input/output error`)
  → ビルド/実行は Windows側 (IntelliJ) で実施。`--no-watch-fs` でも不可
- Windows DLLビルド: zig クロスコンパイル (WSL上):
  `/tmp/opencode/zig-x86_64-linux-0.17.0-dev.1552+79dc16a0e/zig c++ -target x86_64-windows-gnu`
- 検証済みバイナリ: `/tmp/opencode/rka-build-win/random_kill_native.dll` / `/tmp/opencode/rka-build-linux/librandom_kill_native.so`
- E2Eテスト一式: `/tmp/opencode/java-side/` (Windows実行時は `/mnt/c/.../.tmp-e2e/` へコピーして実行)
- javac 単体: `/tmp/opencode/jdk/jdk-17.0.20+8/bin/javac -proc:none -cp <slf4j-api-2.0.1.jar> -d <out> <files>`
  slf4j-api: `/mnt/c/Users/coela/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/2.0.1/f48d81adce2abf5ad3cfe463df517952749e03bc/slf4j-api-2.0.1.jar`

## 次にやること

1. **[優先] ResolutionException (#2) の根本原因特定と修正** — ForgeGradleのrunClientモジュールパス構築を調査
2. (任意) #3: processResources への agent jar 組み込みで Java Agent 経路をdevでも有効化
3. Windowsで `runClient` 再実行 → 起動確認

## Option A 自己完結検証完了 (2026-08-08) — 全項目 PASS

### 背景

前セッションで発見した3障害 (Bug A/B + VerifyError + sweeper FindClass失敗) を全て修正・実機検証した。

### Java 側修正 (RKTransformer.java, ユーザー適用)

- `injectLoadClassHook` (ModuleClassLoader への loadClass 注入) を**完全削除** → 変換は
  `isTargetClass` 一致時 `TransformerCore.transformForByte` のみ。VerifyError の原因が根絶。

### C++ 側修正 (random_kill_agent.cpp: initializeNativeSweeper)

- **根本原因**: earlyboot CL コンテキストでは `FindClass(".../KillTargetRegistry")` が agent JAR 内
  クラスを解決できない (native static メソッドの第2引数 `jclass` が NativeLoader クラス自身である
  ため FindClass はそもそも不要だった)
- **修正**: `GetStaticFieldID(nativeLoaderClazz, "agentKillTargetRegistry", "Ljava/lang/Class;")`
  + `GetStaticObjectField` で Java 側ブリッジフィールドから直接 Class 参照を取得 → NewGlobalRef
  → `isMarkedEntity` メソッドIDキャッシュ。フィールド欠落/ヌル時は logError + JNI_FALSE。
- **Java 側ブリッジ** (RKAgentLoader.java: 既存): `NativeLoader.agentKillTargetRegistry =
  ClassLoader.getSystemClassLoader().loadClass("com.randomkillentity.agent.KillTargetRegistry")`
  を agent アタッチ成功直後 (initializeNativeSweeper 呼び出し前) に設定。
- **logInfo/logError に `std::flush` 追加** — stdout はリダイレクト時にフルバッファリングされ、
  強制終了 (taskkill /F) で成功ログが失われる問題を修正。

### ビルド手順 (記録)

1. ツールチェーン: MSVC (VS 18 Community, cl.exe 14.51.36231) + ninja。
   `vcvars64.bat` → `ninja -C out/build/x64-Debug` → `out/build/x64-Debug/output/random_kill_native.dll` (137,216 bytes)
2. DLL を `src/main/resources/META-INF/native/windows/` へコピー
3. `gradlew.bat buildEarlyBootJar` を**明示実行** — ⚠️ `runClient` が `buildEarlyBootJar` を
   自動で走らせない場合があり、**09:13 の旧 earlyboot jar (旧 MinGW DLL 2,603,558 bytes 内包) が
   そのままクラスパスに載って旧 DLL がロードされる罠**を確認。jar 内 DLL サイズ検証 (137,216) 必須。
4. `gradlew.bat runClient --no-daemon` (JAVA_HOME=jdk-17)

### runClient 実機検証結果 (全項目 PASS)

- ✅ `NoClassDefFoundError` (Bug A) = 0
- ✅ `ResolutionException` (Bug B) = 0
- ✅ `VerifyError` = 0 (RKTransformer 修正の効果)
- ✅ `Agent failed to start` = 0
- ✅ `[RK] Force-launching Java Agent via native self-attach` → `Agent_agentmain CALLED` →
  `Java Agent loaded successfully` → `Agent loaded successfully in onLoad` (エージェント全経路成功)
- ✅ `[RK] KillTargetRegistry pre-loaded into bridge field` (ブリッジ設定成功)
- ✅ `[RandomKill Native] initializeNativeSweeper: sweep layer ready (Java Agent path)` — **新 DLL の
  成功メッセージ確認。sweeper エラー `Failed to find KillTargetRegistry` 消滅**

### クリーンアップ

- `run_build.bat` / `rebuild_dll.bat` / `print-run.init.gradle` / `/tmp/opencode/jni` (一時 jni_md.h) 削除済み
- build.gradle からの参照なし確認済み
- 未対応: #2 ResolutionException は Option A では発生せず (runClient 実機でゼロ確認)。根本原因
  調査 (モジュールパス構築) は引き続き #2 の TODO として残す。

## 設計v3/v4 (ワールド全体フラグ + 構造破壊レイヤー) — 2026-08-09

> 設計v2 (対象UUIDレジストリ) から **設計v3 (ワールド全体フラグ)** へ移行済み。
> 全ソース読了 + javap 裏取り完了。動作修正は行わない (2回死は設計v3の構造的必然 — 後述)。

### 設計v3: ワールド全体フラグ `rk.should_kill`

- `event/GlobalKillFlag.java` — サーバー側 START tick のみ抽選。`rk.should_kill=true` を立て、
  `AUTO_RESET_AFTER_TICKS=200` (10秒) で自動OFF (安全網)
- 注入対象拡大: `LivingEntity` に加え `Player` / `ServerPlayer` / `LocalPlayer`
  (`transformer/RKTransformer.java` / `transformer/LivingEntityTransformer.java` に v3 コメント明記)
- `hook/LivingEntityMethodImpl.java` — RETURN再注入フック (setHealth/heal/die) で should_kill チェック、
  `SHOULD_KILL_CACHE_MS=100` (100ms TTL) キャッシュ、`UnsafeUtils` で health 実体フィールドを直書き
- `hook/LivingEntityMethodEvent.java` — enum `RETURN` / `AFTER` / `BEFORE`

### 設計v4: 構造破壊レイヤー (WorldStructureDestroyer)

- `agent/WorldStructureDestroyer.java` (javadoc: 「設計v4: 構造破壊レイヤー」)
- `EntityTickList` / `EntitySectionStorage` / `EntityLookup` の内部フィールドを直接操作して
  エンティティを世界構造から剥がす (pig2mod-1.20.1-2.4.3 `removeTheEnemiesInTheServerLevel` の汎用移植)
- 対象ModのClass名・Item名等を一切参照しない**汎用対策のみ** (ASM/Mixin/低レイヤー殴り合い。ユーザー要件)

### 殺害チェーンの相互関係 (現行)

```
GlobalKillFlag (START tick 抽選 → rk.should_kill=true, 200tick 自動OFF)
  ├─ LivingEntityMethodImpl (RETURN再注入: setHealth/heal/die)
  │    └─ should_kill=true なら Unsafe で health=0 強制 → 即死
  ├─ KillDaemon (5ms常駐) — 6段エスカレーションキルチェーン:
  │    invulnerableTime=0 → hurt(genericKill,MAX) → hurt(100000) → generic()
  │    → kill() → setHealth(0)+die() → 各段 isDeadOrDying/isRemoved 検証
  │    → 末尾 Unsafe health 直書き保険 (health0=false 時の最終手段)
  ├─ StateDestroyer — 死亡後デスポーン保証 (状態破壊 + persistence 削除)
  ├─ WorldStructureDestroyer (設計v4) — 世界構造 (EntityTickList/SectionStorage/Lookup) から剥離
  └─ CountermeasureService — LivingEntityMethodImpl.hookCalls() 停滞検出 (約6秒=3連続)
       → retransformClasses でフック再注入 (再適用は高々1回/30秒)
```

- `agent/KillTargetRegistry.java` — 二段構え: LOCAL_TARGETS (ConcurrentHashMap) + プロパティ `rk.kill_targets`。
  スイープ間引き: SWEEP_MIN_INTERVAL_MS=5000 / CLASS_CACHE_TTL_MS=30000 / MAX_PURGE_DEPTH=4

### 2回死 (リスポーン後に再び即死) の経路・原因 — 確定

ユーザー報告: 「2回死んでから Respawn ボタンが押せる」。以下の3要因が重なる **設計v3の構造的必然** であり、
意図的な動作。**修正しない** (ユーザー判断)。

1. **`ServerPlayer.restoreFrom` が health=0 をコピー** — リスポーン時、新 ServerPlayer は
   `this.setHealth(old.getHealth())` で死亡済み old (health=0) の値をそのまま引き継ぐ
2. **フラグ残留 (最大200tick=10秒)** — 死亡後も `rk.should_kill=true` が生きており、リスポーン直後も有効
3. **RETURN再注入フックが Unsafe で health=0 を再強制** — リスポーン直後の setHealth/heal が
   フックで遮られ、health 実体が 0 に直書きされる → フラグOFF (10秒) まで即死が繰り返される

### javap 裏取りメモ (2026-08-09)

- `PlayerList.placeNewPlayer(Connection, ServerPlayer)` — getHealth/setHealth ペア存在 (bytecode 724/727)。
  役割は未精査 (2回死の説明には不要)
- `ServerPlayer.respawn(ServerPlayer, boolean)` — players.remove → removePlayerImmediately(RemovalReason.DISCARDED)
  → restoreFrom → setId → setPos → 自 getHealth/setHealth コピー → ForgeEventFactory.firePlayerRespawnEvent
- `ServerPlayer.die` 先頭は `ForgeHooks.onLivingDeath` (キャンセル可能)
- javap 実行は **8.3短縮名必須** (cmd.exe のクォート対策):
  `cmd.exe /c "C:\PROGRA~1\Java\jdk-17\bin\javap.exe -cp <recomp.jar> -p -c <Class>"`
- 検証JAR: `C:\Users\coela\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\
  1.20.1-47.4.20_mapped_official_1.20.1\forge-1.20.1-47.4.20_mapped_official_1.20.1-recomp.jar` (mojmap)
- git: コミット履歴なし (全ファイル ステージ済み A/AM) → v3/v4 移行日時の特定は不可

## 設計v6 (ServerLevel 合成メソッド注入でスタック偽装) — 2026-08-10

> 殺害ガード (pig2mod のスタック検査 `isCalledFromOtherModWithin1/5`、`getHealth` データドリブン化) を
> 回避して確実に殺す新設計。**専用対策禁止** (mIsJisatsu 直書き / pig2mod 内部フィールド依存は不可) の
> 制約下で、バニラ公開API のみで実現する。

### 背景・原理

- pig2mod は kill 呼び出し時のスタック (frame[3]) を検査し、他MODフレームならブロックする
- → **バニラクラス (`net/minecraft/server/level/ServerLevel`) のフレームから kill すれば検査を素通りできる**
- 実現方法: `ServerLevel` に合成メソッド `rkKillSweep()` を ASM 注入し、`tick` メソッド HEAD から呼ぶ。
  kill 実行時のスタックは全てバニラフレームになる (専用対策ゼロ、バニラ公開APIのみ:
  `setRemoved(RemovalReason.KILLED)` + `kill()`)
- `setRemoved(KILLED)` をバニラAPI経由で呼べば `f_146795_` (removalReason) が正しく立ち、
  `reviveInChunk` の復活条件 (`f_146795_==null`) を自然に遮断 — 専用対策不要の理由

### 変更ファイル (Java Mod 側)

| ファイル | 変更 |
|----------|------|
| `agent/transformer/ServerLevelTransformer.java` | **新規**。`matchesClass` (dot/slash両対応) / `matchesMethod` (`tick`/`m_8793_` + BooleanSupplier desc) / `transform` (合成メソッド追加 + HEAD に `ALOAD 0` + `INVOKESTATIC` 注入、二重注入ガードあり)。合成メソッドは `collectSnapshot` → instanceof Entity → `setRemoved(RemovalReason.KILLED)` → `kill()` を SRG/dev 名切り替えで生成 |
| `agent/ServerLevelSweepHelper.java` | **新規**。`collectSnapshot(Object)` が `m_8583_`/`getAllEntities` で Iterable 取得 → LivingEntity 判定 (ClassValue キャッシュ) → `KillTargetRegistry.isMarkedEntity` → `preKill` (UnsafeUtils で `f_19802_`=0, `f_20963_`/health=0) → スナップショット返却。**高速パス**: `rk.should_kill` OFF かつ `targets()` 空なら毎 Tick の全エンティティ列挙を回避 |
| `agent/TransformerCore.java` | `ServerLevelTransformer` 登録 + `SafeClassWriter` (`RKTransformer.SafeClassWriter(cr, COMPUTE_FRAMES\|COMPUTE_MAXS, null)`) 採用 |
| `agent/RKTransformer.java` | `isTargetClass` に `net/minecraft/server/level/ServerLevel` 追加 |
| `agent/CountermeasureService.java` | `SERVER_LEVEL` 定数 + `isTargetClass` に追加 |
| `agent/RKAgent.java` | `ensureHookClassesLoaded` 配列に `ServerLevelSweepHelper` 追加 |

### 変更ファイル (C++ 側)

| ファイル | 変更 |
|----------|------|
| `src/random_kill_agent.cpp` | `isHookTargetClass` に `net/minecraft/server/level/ServerLevel`、`HOOK_TARGET_SIGS` に `Lnet/minecraft/server/level/ServerLevel;` 追加 |

### 検証済み SRG 名 (srg_to_official_1.20.1.tsrg)

- `ServerLevel.tick` = `m_8793_ (Ljava/util/function/BooleanSupplier;)V`
- `ServerLevel.getAllEntities` = `m_8583_ ()Ljava/lang/Iterable;`
- `Entity.setRemoved` = `m_142467_ (Lnet/minecraft/world/entity/Entity$RemovalReason;)V`
- `Entity.kill` = `m_6074_ ()V`、`RemovalReason.KILLED` (リネームなし)

### 実行経路 (C++ 初期化フロー確認済み)

`Agent_OnLoad` → `initializeAgent` → CFLH 登録 → `triggerRetransform`。
ServerLevel はサーバー起動時に既にロード済みのため、HOOK_TARGET_SIGS への追加で必ずリトランスフォーム適用される。
スナップショット収集はサーバースレッド上で同期的に実行 (CME 防止: 収集中に状態変更しない、キルは返却後に合成メソッド側で実行)。

### ビルド検証 (2026-08-10)

- [x] javac 検証 (JDK 21, `--release 17`): ServerLevelSweepHelper / ServerLevelTransformer / TransformerCore /
  RKTransformer / CountermeasureService / RKAgent 全6クラス **EXIT:0**
  (クラスパス: `build/classes/java/main` + `earlybootstrap` + ASM 9.9.1 一式)
  → 同時に `KillTargetRegistry.targets()/isMarkedEntity` / `UnsafeUtils.writeIntField/writeFloatField` の
  シグネチャ一致も確認 (javac が実クラス解決で通過)
- [x] C++ 構文検証: g++ `-include cstdint` (MSVC/MinGW の推移インクルードを模擬) で EXIT:0
  (既存の unused-parameter 警告 1件のみ。`<cstdint>` 直インクルード欠落は既存問題)
- [x] **Windows DLL 再ビルド (MSVC) 成功**: `vcvars64.bat` + `cmake --build out\build\x64-Debug` → BUILD_EXIT=0
  (`rebuild_native.bat` 相当を WSL から cmd.exe 経由で実行。既存 C4100 警告のみ)
- [x] 新 DLL 検証: `strings` で `net/minecraft/server/level/ServerLevel` (isHookTargetClass) と
  `Lnet/minecraft/server/level/ServerLevel;` (HOOK_TARGET_SIGS) の両方を含むことを確認
  (md5: `67435e1f...` = 旧 DLL `bac1080b...` と差分あり、変更が反映済み)
- [x] 新 DLL を `src/main/resources/META-INF/native/windows/random_kill_native.dll` に配置 (879,616 bytes)
- [ ] **未実施**: `gradlew.bat buildEarlyBootJar` 明示実行 + `runClient` E2E — 次セッションで確認

### 残 TODO

1. [x] `gradlew.bat buildEarlyBootJar` 明示実行 (旧 DLL 混入防止、jar 内 DLL サイズ 879,616 検証)
   - 2026-08-10 セッションで `build.bat` (JAVA_HOME=jdk-17 固定) 経由で実行 → **BUILD SUCCESSFUL in 18s**
   - 補足: WSL から cmd.exe へ複雑なコマンドラインを渡すとクォートが壊れるため、
     .bat ファイル経由でのみビルド可能 (`build.bat` をプロジェクト直下に置いた)
2. [ ] `gradlew.bat runClient --no-daemon` (JAVA_HOME=jdk-17) 実機 E2E — kill 発火 + ガード突破確認

---

## 設計v7 (LivingEntity.tick インラインキル注入 — メソッド単位の文字列判定) — 2026-08-10

> ユーザー提案「クラス文字列判定で指定できるのであれば、メソッドなども適用できる気がする」を実装。
> v6 (ServerLevel スイープ) が Pig2 に効かなかった根本原因を特定し、列挙不要の方式へ転換した。
> **専用対策禁止** (mIsJisatsu 直書き / pig2mod 内部フィールド依存は不可) は引き続き遵守。

### v6 が Pig2 に効かなかった根本原因 (特定済み)

- `ServerLevelSweepHelper.collectSnapshot` は `ServerLevel.getAllEntities` (`m_8583_`) で全エンティティを
  **列挙** する。これは `EntityLookup` を読むため、pig2mod / `WorldStructureDestroyer.removeFromEntityLookup`
  (`m_156810_`) で Lookup から剥離された「幽霊エンティティ」はスイープから不可視になる。
- **v7 は列挙を一切しない**: 対象エンティティ自身の `tick` 冒頭で判定・除去するため、
  Lookup から消されても tick は継続する限り確実に発動する。

### 設計方針

- `EntityInlineKillTransformer` が `LivingEntity.tick()` の **HEAD** にインライン注入する
  - クラス名ルール: `getClass().getName().equals("net.minecraft.world.entity.monster.Slime")`
  - 表示名ルール: `getDisplayName().getString().contains("Pig2")`
  - 一致時: `setRemoved(RemovalReason.KILLED)` + `kill()` (バニラ公開APIのみ)
- **完全バニラフレーム**: 注入コードはエンティティ自身の tick 内でバニラメソッドだけを呼ぶ。
  mod フレームを積まないため pig2mod のスタック検査 (`isCalledFromOtherModWithin1/5`) を素通り
  (v6 の設計思想をエンティティ単位へ拡張)
- **フラグ非依存**: `rk.should_kill` (確率 0.0001/tick) が OFF でも確定的に発動
- **C++ 側は変更ゼロ**: `LivingEntity` は既に `isHookTargetClass` (line 69) と
  `HOOK_TARGET_SIGS` (line 354) に登録済み。ロード時 CFLH + 遅延アタッチ時 `triggerRetransform` の両経路で
  新しい Java トランスフォーマーが適用される

### 変更ファイル

| ファイル | 変更 |
|----------|------|
| `agent/transformer/EntityInlineKillTransformer.java` | **新規**。`KILL_RULES` 文字列表、`matchesClass` (dot/slash)、`matchesMethod` (`tick`/`m_8119_`)、`transform` (HEAD 注入、SRG/dev 名切替、マーカー LDC で二重注入ガード) |
| `agent/TransformerCore.java` | `TRANSFORMERS` リスト先頭に `new EntityInlineKillTransformer()` 登録 |

### 検証済み SRG 名 (srg_to_official_1.20.1.tsrg、Entity ブロックは line 119026)

- `LivingEntity.tick` = `m_8119_ ()V`
- `Entity.setRemoved` = `m_142467_ (Lnet/minecraft/world/entity/Entity$RemovalReason;)V`
- `Entity.kill` = `m_6074_ ()V`
- `Entity.getDisplayName` = `m_5446_ ()Lnet/minecraft/network/chat/Component;`
- `Component.getString` = 未リネーム (dev/SRG 同一)、`RemovalReason.KILLED` = リネームなし

### ビルド・検証結果 (2026-08-10)

- [x] javac (JDK 21, `--release 17`): EntityInlineKillTransformer + TransformerCore **EXIT:0**
- [x] **実クラス検証**: 本物の `LivingEntity` バイトコード
  (`forge-1.20.1-47.4.20_mapped_official_1.20.1.jar` から抽出) に全トランスフォーマー
  (v7 + v6 + 既存) を適用 → 1215 bytes 増、`tick` 内に Slime/Pig2 LDC・setRemoved・kill を確認
- [x] **JVM バイトコード検証**: 変換後クラスを `-Xverify:all` でロード成功
  (カスタム親優先ローダー経由、HotSpot 実検証器が全メソッド検証) — **PASS**
- [x] `gradlew.bat compileJava buildEarlyBootJar` → **BUILD SUCCESSFUL in 18s**
  (build/classes/java/main に新クラス反映、earlyboot/agent jar 再生成、DLL は v6 のまま)

### 残 TODO

1. `runClient` 実機 E2E — `RK_LOG_ENABLED=1` で起動し、Pig2 撃破 + スライム軍団消滅を確認
2. 撃破後、スライム分裂 (Slime は死亡時分裂) の挙動確認 — 必要なら `KILL_RULES` 調整
3. 不要になった v6 スイープ (ServerLevelTransformer / ServerLevelSweepHelper) の削除判断

---

## タスク5: 実機 E2E 検証 (設計v8 §8) — 2026-08-10 — OVERALL FAIL (柱1 のみ起因)

### 実行方法

- build.gradle runClient に一時 `args '--quickPlaySingleplayer', 'New World'` を追加 (TEMP コメント付き)
- `JAVA_HOME=/tmp/jdk-17.0.2 RK_HARNESS=1 RK_LOG_ENABLED=1 nohup ./gradlew runClient` で起動
- ハーネス: `harness/HarnessAutoVerify.java` (ServerStartedEvent でゲート、watchdog スレッドで結果書出し後に stop)
- 結果: `/tmp/opencode/task5_result.txt` (+ `run/task5_result.txt`)。ゲームは手動 `kill -9` で終了

### 検証結果 (設計書 §8 の 5 検証 + 回帰)

| 検証 | 結果 | 観測 |
|---|---|---|
| 柱1 自クラス化 (MyLib2 への注入) | **FAIL** | `isThisNameOtherMOD("com.randomkillentity.…") = true` (期待 false) / `isThisNameMyMOD = false` (期待 true) / WhiteListedMOD = false (情報) |
| 柱2 自爆フラグ反転 | PASS | flip 前 isEnding=false → 後 true、isRemoved 維持 false (flip は除去しない) |
| 柱3a 完全殺害 (1tick) | PASS | B (id=220): t=1 で isRemoved=true / removalReason=KILLED / isEnding=true (インラインキル起因の証明) |
| 柱3b 無復活 (60tick) | PASS (世界) | `level.getEntity(B)=null`、isRemoved 維持 (KILLED)、インスタンス走査 (spawn±128) = 0 → **リバイブ不発の証明** |
| 回帰 (Slime) | PASS | S (id=221): t=1 で KILLED、t=60 世界不在 |
| 柱3b 簿記 (gAliveServerPigs) | **FAIL** (設計期待 0) | `size()=15` / contains B=true / isThereAnyPigs=true → **pig2mod 簿記ゴースト** (設計 §7-4 で予測済み) |
| 停止確認 (kill @e 副次) | **FAIL** | `kill @e[type=pig2mod:pig2]` exit=1 だが D.isRemoved()=false → バニラキルが Pig2 に効かない |

### 根本原因 (柱1 のみ・1 箇所・デッドコードバグ)

`SelfClassifierTransformer.matchesMethod` L54-58:

```java
if ((method.access & Opcodes.ACC_STATIC) == 0) return false;
if (!"Z".equals(method.desc)) return false;                          // ← L57: 常に false
if (!method.desc.equals("(Ljava/lang/String;)Z")) return false;      // ← L58: 正しいが到達不能
```

- `MethodNode.desc` は完全ディスクリプタ `(Ljava/lang/String;)Z`。単独 `"Z"` と等しくなることは**絶対にない**
- → `matchesMethod` が全メソッドで false → SelfClassifierTransformer は**一切注入しない** (完全デッドコード)
- クラスロードタイミングは無関係 (agent アタッチ完了 15:51:56 < MyLib2 ロード 15:52:09。LivingEntity は変換 hit 確認済み)
- **修正: L57 を削除するのみ** (L58 が厳密一致 `(Ljava/lang/String;)Z` を既に担保) → タスク6

### 副次 (kill @e が Pig2 に効かない) の整合解釈

- 柱1 不活性 → `isThisNameOtherMOD` が true のまま → pig2mod の setRemoved ガード
  (mIsSetRemoveding / Within5、javap row 12) がバニラキルを遮断 → D 生存
- exit=1 は「コマンド成功」を意味するだけで、対象エンティティが実際に除去されたわけではない

### 検証パイプライン自体は正常動作

- agent 自己アタッチ成功 (15:51:51-56)、LivingEntity 変換 hit 確認 (`[RK-NATIVE-HOOK]` ログ)
- ハーネスは全チェックを実機上で実行・結果書出し成功 → テスト経路に問題なし

### 備考

- 「New World」は過去セッションのセーブを使い回し → gAliveServerPigs=15 の内訳は
  旧セーブ由来の残留エンティティ + B のリーク (世界走査 0 とは矛盾しない)
- タスク5 終了時点でハーネス・quickplay 引数は削除済み (クリーン状態に復元)

### 残 TODO (タスク6)

1. `SelfClassifierTransformer` L57 削除 (matchesMethod デッドコード修正) → ビルド
2. 再 E2E: 柱1 PASS (isThisNameMyMOD("com.randomkillentity.…") → true) + 副次 (kill @e で D 除去) を確認
3. フレッシュワールドで再計測 (旧セーブ残留を排除し gAliveServerPigs=0 を確認)

---

## タスク6: パラドックス解明 + 実キルパス安全検証 (設計v9 フラグゲート整合) — 2026-08-11

### 経緯

タスク5 の `SelfClassifierTransformer` L57 修正 (matchesMethod デッドコード) 後、柱1 は PASS に転じた
(run6: isThisNameOtherMOD(us)=false / isThisNameMyMOD(us)=true / WhiteListedMOD=true)。
しかし新たなパラドックスが発生:

- **`[timer] fired rk-t1/t2/t60/t70` は毎回出るのに、hop()/hop2() 内の検証が一切実行されない**
- 柱3a/柱3b/回帰/副次/EGG スポーンは全て hop()/hop2() 内 → 全滅 → 全 run が OVERALL TIMEOUT FAIL (5/5)

### 根本原因: pig2 MyPlugin が Runnable 引数メソッドを空化 (パラドックス完全解明)

MyPlugin (pig2 の ILaunchPluginService) を javap で逆解析した結果:

```
MyPlugin.handlesClass(Type, boolean):
  MyLib2.isThisNameOtherBadMOD(className) → true なら EnumSet.of(Phase.AFTER) (変換対象)
  com.randomkillentity.* はホワイトリスト外・非バニラ → true
MyPlugin.processClass (reason == "classloading"):
  transform_emptyMethodWithRunnableArg() を実行
    → ClassNode.methods を走査し、引数型に Runnable を含むメソッドを makeMethodEmpty() で空化
```

- ハーネスの `hop(server, label, Runnable task)` / `hop2(...)` は**両方 Runnable 引数**
- → クラスロード時に本体が空化される → Timer から呼ばれても何も起きない (PROBE-C/D 消失)
- PROBE-A/B (server.tell 直送) は Runnable 引数メソッド経由でない → RAN する (run9 で実証)

**run9 PROBE A-D 対照実験** (ハーネスコメント記載の判定基準どおり):

| PROBE | 経路 | 結果 | 判定 |
|---|---|---|---|
| A | `server.tell` + ラムダ | **RAN** | tell 経路は生存 |
| B | `server.tell` + 匿名 Runnable | **RAN** | tell 経路は生存 |
| C | `hop2(server, "t1", new Runnable(){...})` | 消失 | hop 系メソッド本体死 |
| D | `hop(server, "t1", () -> {...})` | 消失 | hop 系メソッド本体死 |

→ 「ラムダ専用経路死」「tell 経路死」「タイマー生成死 (サーバ飢餓)」は**全て否定**。
唯一の原因は hop()/hop2() の空化 (Runnable 引数メソッド) と確定。

### 柱1 PASS と hop 空化の整合: MyLib2 はクラスローダごとに 2 コピー

柱1 (SelfClassifierTransformer 注入検証) が PASS するのに hop() が空化される矛盾は、
**MyLib2 が 2 つのクラスローダに別々にロードされる**ことで解消:

| コピー | ローダ | ロード時期 | 注入状態 | 参照元 |
|---|---|---|---|---|
| launch/service 層 | bootstraplauncher 系 (service ロード) | agent アタッチ**前** | **未注入** (OtherMOD(us)=true のまま) | MyPlugin がクラス変換時に使用 |
| mod 層 | ModuleClassLoader (TransformingClassLoader) | agent アタッチ**後** | **注入済み** (柱1 PASS) | ハーネスがサーバ起動時に参照 |

- MyPlugin の handlesClass は **launch 層 MyLib2** を参照 → 自クラスを OtherMOD 判定 → 変換対象 → hop 空化
- ハーネスの柱1 は **mod 層 MyLib2** を参照 → 注入済み → PASS
- `RKAgent.retransformClassTransformer` (L111) は `cpw.mods.cl.ModuleClassLoader` のみ対象 →
  **launch 層 MyLib2 は二度と再注入されない** (恒久的に未注入のまま) — クラスロードタイミング説は否定済み
- 実証: run8e fingerprint で 6 クラス (HarnessAutoVerify + 匿名クラス $2-$6) が sys=cl 完全一致
  (同一バイト列が両ローダで別々にロードされる)

### 実キルパスは pig2 の影響を受けない (設計の安全確認)

| キルパス | 所在 | Runnable 引数 | pig2 による空化 |
|---|---|---|---|
| KillDaemon | agent jar → `appendToSystemClassLoaderSearch` → **system CL** | dispatchOnServerThread あり | **されない** (ModLauncher パイプライン外、MyPlugin 到達不能) |
| StateDestroyer | agent jar (同上) | なし (Object/UUID/File 引数のみ) | されない (そもそも Runnable 引数なし) |
| EntityInlineKillTransformer | **vanilla** LivingEntity.tick へ注入 | なし (注入コード自体) | されない (pig2 は vanilla を変換しない: isThisNameMinecraftVanilla → false) |

- **run6 実証**: flag=ON 固定で D (L1182) / Pig2 (L1184) が即死 → **インラインキルは pig2 に妨害されず作動**
- スポーンエッグ産 Pig2 の即死 (ユーザー本来の要望) は EntityInlineKillTransformer 経由 → **完全無傷**
- transform_render (renderer 名のみ) / transform_OtherTransform (retransformClasses 差し替え) は本パスに非干渉

### フラグゲート整合 (run6 vs run7) — 修正付き

| run | フラグ設定 | 観測 | 整合 |
|---|---|---|---|
| run6 | flag=ON 固定 | D/Pig2/S 即死 (L1182/1184) | フラグ ON → インラインキル発動 ✓ |
| run7 | KillDaemon.stop + killProbability=0.0 + 検証窓 tick<10 のみ ON | t70-before-hop flag=false (L1282) | フラグ OFF は確認 ✓ |
| run8e | — | fingerprint 6/6 equal=true | 両ローダ同一バイト列 |
| run8f | RK_NO_PIG2=true | S のみスポーン | Pig2 非依存の回帰 |
| run9 | 同上 | PROBE A/B RAN、C/D 消失 | 空化経路の特定完了 |

**修正**: 先行メモの「run7 EGG 生存」は**誤り**。run7 ログに `[柱3b] EGG spawned` は存在しない
(grep 確認)。EGG スポーン (L450-455) は hop() 内にあり、hop 空化で**スポーン自体が実行不能**。
→ フラグ OFF 下での EGG 生存は **E2E 未検証** (ハーネス経路の問題であり、製品キルパスの欠陥ではない)。

### 結論 (タスク6 総括)

1. パラドックスの正体 = **pig2 MyPlugin が Runnable 引数メソッドを空化** (hop/hop2 が該当)
2. 柱1 PASS との矛盾 = **MyLib2 のクラスローダ二重ロード** (launch 層=未注入 / mod 層=注入済み)
3. 実キルパス (KillDaemon / StateDestroyer / EntityInlineKillTransformer) は全て pig2 の影響外 →
   **製品の即死機能は正常動作** (run6 で D/Pig2 即死を実証)
4. フラグゲート (設計v9) はコード上正しい。EGG 生存の E2E 確認のみが残課題

### 残 TODO (タスク7)

1. ハーネスの hop()/hop2() を Runnable 引数なしへ変更 (Runnable をフィールド保持 or ラムダを直接 server.tell)
   → EGG 生存検証を復活
2. フラグ OFF 下での EGG 生存 E2E (柱3b 完全系)
3. build.gradle TEMP quickPlaySingleplayer 引数はタスク6 検証終了に伴い削除 (タスク7 再開時に再追加)

## タスク6.5: KILL_RULES 削除 (専用対策違反の修正) — 2026-08-11

### 経緯

ユーザー指摘: `EntityInlineKillTransformer` の `KILL_RULES` 配列は「専用対策」そのもの
(設計書 docs/Pig2CompleteKill.md §2 の制約違反)。

- 旧 KILL_RULES: `{"net.minecraft.world.entity.monster.Slime", null}` / `{null, "Pig2"}`
  → pig2mod のクラス名・表示名 "Pig2" をハードコードする専用対策
- 修正選択肢 (A: KILL_RULES 拡充 / B: 表示名一致 / C: 削除→フラグON全除去) のうち
  **C を採用** (ユーザー選択・Recommended)

### 変更内容

- `EntityInlineKillTransformer.java`:
  - `KILL_RULES` 配列・`COMPONENT_CLASS`・`getDisplayNameName` を**削除**
  - `transform()` を「フラグゲート (rk.should_kill) → flipSelfDestructFlags → setRemoved(KILLED) + kill()」の
    **全 LivingEntity 除去**へ変更 (KillDaemon のフラグON全掃討意味論と一致)
  - `hasInlineKillInjected` マーカーを LDC 定数 `"rk.should_kill"` に変更 (冪等ガード)
- `HarnessAutoVerify.java`: KILL_RULES 参照コメントを新意味論 (フラグON全除去) に更新
- ビルド検証: `build.bat` (compileJava + buildEarlyBootJar) **BUILD SUCCESSFUL in 25s**

### 整合確認

| 検証項目 | 結果 |
|---|---|
| KILL_RULES 実装参照 | ゼロ (src 全体 grep、残存は「削除済み」の文脈コメントのみ) |
| フラグON での全 LivingEntity 除去 | 設計v9 フラグゲート維持 (run6 の D/Pig2 即死実証と同経路) |
| 専用対策禁止 (docs §2) | 適合 — クラス名/表示名のハードコードなし |
| フラグOFF での休止 | ゲートにより変更なし (EGG 生存の E2E はタスク7 残 TODO) |

---

## タスク6.5.1: 本番 NCDFE (utils/RKLog) 検証 + Bug C 修正 — 2026-08-12

### 依頼

> かなり前のバージョンのものだったら Build したものを本番環境で実行しようとすると
> `NoClassDefFoundError utils/RKLog` と出る現象を現バージョンでは修正出来ているか確認して欲しい
> (起動確認は不要、静的検証のみ)

### 検証結果 (静的解析で確定)

| 項目 | 結果 |
|---|---|
| 旧エラー `utils/RKLog` (裸パッケージ) の残存 | **ゼロ** — `package utils` / `import utils.` は src 全体 grep で残存なし。クラスは `com.randomkillentity.utils.RKLog` に再パッケージ済み (参照 11 箇所全て FQCN) |
| 旧エラー自体の再発可能性 | **なし** (裸パッケージは存在しないため) |
| **本番 jar 単体での同種 NCDFE** | **残存 → Bug C として修正** (下記) |

### Bug C: 本番 jar 単体では `com/randomkillentity/utils/RKLog` が不可視

- 本番 jar `randomkillentity-1.0.1.jar` (63 エントリ) の**ルートに `com/randomkillentity/utils/` が無い**
  (utils は earlybootstrap sourceSet 由来で、本番 jar には未同梱)
- 埋め込み `META-INF/jarjar/randomkill-agent.jar` は **jar-in-jar としてロードされない**:
  JarJarSelector 0.3.19 ソースで確定 — 埋め込み jar の検出は
  `META-INF/jarjar/metadata.json` の存在のみ (`Constants.CONTAINED_JARS_METADATA_PATH`)。本番 jar に metadata.json なし
- 本番 jar に `META-INF/services` (SPI) も無い → EarlyBootstrapService も起動しない
- `RandomKillEntity` はクラス初期化 (L22 `LOGGER = RKLog.isLogEnabled() ? ...`) で
  `com/randomkillentity/utils/RKLog` を要求 → クラスロード時に **NCDFE 発生**
- dev 環境が動くのは `runtimeOnly files(.../randomkill-earlyboot.jar)` (L186) がクラスパスに載るため

### 修正 (選択肢 a を採用: 本番 jar ルートに utils 同梱)

`build.gradle` jar タスクに追加:

```groovy
// Bug C 修正: 本番 jar 単体では早期ブート sourceSet の classes が同梱されておらず、
// ... NCDFE: com/randomkillentity/utils/RKLog が発生していた。
from(sourceSets.earlybootstrap.output.classesDirs) {
    include 'com/randomkillentity/utils/**'
}
dependsOn sourceSets.earlybootstrap.classesTaskName
```

- (b) 案 (metadata.json 追加で jar-in-jar ロード) は **不採用**: mod jar と agent jar で
  `com.randomkillentity.utils` が split package になり ResolutionException (旧 Bug B と同種) のリスク
- (c) 案 (earlyboot jar を正式配備) は不採用: 配備手順の複雑化を避け、mod 単体で完結させる

### ビルド・検証結果

- [x] `gradlew.bat jar` → **BUILD SUCCESSFUL in 23s**
- [x] jar エントリ検証: `com/randomkillentity/utils/` 配下 6 クラス
  (JVMArgsInjector / NativeLoader / RKAgentLoader / RKLog / UnsafeUtils / WatcherLauncher) が
  **本番 jar ルートに同梱** (70 エントリ = 旧 63 + 7)
- [x] 本番 jar 単体でも `RandomKillEntity` クラス初期化時の RKLog 解決が可能に
- [ ] (依頼により) 起動確認は実施しない

### 残 TODO

1. 本番配備手順のドキュメント化 (mod jar 単体で OK になった旨) — 必要なら

---

## タスク7: 設計v11 — 汎用 JVMTI / ネイティブ層の狙い撃ち + Mixin 強化 — 2026-10-11

### 依頼

> `KillSources` 内の防御 Mod、特に最上位 JVMTI 防御やネイティブ防御 (メモリ保護等) に対しても
> 有効な即死手段にすること。あわせて ASM に加えて Mixin 側も強化する (ASM で行っている処理の模倣)。
> 副作用の多さ・安定性の低さは許容。回答・成果物は日本語。

### 方針 (到達範囲・突破力を最優先、完全汎用)

- 特定 mod のクラス名・パッケージ名・ライブラリ名を**一切ハードコードしない**。
  プラットフォーム機構 (JVMTI / Windows ローダ) の構造だけを狙う。
- 特定 mod の Mixin (`@Mixin(targets=...)`) は「専用対策」に該当するため全廃。
  Mixin はバニラ MC クラスのみを対象とし、ASM と同一処理を模倣する用途に限定。
- 副作用 (防御mod の自己修復・状態復元の停止、場合により通常 mod の一部停止) は許容する。

### 実装した3層

#### 1. ネイティブ層 (`RandomKillAgent/src/random_kill_agent.cpp` / `.h`)

**(1-a) JVMTI 関数のホットパッチ (汎用・名前非依存)**

- 各エージェントが受け取る `jvmtiEnv` の関数テーブル (`functions`) は、実体として
  jvm.dll 内の共有関数を指す。`SetBreakpoint` / `SetFieldModificationWatch` /
  `SetFieldAccessWatch` / `ForceEarlyReturn{Int,Long,Float,Double,Object,Void}` の
  機械語先頭を `xor eax,eax; ret` (= return 0) に書き換える。
- 以後どのエージェントの env から呼んでも no-op になり、breakpoint による
  ForceEarlyReturn 握り潰しと field watch による状態復元を、相手の実装に依存せず根本から封じる。
- x86 (__stdcall) では `ret imm16` で引数分を pop。既適用判定 (memcmp) で冪等。
  `patchJvmtiDefenseFunctions()` を初期化時 + 周期で適用。

**(1-b) ネイティブエージェント DLL のエントリポイント中和 (汎用・名前非依存)**

- Windows ローダ通知 `LdrRegisterDllNotification` で DLL ロードを検知し、
  エクスポートに JVMTI 仕様の固定シンボル `Agent_OnLoad` / `Agent_OnAttach` / `Agent_OnUnload`
  を持つモジュールの当該関数先頭を「return 0」に書き換える。JVMTI 仕様上これらシンボル名は
  固定であり、任意のネイティブエージェントをモジュール名非依存で無力化できる。
- ロード済みモジュールは `CreateToolhelp32Snapshot` で走査して同様に中和
  (`installNativeAgentBlock()`)。system32 / SysWOW64 / WinSxS、jvm.dll、自 DLL は除外。
- 通知コールバックはローダロック下で呼ばれるため、ヒープ確保を避け `VirtualProtect` と
  機械語書き換えのみを行う。
- 加えて CFLH で全クラスの `premain` / `agentmain` を中和する (従来)。Java エージェントの
  ネイティブ層ロード経路を実行前に遮断する。

**(1-c) ロード済み hook の網羅解除 (汎用)**

- `clearAllLoadedDefenseHooks()`: 全ロード済みクラスの全メソッド location 0 の breakpoint と、
  全フィールドの modification / access watch を一度だけ網羅解除する。ホットパッチ前に
  他エージェントが仕掛けた分の掃討用。
- `neutralizeRivalDefensesImpl()`: コアクラス (Entity / LivingEntity / Player / ServerPlayer /
  ServerLevel / SynchedEntityData) の breakpoint / field watch を解除。コアクラスは
  `NewGlobalRef` でキャッシュし、1 秒周期でも `GetLoadedClasses` を繰り返さない。
  併せて (1-a)(1-b) を冪等に再適用。

- **CFLH 動作モード** `gCflhMode` (0=未, 1=フル, 2=中和のみ)。`gCflhMode==2` (Java Agent 経路) では
  ネイティブからの Java 変換委譲を行わず二重変換を回避。
- **新 JNI**: `initializeAgentNeutralizer` (中和専用 CFLH 登録 + 遡及中和 + 汎用層適用)、
  `neutralizeRivalDefenses` (解除数の返却)。
- エントリポイント `initializeNativeAgent` (ネイティブ fallback) でも汎用層を適用。
  `sweepEntitiesAndKill` の冒頭でも毎回 `neutralizeRivalDefensesImpl()` を実行する。

#### 2. Java (ASM) 層

- **新規 `DefenseNeutralizerTransformer`** (`agent/transformer/`):
  第三者のクラスを、防御的な**命名パターン**のみで中和する (クラス名・パッケージ名は不使用)。
  - 候補判定 (`isDefenseCandidate`): クラスファイルの定数プールを特徴トークン
    (`Immortal` / `shouldBlock` / `shouldReplace` / `shouldPrevent` / `shouldCancel` /
    `checkHook` / `isProtected` / `isBypass` / `enforceImmortal` / `syncImmortal` /
    `restoreIntegerDataItems` / `restoreTransformer` / `onBaseTick` / `onLivingTick`) で
    安価に走査し、ヒットしたクラスのみ ASM 解析する (誤検出抑制のため複合語のみ)。
  - 対象クラス判定 (`isDefenseClass`): バニラ / Forge / JDK / 主要ライブラリ / 自 mod 以外。
  - 中和 (`matchesMethod` / `transform`): ガード命名 (`should*` / `isProtected` / `isImmortal` /
    `isInvulnerable` / `isBypass` / `checkImmortal*` / `checkKill*` / `*isCaller*`) と
    復元・執行ループ命名 (`restore*` / `revert*` / `repair*` / `enforce*` / `reRegister*` /
    `retransform*` / `checkHook*` / `syncImmortal*` / `processEntities` / `onBaseTick` /
    `onLivingTick*` / `cleanupStale*` / `purge` / `sanitize` / `watchdog` / `revive*` /
    `respawn*` / `resurrect*` / `transform` 等) を戻り値型に応じた既定値 return に置換。
  - ガードが false を返すため注入済みフックの呼び出し先が無効化される。呼び出し先は
    相手自身のクラスにあるため、フックを再注入されても恒久的に無効。
- **新規 `DefenseDisarmService`** (`agent/`): 1 秒周期で `NativeLoader.neutralizeRivalDefenses()`
  を再適用するのみ (クラス名依存の再トランスフォームは廃止)。
- `RKTransformer.transform` は `isTargetClass(バニラ5クラス)` または
  `DefenseNeutralizerTransformer.isDefenseCandidate(bytes)` でゲート。
  `TransformerCore` に `DefenseNeutralizerTransformer` を登録。
  `RKAgent.agentmain` で `initializeAgentNeutralizer` → `neutralizeRivalDefenses` →
  `DefenseDisarmService.start()` を実行。`ensureHookClassesLoaded` に新クラスを追加。
- `NativeLoader.java` (earlybootstrap) に新 native 宣言を追加
  (`setTransformClassPrefixes` は廃止)。

#### 3. Mixin 層 (バニラ対象のみ・ASM の模倣)

- `com.randomkillentity.mixin.LivingEntityMixin`: `tick` HEAD で ASM `EntityInlineKillTransformer` と
  同一シーケンス (フラグ `rk.should_kill` ゲート → `StateDestroyer.flipSelfDestructFlags` →
  `setRemoved(KILLED)` → `kill()`) をインライン展開。`setHealth` RETURN で
  `LivingEntityMethodImpl.INSTANCE.setHealth` を呼び体力 0 を強制。
- `com.randomkillentity.mixin.ServerLevelMixin`: `addFreshEntity` HEAD で
  `RevivalBlocker.shouldBlockRevival` により復活をブロック (ASM `ServerLevelTransformer` の模倣)。
- 特定 mod を `targets=` 指定する防御 Mixin 群と専用 config は全廃。

### ビルド設定変更 (`build.gradle`)

- 専用 Mixin config (`randomkillentity.defense.mixins.json`) 登録・`compileOnly files(...)` 依存・
  `sourceSets.main.java.exclude`・manifest の `MixinConfigs` 手動属性をすべて除去。
  MixinGradle の自動設定に委譲 (manifest `MixinConfigs` は `randomkillentity.mixins.json` のみ)。
- Mixin config は `randomkillentity.mixins.json` のみ (`JAVA_17`、バニラ対象 2 Mixin)。

### 検証結果

- [x] ネイティブ再ビルド (MSVC / `rebuild_native.bat`) → 警告のみで成功。新 DLL 287,744 bytes を
  `src/main/resources/META-INF/native/windows/random_kill_native.dll` に配置。
- [x] `gradlew.bat build` (全体) → **BUILD SUCCESSFUL**。
- [x] 成果物検証:
  - manifest `MixinConfigs: randomkillentity.mixins.json` のみ。defense config / Mixin は非同梱。
  - 本番 jar / agent jar / earlyboot jar の `random_kill_native.dll` = 287,744 bytes。
- [ ] **未実施 (最重要)**: 実機 E2E (防御 Mod 導入環境での即死 / 防御mod 中和の実挙動確認)。
  本設計は解析ベースで、実環境での完走は未検証。

### 残 TODO

1. 防御 Mod 導入環境での E2E (エージェント自己アタッチ → フラグ ON → 対象即死 / 防御の復元停止) の実測。
2. JVMTI 関数ホットパッチの共有性確認 (全 env が同一関数アドレスを指すこと) を実ログで検証。
3. `DefenseNeutralizerTransformer` の中和対象に副作用過多がないか実ログで確認。
4. `installNativeAgentBlock` / `clearAllLoadedDefenseHooks` の走査コスト実測と最適化判断。
5. location 0 以外に breakpoint を張る防御への追加対応 (現状は短メソッドのみ全位置走査)。
