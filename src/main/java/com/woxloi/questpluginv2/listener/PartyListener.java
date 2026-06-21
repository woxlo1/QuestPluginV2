package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.manager.PartyManager;
import com.woxloi.questpluginv2.model.party.Party;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * パーティー関連のイベントリスナー
 */
public class PartyListener implements Listener {

    private final QuestPluginV2 plugin;
    private final PartyManager partyManager;

    public PartyListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.partyManager = plugin.getPartyManager();
    }

    /**
     * パーティーチャット検知
     * メッセージが ! で始まる場合はパーティーチャットとして処理
     */
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        if (!message.startsWith("!")) return;

        Party party = partyManager.getPlayerParty(event.getPlayer().getUniqueId());
        if (party == null) return;

        event.setCancelled(true);
        String content = message.substring(1).trim();
        partyManager.sendPartyChat(party, event.getPlayer(), content);
    }
}
