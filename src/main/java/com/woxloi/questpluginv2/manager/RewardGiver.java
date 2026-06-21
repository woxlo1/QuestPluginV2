package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.model.quest.Quest;
import com.woxloi.questpluginv2.model.reward.QuestReward;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * クエスト報酬の付与処理。
 *
 * 以前は QuestManager.giveRewards() と ActiveQuestManager.giveRewards() に
 * 全く同じロジックが重複していたため、この1か所に統一した。
 * 両マネージャーはここを呼び出すだけにする。
 */
public final class RewardGiver {

    private RewardGiver() {}

    public static void give(QuestPluginV2 plugin, Player player, Quest quest) {
        VaultManager vault = plugin.getVaultManager();
        for (QuestReward reward : quest.getRewards()) {
            switch (reward.getType()) {
                case COMMAND -> {
                    String cmd = reward.getCommand().replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
                case MONEY -> {
                    if (vault.isEnabled()) {
                        vault.getEconomy().depositPlayer(player, reward.getAmount());
                        player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + reward.getAmount() + "円を受け取りました");
                    }
                }
                case ITEM -> {
                    ItemStack item = reward.toItemStack();
                    if (item != null) {
                        var overflow = player.getInventory().addItem(item);
                        if (!overflow.isEmpty()) {
                            overflow.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                        }
                        player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + reward.getItemAmount()
                                + "x " + reward.getMaterial().name() + "を受け取りました");
                    }
                }
                case EXP -> {
                    player.giveExp(reward.getExpAmount());
                    player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + reward.getExpAmount() + "経験値を受け取りました");
                }
                case PERMISSION -> {
                    var perms = vault.getPermission();
                    if (perms != null) {
                        perms.playerAdd(player, reward.getPermission());
                        player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + reward.getPermission() + "を取得しました");
                    }
                }
            }
        }
    }
}