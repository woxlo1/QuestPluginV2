package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.model.quest.QuestType;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * MythicMobs連携リスナー
 * MythicMobsが導入されている場合のみ登録される
 *
 * 修正点: BOSS_KILL は /quest start 経由（ActiveQuestManager管理）の
 * クエストでも使われ得るため、QuestManager だけでなく
 * ActiveQuestManager にも進捗を通知するようにした。
 */
public class MythicMobsListener implements Listener {

    private final QuestPluginV2 plugin;

    public MythicMobsListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        // キラーがプレイヤーでない場合はスキップ
        if (!(event.getKiller() instanceof Player killer)) return;

        String mobName = event.getMobType().getInternalName();
        plugin.getQuestManager().addProgress(
                killer.getUniqueId(),
                QuestType.BOSS_KILL,
                mobName,
                1
        );
        plugin.getActiveQuestManager().addProgress(
                killer.getUniqueId(),
                QuestType.BOSS_KILL,
                mobName,
                1
        );
    }
}