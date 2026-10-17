# プレイヤーが死に続ける現象の原因分析

## 現象

「稀にプレイヤーが死に続ける」との報告。即死（`/rke` またはキル処理）後に、プレイヤーが再度死亡状態になったり、死亡画面からリスポーンしても再び死ぬ、あるいは死亡ループに陥る。

## 考察される原因

### 1. KillDebounce の identity ベース
- 従来: `System.identityHashCode(entity)` をキーにしていた。プレイヤーが死亡→リスポーンすると、Minecraft では新しい `ServerPlayer` インスタンスが作成されるため identity が変わる。
- 結果: デバウンスが効かず、同一プレイヤー（同一 UUID）に対して再度キルがトリガーされた際に「またキル」となり得る。

**対応済み**: UUID 優先のキーに変更。`getUUID()` が取得できる場合は UUID を使用。これによりリスポーン後も同一プレイヤーとして判定され、意図しない再トリガーを抑制しやすくなる。

### 2. 他 Mod が死亡後イベントをトリガー
- `LivingDeathEvent`, `PlayerEvent.Clone`, `PlayerRespawnEvent`, カスタムイベント等で他 Mod が「死んだプレイヤー」に対して何らかのアクションを行い、その結果再び `killNow` が呼ばれる可能性がある。
- 例: No-Sugar（sugar 関連効果）、The Trial Monolith、apexboss 連動など。

**現状対策**: KillDebounce（500ms）で同一エンティティ/同一 UUID への連続呼び出しを抑制。だが 500ms 経過後に再度トリガーされると再キル。

### 3. levelCallback の不整合
- `WorldStructureDestroyer` で `levelCallback` を `NoOpEntityInLevelCallback` に置換済み（null 回避）。
- しかし `ServerLevel#removePlayerImmediately` 等の一部経路が走ると、内部状態が「removed」だがクライアント側と不一致になり、次 tick で再処理が走る恐れ。

### 4. 構造的除去（structural purge）がプレイヤーに影響
- プレイヤーに対しては構造リストからの除去を行わないように調整済み（isPlayerLike）。tick list/section/lookups 等からの物理的削除をスキップし、`setRemoved` とコールバック置換中心にした。
- これによりクライアント同期の破壊は軽減。

### 5. プレイヤーの死亡状態が「固着」
- `setRemoved` 後も `isDeadOrDying()` が期待通りにならない、または `deathTime` が 0 のままになるケース。
- `FixDeadPlayer.tryFix()` で `setDead(false)` を試行。だが一部実装では無視されたり効果が一時的。

## 推奨追加対策（汎用）

### A. プレイヤー専用の長めデバウンス
- `KillDebounce` で `isPlayerLike` の場合は `DEBOUNCE_MS = 2000L` 程度に延長すると、リスポーン直後の二重トリガーをさらに抑制できる。

### B. UUID ベースの「kill 済み」マーカー（revival window と独立）
- `RevivalBlocker` は復活ブロック用。別に「最近 kill 済みプレイヤー UUID」集合を用意し、一定時間（例 3-5秒）再キルを完全にスキップする（ただし明示的な `/rke` は除外する？要検討）。
- 汎用的に `Player` かつ UUID がマーカーに入っていれば throttle。

### C. setHealth(0f) と死亡状態の強制をより確実に
- `forceHealthZero` は LivingEntity 限定で `setHealth(0f)` を呼ぶ（実装済み）。
- 追加: `deathTime` を大きく設定（例 100）、`dead = true` 等の boolean フラグ強制（`StateDestroyer` でヒューリスティックに対応済みだが、プレイヤーの vanilla クラスではフィールド走査を break する経路あり）。
- `destroyPlayerDefenses` も活用。

### D. kill 呼び出し側での多重防止
- イベント駆動で kill が呼ばれる場合、同一 tick 内で複数回呼ばれないよう、`ThreadLocal` な「inProgress」セットも有効（超軽量・汎用）。

ただし「Mod 固有識別子を一切使わない」という方針を崩さない範囲で実施。

## 結論

最も直接的な原因は **リスポーン後のインスタンス差によるデバウンス漏れ** で、UUID キー化で大幅に改善。加えてプレイヤーに限ってデバウンスを長めにするのが最も安全で汎用的な対策。
