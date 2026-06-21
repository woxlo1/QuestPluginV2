package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.model.quest.Quest
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import oraserver.orapluginapi.text.OraInput
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * クエスト編集GUI
 *
 * /questop create gui (QuestCreateGUI) で新規作成した後、
 * 既存クエストの各項目を個別に編集するためのメニュー。
 *
 * 各ボタンをクリックするとチャット入力ウィザードが開始され、
 * 入力を受け取ったら即座にDBへ保存（QuestManager.saveQuest）する。
 * 値を変更する度に画面を再描画して、現在値を常に確認できるようにしている。
 */
class QuestEditGUI(
    private val questplugin: QuestPluginV2,
    private val questId: String
) : OraInventory(
    plugin = questplugin,
    title  = "§a§lクエスト編集 — $questId",
    rows   = 6
) {

    private val glass = OraInventoryItem(Material.GRAY_STAINED_GLASS_PANE)
        .setDisplayName("§r")
        .setCanClick(false)

    private val borderGlass = OraInventoryItem(Material.CYAN_STAINED_GLASS_PANE)
        .setDisplayName("§r")
        .setCanClick(false)

    /** 最新のクエストを取得する。削除されていた場合は null */
    private fun quest(): Quest? = questplugin.questManager.getQuest(questId)

    // ==========================================================
    //  OraInventory — onOpen
    // ==========================================================

    override fun onOpen(player: Player): Boolean {
        val quest = quest()
        if (quest == null) {
            player.sendMessage("${QuestPluginV2.PREFIX}§c§l${questId}が見つかりません")
            return false
        }

        render(quest)
        return true
    }

    /** 現在のクエスト状態に基づいて画面全体を描き直す */
    private fun render(quest: Quest) {
        for (i in 0 until 54) {
            val row = i / 9
            val col = i % 9
            if (row == 0 || row == 5 || col == 0 || col == 8) {
                setItem(i, borderGlass)
            } else {
                setItem(i, glass)
            }
        }

        // タイトルアイテム (slot 4)
        setItem(4, OraInventoryItem(Material.WRITABLE_BOOK)
            .setDisplayName("§6§l【クエスト編集】")
            .setLore(listOf("§7ID: §f${quest.id}", "§7項目をクリックして編集します"))
            .setCanClick(false)
        )

        // ---- 説明 (slot 11) ----
        setItem(11, OraInventoryItem(Material.PAPER)
            .setDisplayName("§e§l説明")
            .setLore(listOf("§7現在値: §f${quest.description}", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askDesc(p)
            }
        )

        // ---- 必要数 (slot 13) ----
        setItem(13, OraInventoryItem(Material.TARGET)
            .setDisplayName("§e§l必要数")
            .setLore(listOf("§7現在値: §f${quest.requiredAmount}", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askAmount(p)
            }
        )

        // ---- クールダウン (slot 15) ----
        setItem(15, OraInventoryItem(Material.CLOCK)
            .setDisplayName("§e§lクールダウン")
            .setLore(listOf("§7現在値: §f${quest.cooldownSeconds}秒", "", "§aクリックして変更"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askCooldown(p)
            }
        )

        // ---- 制限時間 (slot 20) ----
        setItem(20, OraInventoryItem(Material.RECOVERY_COMPASS)
            .setDisplayName("§e§l制限時間")
            .setLore(listOf(
                "§7現在値: §f${quest.timeLimitSeconds?.let { "${it}秒" } ?: "無制限"}",
                "",
                "§aクリックして変更 §7(0で無制限)"
            ))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askTimeLimit(p)
            }
        )

        // ---- ライフ数 (slot 22) ----
        setItem(22, OraInventoryItem(Material.TOTEM_OF_UNDYING)
            .setDisplayName("§e§lライフ数")
            .setLore(listOf(
                "§7現在値: §f${quest.maxLives?.toString() ?: "無制限"}",
                "",
                "§aクリックして変更 §7(0で無制限)"
            ))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askMaxLives(p)
            }
        )

        // ---- 前提クエスト (slot 24) ----
        setItem(24, OraInventoryItem(Material.NAME_TAG)
            .setDisplayName("§e§l前提クエスト")
            .setLore(listOf(
                "§7現在値: §f${quest.prerequisiteQuestId ?: "なし"}",
                "",
                "§aクリックして変更 §7(noneで解除)"
            ))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askPrereq(p)
            }
        )

        // ---- パーティー対応 (slot 29) — トグル式、再度開く必要なし ----
        setItem(29, OraInventoryItem(if (quest.isPartyEnabled) Material.LIME_DYE else Material.GRAY_DYE)
            .setDisplayName("§e§lパーティー対応")
            .setLore(listOf(
                "§7現在値: §f${if (quest.isPartyEnabled) "有効" else "無効"}",
                "",
                "§aクリックで切り替え"
            ))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                quest.isPartyEnabled = !quest.isPartyEnabled
                if (questplugin.questManager.saveQuest(quest)) {
                    p.sendMessage("${QuestPluginV2.PREFIX}§a§lパーティー対応を${if (quest.isPartyEnabled) "有効" else "無効"}にしました")
                    render(quest)
                } else {
                    p.sendMessage("${QuestPluginV2.PREFIX}§c§l保存に失敗しました")
                }
            }
        )

        // ---- 報酬一覧 (slot 31) ----
        setItem(31, OraInventoryItem(Material.CHEST)
            .setDisplayName("§e§l報酬 (${quest.rewards.size}件)")
            .setLore(
                if (quest.rewards.isEmpty()) {
                    listOf("§7報酬は設定されていません", "", "§aクリックして追加")
                } else {
                    quest.rewards.map { "§7- ${it.serialize()}" } + listOf("", "§aクリックして追加")
                }
            )
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                askAddReward(p)
            }
        )

        // ---- 報酬クリア (slot 33) ----
        setItem(33, OraInventoryItem(Material.LAVA_BUCKET)
            .setDisplayName("§c§l報酬をすべて削除")
            .setLore(listOf("§7クリックして全報酬を削除します"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                quest.rewards.clear()
                if (questplugin.questManager.saveQuest(quest)) {
                    p.sendMessage("${QuestPluginV2.PREFIX}§a§l報酬をすべて削除しました")
                    render(quest)
                } else {
                    p.sendMessage("${QuestPluginV2.PREFIX}§c§l保存に失敗しました")
                }
            }
        )

        // ---- 戻る/閉じる (slot 49) ----
        setItem(49, OraInventoryItem(Material.BARRIER)
            .setDisplayName("§c§l閉じる")
            .setLore(listOf("§7編集を終了します"))
            .setCanClick(false)
            .setClickEvent { e ->
                val p = e.whoClicked as? Player ?: return@setClickEvent
                p.closeInventory()
                p.sendMessage("${QuestPluginV2.PREFIX}§a§l編集を終了しました")
            }
        )
    }

    // ==========================================================
    //  チャット入力ウィザード
    // ==========================================================

    private fun askDesc(player: Player) {
        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l新しい説明を入力してください")
            cancelKeyword("cancel")
            convert { it.trim() }
            validate { it.isNotBlank() }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l説明を入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.description = value
                save(player, q, "説明を更新しました")
            }
        }
    }

    private fun askAmount(player: Player) {
        OraInput.int(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l新しい必要数を入力してください §7(例: 10)")
            cancelKeyword("cancel")
            validate { it > 0 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l1以上の整数を入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.requiredAmount = value
                save(player, q, "必要数を更新しました")
            }
        }
    }

    private fun askCooldown(player: Player) {
        OraInput.int(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l新しいクールダウン（秒）を入力してください §7(0で無し)")
            cancelKeyword("cancel")
            validate { it >= 0 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l0以上の整数を入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.cooldownSeconds = value.toLong()
                save(player, q, "クールダウンを更新しました")
            }
        }
    }

    private fun askTimeLimit(player: Player) {
        OraInput.int(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l新しい制限時間（秒）を入力してください §7(0で無制限)")
            cancelKeyword("cancel")
            validate { it >= 0 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l0以上の整数を入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.timeLimitSeconds = if (value == 0) null else value.toLong()
                save(player, q, "制限時間を更新しました（0=無制限）")
            }
        }
    }

    private fun askMaxLives(player: Player) {
        OraInput.int(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l新しいライフ数を入力してください §7(0で無制限)")
            cancelKeyword("cancel")
            validate { it >= 0 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l0以上の整数を入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.maxLives = if (value == 0) null else value
                save(player, q, "ライフ数を更新しました（0=無制限）")
            }
        }
    }

    private fun askPrereq(player: Player) {
        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l前提クエストのIDを入力してください §7(noneで解除)")
            cancelKeyword("cancel")
            convert { it.trim() }
            validate { id ->
                id.equals("none", ignoreCase = true) || questplugin.questManager.getQuest(id) != null
            }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l存在するクエストIDかnoneを入力してください") }
            onCancel { reopenOrNotify(player) }
            onReceive { value ->
                val q = quest() ?: return@onReceive
                q.prerequisiteQuestId = if (value.equals("none", ignoreCase = true)) null else value
                save(player, q, "前提クエストを更新しました")
            }
        }
    }

    private fun askAddReward(player: Player) {
        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l報酬データを入力してください §7(例: MONEY:100)")
            cancelKeyword("cancel")
            convert { it.trim() }
            validate { com.woxloi.questpluginv2.model.reward.QuestReward.deserialize(it) != null }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l不正な報酬データです") }
            onCancel { reopenOrNotify(player) }
            onReceive { raw ->
                val q = quest() ?: return@onReceive
                val reward = com.woxloi.questpluginv2.model.reward.QuestReward.deserialize(raw) ?: return@onReceive
                q.addReward(reward)
                save(player, q, "報酬を追加しました")
            }
        }
    }

    // ==========================================================
    //  共通ヘルパー
    // ==========================================================

    /** 保存して結果を通知し、GUIを再度開く */
    private fun save(player: Player, quest: Quest, successMessage: String) {
        if (questplugin.questManager.saveQuest(quest)) {
            player.sendMessage("${QuestPluginV2.PREFIX}§a§l$successMessage")
        } else {
            player.sendMessage("${QuestPluginV2.PREFIX}§c§l保存中にエラーが発生しました")
        }
        reopenOrNotify(player)
    }

    /** クエストが存在する限り編集GUIを再度開き、無ければ通知のみ行う */
    private fun reopenOrNotify(player: Player) {
        if (quest() == null) {
            player.sendMessage("${QuestPluginV2.PREFIX}§c§l${questId}は存在しません")
            return
        }
        QuestEditGUI(questplugin, questId).open(player)
    }
}