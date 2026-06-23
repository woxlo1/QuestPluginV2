# QuestPluginV2

Minecraft (Bukkit/Spigot/Paper, API 1.20) 向けのクエスト管理プラグイン。
クエスト受注・進捗管理・パーティー機能・独自NPCシステムを、MySQLによる永続化とともに提供する。

---

## このプラグインについて

### 概要

QuestPluginV2は、サーバー管理者がプレイヤー向けのクエストコンテンツを作成・運用するための基盤プラグイン。前身であるV1の設計を引き継ぎつつ、以下の点を拡張・再設計したV2実装である。

- **2系統のクエスト進行モデル**を持つ（後述）
- **パーティー機能**を新規追加し、複数人でのクエスト進行・進捗共有に対応
- **独自NPCシステム**を実装し、Citizens等の外部プラグインに依存しない
- **MySQL + HikariCP**によるコネクションプーリングと、キャッシュ＋DBの二層構造によるデータ管理
- Kotlin（コマンド定義）とJava（マネージャー・リスナー・モデル層）の混成実装

### 実行環境・依存

| 項目 | 内容 |
|---|---|
| 対応API | Bukkit/Spigot/Paper `1.20` |
| 必須依存 | MySQL（HikariCPでプーリング） |
| 任意依存（softdepend） | Vault（金銭・権限報酬）, MythicMobs（`BOSS_KILL`クエスト） |
| 言語 | Java（マネージャー / リスナー / モデル）、Kotlin（コマンド定義） |
| コマンド登録 | 独自のOraPluginAPI（NMS経由でBrigadierに登録） |

未導入のオプション依存（Vault / MythicMobs）がある場合、関連機能は自動的に無効化され、それ以外の機能には影響しない。

---

## アーキテクチャ

### レイヤー構成

```
QuestPluginV2 (JavaPlugin)
├── command/      … /quest, /questop, /party のコマンド定義（Kotlin, OraPluginAPI経由でBrigadierに登録）
├── listener/     … Bukkitイベント検知 → 進捗反映のトリガー
├── manager/      … ビジネスロジックの中枢（クエスト・パーティー・NPC・進行中セッション・報酬・Vault）
├── model/        … データモデル（quest / party / npc / reward）
├── gui/          … クエスト作成・編集用のインベントリGUI（Kotlin）
├── database/     … MySQL接続・スキーマ初期化・マイグレーション
└── util/         … スコアボード・ボスバー用タイマー・インベントリバックアップ等の補助クラス
```

起動時（`onEnable`）の初期化順序は次の通り。これは依存関係上の制約であり、変更時は順序を保つ必要がある。

1. `DatabaseManager.connect()` → 接続失敗時はプラグインを即時無効化
2. `DatabaseManager.initializeTables()` → `database.sql`を読み込んでテーブル作成、続けてスキーママイグレーション
3. `QuestManager` → クエスト定義をDBからキャッシュへロード
4. `ActiveQuestManager` → **QuestManagerの後**に初期化（クエスト定義の参照が必要なため）
5. `PartyManager`, `VaultManager`, `NpcManager`
6. Vault連携チェック → MythicMobs連携チェック → リスナー登録 → コマンド登録

### 2系統のクエスト進行モデル

QuestPluginV2最大の特徴は、目的の異なる2つのクエスト管理系統を**並行して**持つことである。両者は同じ`QuestType`の進捗イベントを共有して受け取るが、データの持ち方・UIが異なる。

| | `QuestManager`（受注型） | `ActiveQuestManager`（進行中型） |
|---|---|---|
| 起動コマンド | `/quest accept <id>` | `/quest start <id>` / `/quest party <id>` |
| 同時進行数 | 複数可（`max-active-quests`まで） | プレイヤーごとに1つのみ |
| 進捗の見え方 | チャットメッセージ通知 | ボスバー＋サイドバースコアボード |
| 制限時間 | なし | `timeLimitSeconds`で設定可（タイムアップで失敗） |
| ライフ制 | なし | `maxLives`で設定可（パーティーは合算） |
| インベントリ | 変化なし | 開始時にバックアップ→クリア、終了時に復元 |
| 永続化先 | `player_quest_progress` | セッションはメモリ管理のみ（完了/失敗時に履歴記録） |

