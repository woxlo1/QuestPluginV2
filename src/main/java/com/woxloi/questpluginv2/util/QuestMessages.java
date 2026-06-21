package com.woxloi.questpluginv2.util;

/**
 * メッセージプレフィックスを util パッケージ側からも参照できるようにするための定数保持クラス。
 * QuestPluginV2.PREFIX と同じ値を持つ（QuestPluginV2 への直接依存を避けるため分離）。
 */
public final class QuestMessages {

    public static final String PREFIX = "§e§l[§6§lQuestPlugin§d§lV2§e§l]§r";

    private QuestMessages() {}
}