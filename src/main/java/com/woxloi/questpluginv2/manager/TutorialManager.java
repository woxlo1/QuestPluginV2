package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 初回コマンド実行時に表示するチュートリアル機能。
 * 既読情報は tutorial_progress テーブルに保存し、起動時に全件メモリへ読み込んでキャッシュする。
 */
public class TutorialManager {

    private final QuestPluginV2 plugin;
    private final DatabaseManager db;
    private final Set<UUID> seen = ConcurrentHashMap.newKeySet();

    public TutorialManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        loadAll();
    }

    private void loadAll() {
        try {
            db.query("SELECT player_uuid FROM tutorial_progress", null, rs -> {
                while (rs.next()) seen.add(UUID.fromString(rs.getString("player_uuid")));
                return null;
            });
            plugin.getLogger().info(seen.size() + "人分のチュートリアル既読情報を読み込みました。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "チュートリアル進捗読み込み中にエラー: " + e.getMessage(), e);
        }
    }

    public boolean hasSeen(UUID uuid) {
        return seen.contains(uuid);
    }

    /** 既読フラグを立てる（メモリ即時反映 + DB非同期保存、既に既読なら何もしない） */
    public void markSeen(Player player) {
        if (!seen.add(player.getUniqueId())) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("INSERT INTO tutorial_progress (player_uuid, player_name) VALUES (?, ?) " +
                                "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)",
                        ps -> {
                            ps.setString(1, player.getUniqueId().toString());
                            ps.setString(2, player.getName());
                        });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "チュートリアル既読保存中にエラー: " + e.getMessage(), e);
            }
        });
    }

    /** チュートリアルをチャットに表示する（読みやすさのため少しずつ間隔を空けて送信） */
    public void show(Player player) {
        send(player, 0,   "§a§l" + "=".repeat(36));
        send(player, 0,   "§6§l QuestPluginV2 へようこそ！");
        send(player, 0,   "§a§l" + "=".repeat(36));
        send(player, 20,  "§f§l最初に知っておくと便利なコマンドを紹介します。");

        send(player, 40,  "§e§l/quest gui §7§l- クエスト一覧をGUIで見る");
        send(player, 60,  "§e§l/quest list §7§l- 受注可能なクエスト一覧（テキスト）");
        send(player, 80,  "§e§l/quest accept <id> §7§l- クエストを受注する");

        send(player, 110, "§b§l-- 民間クエスト（プレイヤー同士の依頼） --");
        send(player, 130, "§e§l/quest player gui §7§l- 民間クエスト掲示板を開く");
        send(player, 150, "§e§l/quest player create §7§l- 依頼を作成する（アイテムを集めてもらう）");
        send(player, 170, "§e§l/quest player submit <id> §7§l- 集めたアイテムを納品する");

        send(player, 200, "§d§l-- パーティー --");
        send(player, 220, "§e§l/party create §7§l- パーティーを作る");
        send(player, 240, "§e§l/party invite <player> §7§l- 仲間を招待する");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§a§l" + "=".repeat(36));
            player.spigot().sendMessage(
                    new ComponentBuilder("§f§lいつでも ")
                            .append("§b§l[ここをクリックでもう一度表示]")
                            .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest tutorial"))
                            .create()
            );
        }, 270L);
    }

    private void send(Player player, long delayTicks, String message) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.sendMessage(message);
        }, delayTicks);
    }
}