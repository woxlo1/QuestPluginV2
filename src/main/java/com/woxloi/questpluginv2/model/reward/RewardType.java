package com.woxloi.questpluginv2.model.reward;

/**
 * クエスト報酬タイプ一覧
 */
public enum RewardType {

    /** コマンド実行（プレースホルダー %player% 対応） */
    COMMAND,

    /** Vault経由で金銭付与 */
    MONEY,

    /** アイテム直接付与 */
    ITEM,

    /** 経験値付与 */
    EXP,

    /** Permissionsへ権限ノードを追加（称号/タグ付与） */
    PERMISSION;

    public static RewardType fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