`QuestProgressListener`と`MythicMobsListener`は、検知した進捗イベントを**両方の管理クラスに同時通知**する。これは元々QuestManagerにしか通知していなかったものを、ActiveQuestManager側にも反映されるよう統一した経緯がある。

### データフロー（進捗反映の例: ブロック破壊）

```
BlockBreakEvent
   ↓
QuestProgressListener.onBlockBreak()
   ↓                              ↓
QuestManager.addProgress()    ActiveQuestManager.addProgress()
   ↓                              ↓
progressCache 更新             sessions 更新
   ↓ (非同期)                     ↓
DB: player_quest_progress     ボスバー/スコアボード更新
   ↓                              ↓
required_amount 到達？          required_amount 到達？
   ↓ YES                          ↓ YES
RewardGiver.give()             RewardGiver.give()
                                インベントリ復元・パーティー解散判定
```

報酬付与ロジック（`RewardGiver`）は両管理クラスから共通で呼ばれ、重複実装を避けている。

### パーティーとクエストの関係

- パーティーはクエストと独立した概念（`PartyManager`が管理）。
- `party.progress-share: true`の場合、`ActiveQuestManager`/`QuestManager`はパーティーメンバー全員に進捗を再帰的に伝播する（距離判定は行わない）。
- `ActiveQuestManager`側では、クエストの`isShareCompletion()`が`true`の場合、1人の完了が他メンバーにも伝播する。
- パーティー解散は、クエスト終了（完了/失敗/キャンセル）時に自動で行われる（`dissolvePartyOnQuestEnd`）。

### インベントリの扱い（進行中型クエストのみ）

`ActiveQuestManager.startQuest()`は、開始時にプレイヤーのインベントリをファイル（`plugins/QuestPluginV2/inventory_backups/<uuid>.yml`）にBase64でバックアップし、その後インベントリをクリアする。クエスト終了時（完了/失敗/キャンセルいずれも）に同ファイルから復元し、ファイルを削除する。

異常系の保護として、既にバックアップファイルが存在する状態（前回の復元が完了していない状態）では新規のクエスト開始自体を拒否する。これはクラッシュ等でファイルが残存したまま再度クリアしてしまい、アイテムが完全消失することを防ぐための安全策。

---

## NPCシステム

Citizens等の外部プラグインに依存せず、プラグイン側で直接Mobをスポーン・管理する独自NPC機能。

### 概要

- NPCは`Villager`をはじめ、任意の`LivingEntity`系Mob（ゾンビ・スケルトン・羊など）として作成できる。
- スポーンしたNPCはAIを無効化・無敵化した「棒立ち」状態になる。敵対Mobを選んだ場合でも、攻撃モーションや追跡などの挙動は発生しない。
- NPCに紐づけたクエストは、右クリックすると挨拶・クエスト概要・受注ボタンが表示される（`NpcInteractListener`）。
- リロード・再起動時は、保存済みのNPC情報からプラグインが毎回スポーンし直す（`Entity#setPersistent(false)`によりワールドのオートセーブには乗らない）。
- スポーン中のエンティティとDB上のNPC IDは`PersistentDataContainer`で紐付けられ、右クリック時にO(1)で逆引きされる。

### コマンド一覧（`/questop npc`）

| コマンド | 説明 |
|---|---|
| `/questop npc create <名前>` | 現在地にNPCを作成する（常に村人＝`VILLAGER`として作成） |
| `/questop npc settype <id> <EntityType>` | 既存NPCのMob種別を変更する（再スポーンを伴う） |
| `/questop npc remove <id>` | NPCを削除する |
| `/questop npc setquest <id> <questId>` | NPCにクエストを設定する |
| `/questop npc setgreeting <id> <文>` | NPCの挨拶文を設定する |
| `/questop npc tp <id>` | NPCを現在地に移動する |
| `/questop npc list` | NPC一覧を表示する（Mob種別も併記） |

#### 使用例

```
/questop npc create 案山子
→ NPCを作成しました ID: 5（VILLAGERとして作成）

/questop npc settype 5 ZOMBIE
→ 5のMob種別をZOMBIEに変更しました
```

