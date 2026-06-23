package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.manager.PlayerQuestManager
import com.woxloi.questpluginv2.model.playerquest.PlayerQuestDraft
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import oraserver.orapluginapi.text.OraInput
import org.bukkit.Material
import org.bukkit.entity.Player

class PlayerQuestCreateGUI(
    private val questplugin: QuestPluginV2,
    private val creator: Player
) : OraInventory(plugin = questplugin, title = "§a§l民間クエスト作成", rows = 4) {

    private val pqm: PlayerQuestManager get() = questplugin.playerQuestManager

    private val glass = OraInventoryItem(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("§r").setCanClick(false)
    private val borderGlass = OraInventoryItem(Material.LIME_STAINED_GLASS_PANE).setDisplayName("§r").setCanClick(false)

    override fun onOpen(player: Player): Boolean {
        val draft = pqm.getDraft(player.uniqueId) ?: pqm.startDraft(player)
        render(draft)
        return true
    }

    private fun render(draft: PlayerQuestDraft) {
        for (i in 0 until 36) {
            val row = i / 9
            setItem(i, if (row == 0 || row == 3) borderGlass else glass)
        }

        // ---- ターゲットアイテム ----
        setItem(10, OraInventoryItem(draft.targetMaterial ?: Material.BARRIER)
            .setDisplayName("§b§lターゲットアイテム")
            .setLore(listOf("§7現在値: §f${draft.targetMaterial?.name ?: "未設定"}", "", "§aクリックして選択"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                MaterialSelectCategoryGUI(questplugin, p,
                    onSelect = { material ->
                        draft.targetMaterial = material
                        PlayerQuestCreateGUI(questplugin, p).open(p)
                    },
                    onCancel = { PlayerQuestCreateGUI(questplugin, p).open(p) }
                ).open(p)
            }
        )

        // ---- 合計必要数 ----
        setItem(12, OraInventoryItem(Material.CHEST)
            .setDisplayName("§b§l合計必要数")
            .setLore(listOf("§7現在値: §f${draft.totalAmount}個", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                OraInput.int(p, questplugin) {
                    prompt("${QuestPluginV2.PREFIX}§a§l合計必要数を入力してください")
                    cancelKeyword("cancel")
                    validate { it > 0 }
                    onInvalid { p.sendMessage("${QuestPluginV2.PREFIX}§c§l1以上の整数を入力してください") }
                    onCancel { PlayerQuestCreateGUI(questplugin, p).open(p) }
                    onReceive { value -> draft.totalAmount = value; PlayerQuestCreateGUI(questplugin, p).open(p) }
                }
            }
        )

        // ---- 募集人数 ----
        setItem(14, OraInventoryItem(Material.PLAYER_HEAD)
            .setDisplayName("§b§l募集人数")
            .setLore(listOf("§7現在値: §f${draft.maxAcceptors}人", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                OraInput.int(p, questplugin) {
                    prompt("${QuestPluginV2.PREFIX}§a§l募集人数を入力してください")
                    cancelKeyword("cancel")
                    validate { it in 1..100 }
                    onInvalid { p.sendMessage("${QuestPluginV2.PREFIX}§c§l1〜100の整数を入力してください") }
                    onCancel { PlayerQuestCreateGUI(questplugin, p).open(p) }
                    onReceive { value -> draft.maxAcceptors = value; PlayerQuestCreateGUI(questplugin, p).open(p) }
                }
            }
        )

        // ---- 1人あたりの報酬 ----
        setItem(16, OraInventoryItem(Material.GOLD_INGOT)
            .setDisplayName("§b§l1人あたりの報酬")
            .setLore(listOf("§7現在値: §f${draft.rewardMoney}円", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                OraInput.int(p, questplugin) {
                    prompt("${QuestPluginV2.PREFIX}§a§l1人あたりの報酬額(円)を入力してください")
                    cancelKeyword("cancel")
                    validate { it >= 0 }
                    onInvalid { p.sendMessage("${QuestPluginV2.PREFIX}§c§l0以上の整数を入力してください") }
                    onCancel { PlayerQuestCreateGUI(questplugin, p).open(p) }
                    onReceive { value -> draft.rewardMoney = value.toDouble(); PlayerQuestCreateGUI(questplugin, p).open(p) }
                }
            }
        )

        // ---- プレビュー ----
        val totalEscrow = draft.rewardMoney * draft.maxAcceptors
        setItem(22, OraInventoryItem(Material.WRITABLE_BOOK)
            .setDisplayName("§f§l現在の設定")
            .setLore(listOf(
                "§7ターゲット: §f${draft.targetMaterial?.name ?: "未設定"}",
                "§7合計必要数: §f${draft.totalAmount}個",
                "§7募集人数: §f${draft.maxAcceptors}人",
                "§7§l→ 1人あたり: §e§l${draft.perPersonAmount}個",
                "§71人あたりの報酬: §f${draft.rewardMoney}円",
                "§c§l公開時のデポジット合計: ${totalEscrow}円"
            ))
            .setCanClick(false)
        )

        // ---- キャンセル ----
        setItem(27, OraInventoryItem(Material.BARRIER)
            .setDisplayName("§c§lキャンセル")
            .setLore(listOf("§7作成を中止します"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                pqm.clearDraft(p.uniqueId)
                p.closeInventory()
                p.sendMessage("${QuestPluginV2.PREFIX}§e§l作成をキャンセルしました")
            }
        )

        // ---- 公開 ----
        val canPublish = draft.isReadyToPublish
        setItem(35, OraInventoryItem(if (canPublish) Material.EMERALD_BLOCK else Material.REDSTONE_BLOCK)
            .setDisplayName(if (canPublish) "§a§l掲示板に公開" else "§c§l公開不可（未設定項目あり）")
            .setLore(if (canPublish) listOf("§c§l${totalEscrow}円がデポジットされます") else listOf("§7ターゲット・必要数・募集人数を設定してください"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                if (!canPublish) { p.sendMessage("${QuestPluginV2.PREFIX}§c§l未設定項目があります"); return@setClickEvent }
                when (pqm.publish(p)) {
                    PlayerQuestManager.PublishResult.SUCCESS -> {
                        p.sendMessage("${QuestPluginV2.PREFIX}§a§l民間クエストを公開しました！")
                        p.closeInventory()
                    }
                    PlayerQuestManager.PublishResult.VAULT_DISABLED ->
                        p.sendMessage("${QuestPluginV2.PREFIX}§c§lVaultが有効になっていません")
                    PlayerQuestManager.PublishResult.INSUFFICIENT_FUNDS ->
                        p.sendMessage("${QuestPluginV2.PREFIX}§c§l残高が不足しています${totalEscrow}円必要です")
                    else -> {
                        p.sendMessage("${QuestPluginV2.PREFIX}§c§l公開に失敗しました")
                        PlayerQuestCreateGUI(questplugin, p).open(p)
                    }
                }
            }
        )
    }
}