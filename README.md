# QuestPluginV2

Minecraft Spigot プラグイン。プレイヤーがクエストを受注し、達成することで報酬を獲得できるシステムです。

## 機能

### クエスト機能
- **複数のクエスト条件タイプ** - MOB討伐、ブロック破壊、アイテム収集など20以上のクエストタイプに対応
- **クエスト進捗追跡** - プレイヤーの進捗を自動的に記録・更新
- **動的報酬システム** - 経験値、お金、アイテムなど複数の報酬タイプに対応

### パーティ機能
- **パーティ作成・管理** - プレイヤー同士がパーティを組成
- **パーティロール** - リーダー、メンバーなど役割を設定可能
- **パーティ内クエスト共有** - パーティメンバー間でクエスト進捗を共有

### 連携機能
- **MythicMobs対応** - MythicMobsボスのクエスト化に対応
- **Vault連携** - 経済プラグインとの連携

## 対応クエストタイプ

### V1引き継ぎ
- `KILL_MOB` - エンティティ討伐
- `KILL_PLAYER` - プレイヤー討伐
- `BREAK_BLOCK` - ブロック破壊
- `COLLECT_ITEM` - アイテム収集
- `WALK_DISTANCE` - 移動距離
- `CRAFT_ITEM` - クラフト
- `CUSTOM_EVENT` - カスタムイベント

### V2追加
- `FISHING` - 釣り
- `FARMING` - 農作物収穫
- `ENCHANT` - エンチャント付与
- `TRADE` - 村人取引
- `TAME` - テイム
- `PLACE_BLOCK` - ブロック設置
- `BREAK_BLOCK_TYPE` - ブロック種別破壊
- `SWIM_DISTANCE` - 移動距離（水中）
- `TAKE_DAMAGE` - ダメージ受ける
- `LEVEL_UP` - レベルアップ
- `BOSS_KILL` - MythicMobsボス討伐

## インストール

1. `pom.xml`がある状態でビルド
```bash
mvn clean package
```
2. 生成された JAR ファイルを、サーバーの plugins/ ディレクトリに配置
3. サーバーを再起動

## LICENSE
MIT License