`/questop npc settype`の引数には、Tab補完で選択可能な`EntityType`のみが候補に出る。`PLAYER`や`ARMOR_STAND`など、NPCとして使用できない種別（`LivingEntity`のサブタイプでないもの）は候補から除外される。

### 村人以外のMobを使う際の注意

- **見た目・挙動**: 全Mob共通でAI無効化・無敵化（棒立ち）。敵対Mobでも攻撃してこない。
- **水中・飛行Mob**: イカ・コウモリなど特殊な移動形態を持つMobは、陸上にスポーンさせると見た目が不自然になる場合がある（機能自体は動作する）。
- **大型Mob**: エンダードラゴン等は当たり判定の都合で意図しない見た目になる可能性があるため、運用前に実機確認を推奨。

### データベーススキーマ

`quest_npcs`テーブルに`entity_type`カラム（`VARCHAR(32)`, デフォルト`'VILLAGER'`）を保持する。

既存のサーバーで稼働中のデータベースに対しては、プラグイン起動時（`onEnable`）に`DatabaseManager`が自動でカラムの存在確認を行い、なければ`ALTER TABLE`で追加する（冪等処理のため、手動でのSQL実行は不要）。事前のDBバックアップを推奨する。

不明な`entity_type`の値が保存されていた場合（手動でのDB編集等）は、`VILLAGER`にフォールバックしてスポーンする。

---

## コマンド詳細

### クエストコマンド（`/quest`）

| コマンド | 説明 |
|---|---|
| `/quest help` | このヘルプを表示する |
| `/quest list [page]` | 受注可能なクエスト一覧を表示する |
| `/quest info <id>` | クエストの詳細情報を表示する |
| `/quest accept <id>` | クエストを受注する（受注型） |
| `/quest abandon <id>` | 受注中のクエストを放棄する |
| `/quest progress` | 受注中クエストの進捗を確認する |
| `/quest party <id>` | パーティーでクエストを受注する（リーダーのみ・受注型） |
| `/quest start <id>` | クエストを開始する（V1互換・リーダーのみ・進行中型） |
| `/quest leave` | 進行中のクエストを中断する（進行中型） |

### パーティーコマンド（`/party`）

| コマンド | 説明 |
|---|---|
| `/party help` | このヘルプを表示する |
| `/party create` | 新しいパーティーを作成する |
| `/party invite <player>` | プレイヤーをパーティーに招待する（リーダーのみ） |
| `/party accept` | パーティーへの招待を承認する |
| `/party leave` | 現在のパーティーを退出する（リーダー退出時は次のメンバーへ自動移譲） |
| `/party kick <player>` | メンバーをキックする（リーダーのみ） |
| `/party disband` | パーティーを解散する（リーダーのみ） |
| `/party info` | パーティーの情報を表示する |
| `/party transfer <player>` | リーダー権を移譲する（リーダーのみ） |
| `/party chat <message>` | パーティーチャットにメッセージを送る（`!`始まりのチャットでも代用可） |

### 管理者コマンド（`/questop`）

クエストの作成・編集・削除・報酬設定、NPC管理を行う。詳細は `/questop help` を参照。GUIウィザード（`/questop create gui`, `/questop edit <id>`）にも対応。

---

## クエストタイプ一覧（`QuestType`）

V1から引き継いだタイプとV2で追加されたタイプが混在する。

| カテゴリ | タイプ |
|---|---|
| V1引き継ぎ | `KILL_MOB`, `KILL_PLAYER`, `BREAK_BLOCK`, `COLLECT_ITEM`, `WALK_DISTANCE`, `CRAFT_ITEM`, `CUSTOM_EVENT` |
| V2追加 | `FISHING`, `FARMING`, `ENCHANT`, `TRADE`, `TAME`, `PLACE_BLOCK`, `BREAK_BLOCK_TYPE`, `SWIM_DISTANCE`, `TAKE_DAMAGE`, `LEVEL_UP`, `BOSS_KILL` |

`LEVEL_UP`のように「到達値」を扱うタイプは加算ではなく絶対値セット（`setAbsoluteProgress`）で処理され、後退や重複到達による誤加算を防いでいる。

