package com.woxloi.questpluginv2.model.quest;

/**
 * クエストの条件タイプ一覧
 * V1引き継ぎ + V2追加タイプ
 */
public enum QuestType {

    // ---- V1引き継ぎタイプ ----

    /** 指定エンティティを指定数討伐 */
    KILL_MOB,

    /** プレイヤーを指定数討伐 */
    KILL_PLAYER,

    /** 指定ブロックを指定数破壊 */
    BREAK_BLOCK,

    /** 指定アイテムを指定数収集（インベントリに所持） */
    COLLECT_ITEM,

    /** 指定距離を歩く */
    WALK_DISTANCE,

    /** 指定アイテムを指定数クラフト */
    CRAFT_ITEM,

    /** 会話型クエスト（カスタムイベント起動） */
    CUSTOM_EVENT,

    // ---- V2追加タイプ ----

    /** 釣りで指定アイテムを指定数釣る */
    FISHING,

    /** 指定作物を指定数収穫 */
    FARMING,

    /** 指定エンチャントを指定数付与 */
    ENCHANT,

    /** 村人と指定数取引する */
    TRADE,

    /** 指定エンティティをテイムする */
    TAME,

    /** 指定ブロックを指定数設置する */
    PLACE_BLOCK,

    /** 指定ブロック種別を指定数破壊する */
    BREAK_BLOCK_TYPE,

    /** 指定距離を泳ぐ */
    SWIM_DISTANCE,

    /** 指定ダメージ量を受ける */
    TAKE_DAMAGE,

    /** 指定レベルに到達する */
    LEVEL_UP,

    /** MythicMobs のボスを討伐する */
    BOSS_KILL;

    /**
     * 文字列からQuestTypeを取得する（大文字小文字無視）
     */
    public static QuestType fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
