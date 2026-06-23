package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

/**
 * プラグインの主要コマンドを初めて使った時にチュートリアルを表示するリスナー。
 * 初回のみコマンドを一旦キャンセルし、表示後に元のコマンドを自動で再実行する。
 */
public class TutorialTriggerListener implements Listener {

    private static final Set<String> TRIGGER_COMMANDS = Set.of("/quest", "/party", "/questop");

    private final QuestPluginV2 plugin;

    public TutorialTriggerListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (plugin.getTutorialManager().hasSeen(player.getUniqueId())) return;

        String message = event.getMessage();
        String firstToken = message.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!TRIGGER_COMMANDS.contains(firstToken)) return;
        if (message.equalsIgnoreCase("/quest tutorial")) return; // 無限ループ防止、素通しする

        event.setCancelled(true);
        plugin.getTutorialManager().markSeen(player);
        plugin.getTutorialManager().show(player);

        // チュートリアルの最初の数行を読む時間を確保してから、元のコマンドを自動で再実行する
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) Bukkit.dispatchCommand(player, message.substring(1));
        }, 10L);
    }
}