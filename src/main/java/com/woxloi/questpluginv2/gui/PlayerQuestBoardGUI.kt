package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.manager.PlayerQuestManager
import com.woxloi.questpluginv2.model.playerquest.PlayerQuest
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import org.bukkit.Material
import org.bukkit.entity.Player

class PlayerQuestBoardGUI(
    private val questplugin: QuestPluginV2,
    private val viewer: Player,
    private val page: Int = 0
) : OraInventory(plugin = questplugin, title = "§6§l民間クエスト掲示板 (ページ ${page + 1})", rows = 6) {

    private val pqm: PlayerQuestManager get() = questplugin.playerQuestManager
    private val filler = OraInventoryItem(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("§r").setCanClick(false)

    override fun onOpen(player: Player): Boolean {
        for (i in 45 until 54) setItem(i, filler)

        val quests = pqm.openQuests.sortedByDescending { it.createdAt }
        val perPage = 45
        val maxPage = ((quests.size - 1) / perPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, maxPage)
        val pageQuests = quests.drop(currentPage * perPage).take(perPage)

        pageQuests.forEachIndexed { i, quest -> setItem(i, buildQuestItem(quest)) }

        if (currentPage > 0) {
            setItem(45, OraInventoryItem(Material.ARROW).setDisplayName("§e前のページ").setCanClick(false)
                .setClickEvent { e -> (e.whoClicked as? Player)?.let { PlayerQuestBoardGUI(questplugin, it, currentPage - 1).open(it) } })
        }

        setItem(48, OraInventoryItem(Material.BOOK)
            .setDisplayName("§fページ ${currentPage + 1}/${maxPage + 1}")
            .setLore(listOf("§7全${quests.size}件")).setCanClick(false))

        setItem(49, OraInventoryItem(Material.WRITABLE_BOOK)
            .setDisplayName("§a§l新規クエスト作成")
            .setLore(listOf("§7募集を開始する"))
            .setCanClick(false)
            .setClickEvent { e -> (e.whoClicked as? Player)?.let { it.closeInventory(); PlayerQuestCreateGUI(questplugin, it).open(it) } }
        )

        setItem(50, OraInventoryItem(Material.PLAYER_HEAD)
            .setDisplayName("§b§l自分のクエスト")
            .setLore(listOf("§7作成・受注したクエストを確認"))
            .setCanClick(false)
            .setClickEvent { e -> (e.whoClicked as? Player)?.let { it.closeInventory(); PlayerQuestMyListGUI(questplugin, it).open(it) } }
        )

        if (currentPage < maxPage) {
            setItem(53, OraInventoryItem(Material.ARROW).setDisplayName("§e次のページ").setCanClick(false)
                .setClickEvent { e -> (e.whoClicked as? Player)?.let { PlayerQuestBoardGUI(questplugin, it, currentPage + 1).open(it) } })
        }
        return true
    }

    private fun buildQuestItem(quest: PlayerQuest): OraInventoryItem {
        val isAccepting = quest.isAccepting(viewer.uniqueId)
        val isOwn = quest.creatorUUID == viewer.uniqueId

        val lore = mutableListOf(
            "§7依頼主: §f${quest.creatorName}",
            "§7目標: §f${quest.targetMaterial.name} x${quest.perPersonAmount}§7（1人あたり）",
            "§7募集: §f${quest.acceptedCount}/${quest.maxAcceptors}人",
            "§a報酬: §f${quest.rewardMoney}円§7（1人あたり）",
            ""
        )

        when {
            isOwn -> lore.add("§7§l自分が作成したクエストです")
            isAccepting && quest.hasCompleted(viewer.uniqueId) -> lore.add("§a§l納品済み")
            isAccepting -> { lore.add("§e§l受注中"); lore.add("§7§l/quest player submit ${quest.id} §7§lで納品") }
            quest.isFull -> lore.add("§8§l募集締切")
            else -> lore.add("§a§lクリックで受注")
        }

        val item = OraInventoryItem(quest.targetMaterial)
            .setDisplayName("§e§l[${quest.id}] ${quest.targetMaterial.name}")
            .setLore(lore)
            .setCanClick(false)

        if (!isOwn && !isAccepting && !quest.isFull) {
            item.setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                when (pqm.accept(p, quest.id)) {
                    PlayerQuestManager.AcceptResult.SUCCESS -> {
                        p.sendMessage("${QuestPluginV2.PREFIX}§a§l受注しました！${quest.targetMaterial.name}を${quest.perPersonAmount}個集めてください")
                        p.closeInventory()
                    }
                    else -> p.sendMessage("${QuestPluginV2.PREFIX}§c§l受注できませんでした")
                }
            }
        }
        return item
    }
}