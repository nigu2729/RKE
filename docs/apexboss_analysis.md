# ApexBoss (apexboss-0.2.1) を倒せない理由と汎用的な対策

## 概要

`apexboss-0.2.1` を「汎用的な即死（構造除去 + 防御破壊 + RevivalBlocker）」で倒すのに失敗する主な理由と、完全に Mod 固有識別子を使わずに有効化し得る汎用的な対策を分析します。

## 調査観点

apexboss-0.2.1 のクラス構成（抜粋）:

- `com.zenith.aegis.ApexBossEntity` - ボス本体（LivingEntity 的挙動）
- 関連: ステータス・段階・AI・同期データ（SynchedEntityData）等

ボス系 Mod が即死を妨げる典型的要因:

1. **Health/Synced Data の再設定**  
   `LivingEntity` の `health` は `SynchedEntityData` でも管理される。`setHealth(0)` やネイティブ書き換えだけだと、後続のパケット/同期や自前処理で値が戻される場合がある。
2. **LivingDeathEvent のキャンセル／カスタム die()**  
   Forge の `LivingDeathEvent` をキャンセルしたり、`LivingEntity.die(DamageSource)` をオーバーライドして独自終了処理（フェーズ遷移・再召喚・演出）を行う。
3. **無敵・ダメージ無効化フラグ**  
   `invulnerable`, `invulnerableTime`, カスタム boolean フィールド（例: `immune`, `invincible`, `phaseLocked`）や `isInvulnerableTo(DamageSource)` のオーバーライド。
4. **AI/目標・タスクが死亡後も動作**  
   ボス固有 Goal が `die()` 後も実行され、何らかの復活・再配置をトリガーする。
5. **カスタム RemovalReason / remove()**  
   `setRemoved(RemovalReason)` を独自にハンドルし、通常の KILLED では削除しない実装が稀に存在。
6. **Chunk/Level 管理**  
   `ServerLevel` のエンティティリストやカスタムコレクションに再登録される（RevivalBlocker で一部カバー済みだが、登録経路次第）。
7. **演出優先**  
   死亡アニメーション中に `isDeadOrDying()` が true でも新たな処理が走る。

## 現行実装のギャップ

現在の `RandomKillEntity`（汎用）の主な処理:

- `WorldStructureDestroyer.purgeFromWorld` - tick/list/section/lookups から除去、`levelCallback` クリア、`writeRemovalReasonKilled` + `callSetRemoved`、ネイティブ field 強制（health/invuln）、プレイヤー除外でネイティブ sweep 抑制
- `StateDestroyer` - リフレクションで防御関連フィールドを汎用的に無効化（`invulnerable*`, `noAI`, `attackable`, `isDead*`, `deathTime` 等 + キーワード拡張）
- `RevivalBlocker` - addFreshEntity/addEntity 等の復活経路を一定時間ブロック（class-wide/coarse key）
- `KillDebounce` - 短時間の再キル抑制（identity/UUID ベースに改善済み）
- `KillExecutor.killNow` - 上記を順次実行

想定される未カバー:

- **LivingEntity.setHealth の直接呼び出し**  
  `StateDestroyer` は boolean/int/float の防御系フィールドを書き換えるが、`setHealth(0.0f)` 自体は呼ばない。`SynchedEntityData` 同期を介して値が戻る場合、明示的に `setHealth(0f)` を呼ぶ方が確実。
- **discard()/remove の強制**  
  `setRemoved(RemovalReason.KILLED)` の後に `discard()`（1.20+ で利用）も試すと、より強固に除去できる場合がある。
- **死亡フラグ・デス時間系の強制**  
  `deathTime` を十分大きく、`dead`/`isDead` 系 boolean を true にするなど、内部状態を「死亡確定」に近づける。
- **カスタム無敵判定の緩和**  
  `isInvulnerableTo` 等をオーバーライドする Mod には直接効かないが、一般的には health=0 + removal が最終的に有効。だが、上記1-3 が組み合わさると阻害される。

## 汎用的な改善案（Mod固有名排除）

`KillExecutor.killNow` もしくは `StateDestroyer` に以下を追加（リフレクション・メソッド名ヒューリスティクスのみ）。

### A. LivingEntity なら setHealth(0f) を呼ぶ

```java
if (living instanceof LivingEntity) { // if we have access to MC classes? but in agent codebase we use reflection; entity is Object
  // via reflection: getMethod("setHealth", float.class) or m_* SRG name fallback
}
```

但し本プロジェクトはほぼリフレクション前提。`StateDestroyer` か `KillExecutor` で以下を試行:

