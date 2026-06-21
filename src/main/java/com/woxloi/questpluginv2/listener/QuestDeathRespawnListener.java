package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * クエスト進行中の死亡・リスポーン処理
 * V1 の QuestDeathListener + QuestRespawnListener に相当
 *
 * 修正点: onPlayerQuit() から unloadPlayerProgress() / leaveParty() の呼び出しを削除した。
 * これらは PlayerJoinQuitListener.onQuit() と全く同じ PlayerQuitEvent に対して
 * 重複登録されており、プレイヤー退出時に常に2回実行されていた
 * （実害は小さいが、無駄な処理であり意図も分かりにくいため解消する）。
 * このリスナーは「進行中クエストの自動キャンセル」のみを担当する。
 */
public class QuestDeathRespawnListener implements Listener {

    private final QuestPluginV2 plugin;

    public QuestDeathRespawnListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    /** 死亡時：ライフを減らし、0 になればクエスト失敗 */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        var player = event.getEntity();
        if (!plugin.getActiveQuestManager().isQuesting(player.getUniqueId())) return;

        int remaining = plugin.getActiveQuestManager().onPlayerDeath(player);
        if (remaining >= 0) {
            player.sendMessage(QuestPluginV2.PREFIX + "§c§l残りライフ: §f" + remaining);
        }
    }

    /** リスポーン時：元の場所（またはパーティーメンバーそば）へ戻す */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var player = event.getPlayer();
        Location loc = plugin.getActiveQuestManager().getRespawnLocation(player);
        if (loc != null) {
            event.setRespawnLocation(loc);
            player.sendMessage(QuestPluginV2.PREFIX + "§e§lクエスト開始地点へリスポーンしました");
        }
    }

    /** ログアウト時：進行中なら自動キャンセル（QuestManager/PartyManager側の後始末は PlayerJoinQuitListener が担当） */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getActiveQuestManager().isQuesting(event.getPlayer().getUniqueId())) {
            plugin.getActiveQuestManager().cancelQuest(event.getPlayer());
        }
    }
}