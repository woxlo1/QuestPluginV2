package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.model.npc.QuestNpc;
import com.woxloi.questpluginv2.model.quest.Quest;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * 独自NPC（Villager）への右クリックを検知し、
 * 挨拶・クエスト情報・受注ボタンを表示するリスナー。
 */
public class NpcInteractListener implements Listener {

    private final QuestPluginV2 plugin;

    public NpcInteractListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        QuestNpc npc = plugin.getNpcManager().getNpcByEntity(event.getRightClicked().getUniqueId());
        if (npc == null) return;

        event.setCancelled(true);
        var player = event.getPlayer();

        player.sendMessage(QuestPluginV2.PREFIX + "§e§l" + npc.getName() + "§7§l: " + (npc.getGreeting() != null ? npc.getGreeting() : "やあ、冒険者！"));

        String questId = npc.getQuestId();
        if (questId == null) return;

        Quest quest = plugin.getQuestManager().getQuest(questId);
        if (quest == null) {
            player.sendMessage(QuestPluginV2.PREFIX + "§c§lクエストが見つかりませんでした");
            return;
        }

        player.sendMessage("§6§lクエスト: §f§l" + quest.getName());
        player.sendMessage("§7§l" + quest.getObjectiveDescription());

        player.spigot().sendMessage(
                new ComponentBuilder("§a§l[クリックでクエストを受注]")
                        .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest accept " + questId))
                        .create()
        );
    }
}