- メソッド候補: `setHealth`, `m_21154_` (SRG) など（MC 1.20.1）
- 引数 0.0f で呼ぶ

### B. discard() を試行（存在すれば）

候補: `discard`, `m_146870_`。引数なし。`Entity` に定義。

### C. 死亡関連フラグの強制

- `dead = true`（`isDead` セッター/フィールド）
- `deathTime` を 20 以上に設定（演出完了扱い）
- `hasImpulse = false`, `levelCallback` は既に no-op 化済み

### D. kill 経路の「遅延実行」戦略（選択的）

稀に「今すぐ kill」だと他 Mod の tick/handler より早すぎて無効化される。デバウンス中でも再試行するか、次 tick に再度 `killNow` をスケジュールする汎用的な軽量仕組み（WeakReference + Map + 1-2 tick 遅延）は有効な場合がある。ただし Forge イベント等には依存しない形が望ましい（エージェント側で単純ループは避ける）。

## ApexBoss 固有で推測される落とし穴

`apexboss-0.2.1` に関する公開挙動から推測:

- 多段階（Phase）制御: HP が 0 になっても phase が残存し、自動的に次 phase 用エンティティをスポーンしたり、同一ボスの状態をリセットする。
- カスタムダメージソース/条件: 特定 DamageSource 以外での「死」を無視。
- `LivingDeathEvent` 優先度高: キャンセル or 後処理で `revive` 的挙動。
- `SynchedEntityData` にボス固有のフラグ（例: `BOSS_PHASE`, `SHIELD`）があり、これを参照して `isInvulnerableTo` が true を返す。

汎用 kill で上記を直接無効化できないが、**health 強制 0 + structural purge + revival block** を tick 単位で「数回（2-3回）」軽く再適用するだけで通るケースは多い。

## 推奨実装（最小・汎用）

### 1) KillExecutor に setHealth 強制を追加

LivingEntity 判定はリフレクション:

```java
private static void forceHealthZero(Object entity) {
  try {
    Class<?> le = Class.forName("net.minecraft.world.entity.LivingEntity", false, entity.getClass().getClassLoader());
    if (le.isInstance(entity)) {
      Method m = findMethod(le, new String[]{"setHealth","m_21154_"}, 1, float.class);
      if (m != null) { m.setAccessible(true); m.invoke(entity, 0.0f); }
    }
  } catch (Throwable ignored) {}
}
```

既存の `NativeLoader.forceWriteFloatField` に加えてこの呼び出しを `killNow` 冒頭〜終了付近の両方で 1 回ずつ試す。

### 2) discard() の試行を追加

```java
private static void tryDiscard(Object entity) {
  try {
    Method m = findMethod(entity.getClass(), new String[]{"discard","m_146870_"}, 0);
    if (m != null) { m.setAccessible(true); m.invoke(entity); }
  } catch (Throwable ignored) {}
}
```

`callSetRemoved` 後に呼ぶ。

### 3) 死亡状態の強制（boolean/ints）

`StateDestroyer` の `DEFENSE_KEYWORDS` に `phase`, `shield`, `invincible`, `immune`, `dying`, `boss` 等を追加（汎用キーワード）。加えて `deathTime` を `Integer.MAX_VALUE / 2` 程度に書き換える候補を追加（既存の汎用フィールド列挙にヒットしない場合にも有効）。

### 4) KillDebounce の再設計（UUID 優先）

既に UUID ベースに変更済み。プレイヤーがリスポーンすると別インスタンスなので UUID キーは有効。ボス等はスポーン時の UUID が概ね維持。

### 5) 軽量リトライ（汎用・無イベント依存）

`KillExecutor` 内に「失敗時 or 条件付き」で最大 2 回、50-100ms 程度の `Thread.sleep` は避け、単純に呼び出しを繰り返す（同一 tick 内で複数回呼ぶか、次回呼び出し時に有効）。現状 `shouldThrottle` が 500ms ブロックするため、リトライは throttle を考慮しつつ短時間に限定。

## まとめ

ApexBoss が「倒せない」主因は恐らく **health 同期（setHealth 呼び出し不足）＋カスタム死亡処理**。現行は構造除去・防御破壊中心で、`LivingEntity.setHealth(0f)` の明示呼び出しと `discard()` が不足しています。

Mod 固有を一切入れずに上記 A-C を追加するだけで、apexboss を含む多くのボス系に対して通用性が上がる見込みです。特に `setHealth(0f)` は最も影響が大きく、リスクも低い（汎用リフレクション）のため優先度が高いです。