## 報酬タイプ一覧（`RewardType`）

| タイプ | 説明 |
|---|---|
| `COMMAND` | コンソール経由でコマンドを実行（`%player%`プレースホルダー対応） |
| `MONEY` | Vault経由で金銭付与 |
| `ITEM` | アイテムを直接付与（インベントリ満杯時はドロップ） |
| `EXP` | 経験値付与 |
| `PERMISSION` | Vault Permissions経由で権限ノードを付与（称号・タグ用途） |

---

## 設定 (`config.yml`)

| キー | 説明 |
|---|---|
| `database.*` | MySQL接続情報（host / port / name / user / password / pool-size / connection-timeout） |
| `quest.max-active-quests` | 同時受注できる最大クエスト数（受注型のみ。`questpluginv2.bypass.limit`権限でバイパス可） |
| `quest.cooldown-enabled` | クエストのクールダウン有効化 |
| `party.default-max-size` | デフォルトのパーティー上限人数 |
| `party.invite-timeout-seconds` | パーティー招待のタイムアウト（秒） |
| `party.progress-share` | パーティーでのクエスト進捗共有（距離は見ず、全員に共有される） |
| `party.chat-prefix` | パーティーチャットのプレフィックス |
| `mythicmobs.enabled` | MythicMobs連携を有効化するか |
| `vault.enabled` | Vault連携を有効化するか |

## パーミッション

| パーミッション | デフォルト | 説明 |
|---|---|---|
| `questpluginv2.quest` | true | `/quest`コマンドの使用 |
| `questpluginv2.party` | true | `/party`コマンドの使用 |
| `questpluginv2.op` | op | `/questop`コマンドの使用 |
| `questpluginv2.bypass.limit` | op | 同時受注数上限のバイパス |

---

## データベース

MySQL（HikariCPでコネクションプーリング）を使用する。テーブル定義は`src/main/resources/database.sql`が単一の正（single source of truth）であり、`DatabaseManager`がこのファイルを読み込んで初期化を行う。

### テーブル一覧

| テーブル | 役割 |
|---|---|
| `quests` | クエスト定義 |
| `quest_rewards` | クエストごとの報酬（`quests`に対するFK、`ON DELETE CASCADE`） |
| `quest_npcs` | NPC定義（座標・Mob種別・紐づくクエストID） |
| `player_quest_progress` | プレイヤーごとの受注中/完了/失敗状態（`player_uuid`+`quest_id`の複合PK） |
| `player_quest_history` | クエスト完了・失敗の履歴（クールダウン判定にも使用） |
| `parties` | パーティー定義 |
| `party_members` | パーティー所属（LEADER/MEMBER） |
| `party_chat_log` | パーティーチャットのログ |

### スキーママイグレーション

`CREATE TABLE IF NOT EXISTS`では既存テーブルへのカラム追加が反映されないため、`DatabaseManager.migrateSchema()`が`INFORMATION_SCHEMA`でカラム存在を確認し、必要な`ALTER TABLE`のみを実行する。`initializeTables()`の直後に自動実行されるため、手動SQL適用は不要（ただし事前のDBバックアップは推奨）。

### 非同期化されている書き込み

メインスレッドのTPSへの影響を避けるため、以下はBukkitの非同期スケジューラ経由で書き込まれる。

- 進捗の保存（`WALK_DISTANCE`等、高頻度イベントの進捗更新）
- クエスト完了・履歴の記録
- パーティーのメンバー脱退・キック・チャットログ・リーダー変更

一方、パーティー作成・招待受理など「結果をすぐに利用者へ提示する必要がある」操作は同期処理のまま残されている。

---

## 依存プラグイン（任意）

- [Vault](https://www.spigotmc.org/resources/vault.34315/) — 金銭・権限報酬の付与に使用
- [MythicMobs](https://www.spigotmc.org/resources/mythicmobs-✪-best-custom-mob-skill-system-since-2014-✪.5702/) — `BOSS_KILL`タイプのクエストに使用

いずれも未導入の場合、該当機能は自動的に無効化され、他の機能には影響しない。