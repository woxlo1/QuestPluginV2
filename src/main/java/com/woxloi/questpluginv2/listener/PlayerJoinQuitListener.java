package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinQuitListener implements Listener {

    private final QuestPluginV2 plugin;

    public PlayerJoinQuitListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 非同期でDB読み込み
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                plugin.getQuestManager().loadPlayerProgress(event.getPlayer().getUniqueId())
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getQuestManager().unloadPlayerProgress(event.getPlayer().getUniqueId());

        // パーティーから退出処理（パーティー内チャット中の場合も含む）
        plugin.getPartyManager().leaveParty(event.getPlayer());
    }
}
