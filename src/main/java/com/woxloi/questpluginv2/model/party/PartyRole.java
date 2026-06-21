package com.woxloi.questpluginv2.model.party;

/**
 * パーティー内でのロール
 */
public enum PartyRole {

    /** パーティーリーダー。解散・キック・上限変更が可能 */
    LEADER,

    /** 一般メンバー */
    MEMBER;

    public String getDisplayName() {
        return switch (this) {
            case LEADER -> "§a§lリーダー";
            case MEMBER -> "§c§lメンバー";
        };
    }
}
