package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.manager.PlayerQuestManager
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import org.bukkit.Material
import org.bukkit.entity.Player

class PlayerQuestMyListGUI(
    private val questplugin: QuestPluginV2,
    private val viewer: Player
) : OraInventory(plugin = questplugin, title = "§b§l自分の民間クエスト", rows = 6) {

    private val pqm: PlayerQuestManager get() = questplugin.playerQuestManager

    override fun onOpen(player: Player): Boolean {
        val created = pqm.getQuestsByCreator(player.uniqueId)
        created.take(18).forEachIndexed { i, quest ->
            val mat = if (quest.isOpen) Material.LIME_WOOL else Material.RED_WOOL
            val deletable = quest.acceptors.values.all { it } // 未完了の受注者がいなければ削除可
            val item = OraInventoryItem(mat)
                .setDisplayName("§e§l[${quest.id}] ${quest.targetMaterial.name}")
                .setLore(listOf(
                    "§7状態: ${if (quest.isOpen) "§a募集中" else "§c締切"}",
                    "§7受注: §f${quest.acceptedCount}/${quest.maxAcceptors}人 §7完了: §f${quest.completedCount}人",
                    "§71人あたり: §f${quest.perPersonAmount}個 / §a${quest.rewardMoney}円",
                    "",
                    if (deletable) "§c§lクリックで削除（未使用分は返金）" else "§8未完了の受注者がいるため削除不可"
                ))
                .setCanClick(false)
            if (deletable) {
                item.setClickEvent { e ->
                    val p = e.whoClicked as? Player ?: return@setClickEvent
                    when (pqm.delete(p, quest.id)) {
                        PlayerQuestManager.DeleteResult.SUCCESS -> {
                            p.sendMessage("${QuestPluginV2.PREFIX}§a§l削除しました")
                            PlayerQuestMyListGUI(questplugin, p).open(p)
                        }
                        else -> p.sendMessage("${QuestPluginV2.PREFIX}§c§l削除できませんでした")
                    }
                }
            }
            setItem(i, item)
        }

        val myAccepted = pqm.getQuestsAcceptedBy(player.uniqueId)
        myAccepted.take(18).forEachIndexed { i, quest ->
            val completed = quest.hasCompleted(player.uniqueId)
            val item = OraInventoryItem(quest.targetMaterial)
                .setDisplayName("§b§l[${quest.id}] ${quest.targetMaterial.name}")
                .setLore(listOf(
                    "§7必要数: §f${quest.perPersonAmount}個",
                    "§7状態: ${if (completed) "§a納品済み" else "§e未納品"}",
                    "",
                    if (!completed) "§a§lクリックで納品チェック" else "§8完了済み"
                ))
                .setCanClick(false)
            if (!completed) {
                item.setClickEvent { e ->
                    val p = e.whoClicked as? Player ?: return@setClickEvent
                    when (pqm.submit(p, quest.id)) {
                        PlayerQuestManager.SubmitResult.SUCCESS -> {
                            p.sendMessage("${QuestPluginV2.PREFIX}§a§l納品完了！報酬を受け取りました")
                            PlayerQuestMyListGUI(questplugin, p).open(p)
                        }
                        PlayerQuestManager.SubmitResult.NOT_ENOUGH_ITEMS -> {
                            val check = pqm.getLastSubmitCheck(p.uniqueId)
                            p.sendMessage("${QuestPluginV2.PREFIX}§c§lアイテムが足りませんあと${check[1] - check[0]}個必要です")
                        }
                        PlayerQuestManager.SubmitResult.CREATOR_INVENTORY_FULL ->
                            p.sendMessage("${QuestPluginV2.PREFIX}§c§l依頼主のインベントリに空きがありません")
                        else -> p.sendMessage("${QuestPluginV2.PREFIX}§c§l納品できませんでした")
                    }
                }
            }
            setItem(27 + i, item)
        }

        setItem(49, OraInventoryItem(Material.ARROW).setDisplayName("§e掲示板へ戻る").setCanClick(false)
            .setClickEvent { e -> (e.whoClicked as? Player)?.let { PlayerQuestBoardGUI(questplugin, it).open(it) } })

        return true
    }
}