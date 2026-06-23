package com.woxloi.questpluginv2.command

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.model.quest.Quest
import oraserver.orapluginapi.commandapi.OraCommandData
import oraserver.orapluginapi.commandapi.OraCommandLiteral
import oraserver.orapluginapi.commandapi.ToolTip
import oraserver.orapluginapi.commandapi.argumenttype.IntArg
import oraserver.orapluginapi.commandapi.argumenttype.StringArg

/**
 * /quest コマンド (Kotlin 版)
 *
 * サブコマンド:
 *   help
 *   list [page]
 *   info <id>
 *   accept <id>
 *   abandon <id>
 *   progress
 *   party <id>
 *   start <id>
 *   leave
 *   gui
 *
 * 修正点:
 *  - ヘルプ表示を HelpMenu に委譲（QuestOpCommand/QuestPartyCommandとの重複解消）。
 *  - /quest start <id> にパーティーリーダー権限チェックを追加。
 *    以前はパーティー参加者なら誰でも呼べてしまい、非リーダーが
 *    全員をテレポート・ライフ消費させられる問題があった。
 *    /quest party <id> と同様の制約に揃えている。
 */
object QuestCommand {

    private val PREFIX get() = QuestPluginV2.PREFIX

    private val helpEntries = listOf(
        HelpMenu.Entry("/quest help",           "このヘルプを表示します",                  "questpluginv2.quest"),
        HelpMenu.Entry("/quest list [page]",    "受注可能なクエスト一覧を表示します",       "questpluginv2.quest"),
        HelpMenu.Entry("/quest info <id>",      "クエストの詳細情報を表示します",           "questpluginv2.quest"),
        HelpMenu.Entry("/quest accept <id>",    "クエストを受注します",                    "questpluginv2.quest"),
        HelpMenu.Entry("/quest abandon <id>",   "受注中のクエストを放棄します",             "questpluginv2.quest"),
        HelpMenu.Entry("/quest progress",       "受注中クエストの進捗を確認します",         "questpluginv2.quest"),
        HelpMenu.Entry("/quest party <id>",     "パーティーでクエストを受注します（リーダーのみ）", "questpluginv2.quest"),
        HelpMenu.Entry("/quest player create",                 "民間クエストの作成を開始します",          "questpluginv2.quest"),
        HelpMenu.Entry("/quest player gui",                 "民間のクエストを閲覧します",          "questpluginv2.quest"),
        HelpMenu.Entry("/quest player cancel",                 "作成中の民間クエストを破棄します",        "questpluginv2.quest"),
        HelpMenu.Entry("/quest player list",                   "募集中の民間クエスト一覧を表示します",    "questpluginv2.quest"),
        HelpMenu.Entry("/quest player accept <id>",            "民間クエストを受注します",                "questpluginv2.quest"),
        HelpMenu.Entry("/quest player submit <id>",            "民間クエストへ納品します",                "questpluginv2.quest"),
        HelpMenu.Entry("/quest player delete <id>",            "自分が作成した民間クエストを削除します",  "questpluginv2.quest"),
        HelpMenu.Entry("/quest tutorial", "チュートリアルを表示します", "questpluginv2.quest"),
        HelpMenu.Entry("/quest start <id>",     "クエストを開始します（V1互換・リーダーのみ）", "questpluginv2.quest"),
        HelpMenu.Entry("/quest leave",          "進行中のクエストを中断します",             "questpluginv2.quest"),
    )

    fun buildArg(plugin: QuestPluginV2): OraCommandLiteral {
        val qm  = plugin.questManager
        val aqm = plugin.activeQuestManager

        val root = OraCommandLiteral("quest")
        root.setRequirement { it.hasPermission("questpluginv2.quest") }

        // ---- help ----
        root.literal("help").setPlayerExecutor { data ->
            HelpMenu.send(data.sender, "Quest", helpEntries)
        }

        // ---- list ----
        root.literal("list").setPlayerExecutor { data ->
            sendPage(data.sender, qm.allQuests.toList(), 1)
        }

        root.literal("list")
            .argument("page", IntArg(1, 999))
            .setPlayerExecutor { data ->
                sendPage(data.sender, qm.allQuests.toList(), data.getArgument("page", Int::class.java))
            }

        // ---- info <id> ----
        root.literal("info")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                val q = qm.getQuest(id)
                if (q == null) {
                    data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません"); return@setPlayerExecutor
                }
                val s = data.sender
                s.sendMessage("§e§l--- ${q.name} ---")
                s.sendMessage("§7§lID: §f§l${q.id}")
                s.sendMessage("§7§l説明: §f§l${q.description}")
                s.sendMessage("§7§lタイプ: §f§l${q.type.name}")
                s.sendMessage("§7§l目標: §f§l${q.objectiveDescription}")
                q.prerequisiteQuestId?.let { s.sendMessage("§7§l前提クエスト: §f§l$it") }
                if (q.cooldownSeconds > 0) s.sendMessage("§7§lクールダウン: §f§l${formatSeconds(q.cooldownSeconds)}")
                q.timeLimitSeconds?.let { s.sendMessage("§7§l制限時間: §f§l${formatSeconds(it)}") }
                q.maxLives?.let { s.sendMessage("§7§lライフ数: §f§l$it") }
                s.sendMessage("§7§lパーティー対応: §f${if (q.isPartyEnabled) "§a§l有り" else "§c§lなし"}")
                q.rewards.forEach { r -> s.sendMessage("  §a§l- ${r.serialize()}") }
            }

        // ---- accept <id> ----
        root.literal("accept")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                when (qm.acceptQuest(data.sender, id)) {
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.SUCCESS ->
                        data.sender.sendMessage("$PREFIX§a§l${qm.getQuest(id)?.name}を受注しました")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.QUEST_NOT_FOUND ->
                        data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.ALREADY_ACTIVE ->
                        data.sender.sendMessage("$PREFIX§c§lそのクエストはすでに受注中です")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.PREREQUISITE_NOT_MET ->
                        data.sender.sendMessage("$PREFIX§c§l前提クエストを先にクリアしてください")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.MAX_ACTIVE_REACHED ->
                        data.sender.sendMessage("$PREFIX§c§l同時受注数の上限に達しています")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.ON_COOLDOWN ->
                        data.sender.sendMessage("$PREFIX§c§lこのクエストはクールダウン中です")
                    com.woxloi.questpluginv2.manager.QuestManager.AcceptResult.PARTY_DISABLED ->
                        data.sender.sendMessage("$PREFIX§c§lこのクエストはパーティーで受注できません")
                }
            }

        // ---- abandon <id> ----
        root.literal("abandon")
            .argument("id", StringArg.word())
            .suggest { d: OraCommandData ->
                val player = d.sender as? org.bukkit.entity.Player ?: return@suggest emptyList()
                qm.getPlayerProgress(player.uniqueId)
                    .entries.filter { it.value.isActive }.map { ToolTip(it.key) }
            }
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                val prog = qm.getProgress(data.sender.uniqueId, id)
                if (prog == null || !prog.isActive) {
                    data.sender.sendMessage("$PREFIX§c§lそのクエストは受注中ではありません"); return@setPlayerExecutor
                }
                qm.abandonQuest(data.sender, id)
                data.sender.sendMessage("$PREFIX§c§l${id}を放棄しました")
            }

        // ---- progress ----
        root.literal("progress").setPlayerExecutor { data ->
            val active = qm.getPlayerProgress(data.sender.uniqueId).values.filter { it.isActive }
            if (active.isEmpty()) {
                data.sender.sendMessage("$PREFIX§c§l受注中のクエストはありません"); return@setPlayerExecutor
            }
            data.sender.sendMessage("§e§l--- 受注中クエスト ---")
            active.forEach { p ->
                val q = qm.getQuest(p.questId) ?: return@forEach
                val pct = (p.currentAmount / q.requiredAmount.toDouble() * 100).toInt()
                data.sender.sendMessage("§6§l${q.name} §7§l- §a§l${p.currentAmount}§7§l/§a§l${q.requiredAmount} §7§l(${pct}%)")
            }
        }

        // ---- party <id> ----
        root.literal("party")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.filter { it.isPartyEnabled }.map { ToolTip(it.id, it.name) } }
            .setPlayerExecutor { data ->
                val party = plugin.partyManager.getPlayerParty(data.sender.uniqueId)
                if (party == null) {
                    data.sender.sendMessage("$PREFIX§c§lパーティーに入っていません"); return@setPlayerExecutor
                }
                if (!party.isLeader(data.sender.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lリーダーのみ受注できます"); return@setPlayerExecutor
                }
                qm.acceptQuestAsParty(party, data.getArgument("id", String::class.java), data.sender)
            }

        val pqm = plugin.playerQuestManager
        val playerLiteral = root.literal("player")

        playerLiteral.literal("create").setPlayerExecutor { data ->
            com.woxloi.questpluginv2.gui.PlayerQuestBoardGUI(plugin, data.sender).open(data.sender)
        }

        playerLiteral.literal("gui").setPlayerExecutor { data ->
            com.woxloi.questpluginv2.gui.PlayerQuestBoardGUI(plugin, data.sender).open(data.sender)
        }

        playerLiteral.literal("cancel").setPlayerExecutor { data ->
            pqm.clearDraft(data.sender.uniqueId)
            data.sender.sendMessage("$PREFIX§e§l作成をキャンセルしました")
        }

        playerLiteral.literal("list").setPlayerExecutor { data ->
            val open = pqm.openQuests
            if (open.isEmpty()) {
                data.sender.sendMessage("$PREFIX§7§l現在募集中の民間クエストはありません"); return@setPlayerExecutor
            }
            data.sender.sendMessage("§e§l--- 民間クエスト募集中 (${open.size}件) ---")
            open.forEach { q ->
                data.sender.sendMessage("§6§l[${q.id}] §f§l${q.targetMaterial.name} x${q.perPersonAmount}/人 "
                        + "§7§l(${q.acceptedCount}/${q.maxAcceptors}人) §a§l報酬:${q.rewardMoney}円 §7§l依頼主:${q.creatorName}")
            }
        }

        playerLiteral.literal("accept")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                when (pqm.accept(data.sender, id)) {
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.SUCCESS -> {
                        val q = pqm.getQuest(id)!!
                        data.sender.sendMessage("$PREFIX§a§l受注しました！ ${q.targetMaterial.name} を ${q.perPersonAmount}個集めてください")
                        data.sender.sendMessage("$PREFIX§7§l集まったら /quest player submit $id")
                    }
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.NOT_FOUND ->
                        data.sender.sendMessage("$PREFIX§c§lそのクエストは存在しません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.CLOSED ->
                        data.sender.sendMessage("$PREFIX§c§l募集は締め切られています")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.ALREADY_ACCEPTED ->
                        data.sender.sendMessage("$PREFIX§c§l既に受注しています")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.FULL ->
                        data.sender.sendMessage("$PREFIX§c§l募集人数に達しています")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.OWN_QUEST ->
                        data.sender.sendMessage("$PREFIX§c§l自分のクエストは受注できません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.AcceptResult.DB_ERROR ->
                        data.sender.sendMessage("$PREFIX§c§l保存中にエラーが発生しました")
                }
            }

        playerLiteral.literal("submit")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                when (pqm.submit(data.sender, id)) {
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.SUCCESS ->
                        data.sender.sendMessage("$PREFIX§a§l納品完了！報酬を受け取りました")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.NOT_FOUND ->
                        data.sender.sendMessage("$PREFIX§c§lそのクエストは存在しません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.NOT_ACCEPTOR ->
                        data.sender.sendMessage("$PREFIX§c§lこのクエストを受注していません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.ALREADY_COMPLETED ->
                        data.sender.sendMessage("$PREFIX§c§l既に納品済みです")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.NOT_ENOUGH_ITEMS -> {
                        val (have, need) = pqm.getLastSubmitCheck(data.sender.uniqueId)
                        data.sender.sendMessage("$PREFIX§c§lアイテムが足りません（所持: ${have} / 必要: ${need}）")
                    }
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.CREATOR_INVENTORY_FULL ->
                        data.sender.sendMessage("$PREFIX§c§l依頼主のインベントリに空きがありません。後でもう一度お試しください")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.VAULT_DISABLED ->
                        data.sender.sendMessage("$PREFIX§c§lVaultが有効になっていません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.SubmitResult.DB_ERROR ->
                        data.sender.sendMessage("$PREFIX§c§l保存中にエラーが発生しました")
                }
            }

        playerLiteral.literal("delete")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                when (pqm.delete(data.sender, id)) {
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.DeleteResult.SUCCESS ->
                        data.sender.sendMessage("$PREFIX§a§l削除しました（未使用分の報酬金は返金されます）")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.DeleteResult.NOT_FOUND ->
                        data.sender.sendMessage("$PREFIX§c§lそのクエストは存在しません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.DeleteResult.NOT_OWNER ->
                        data.sender.sendMessage("$PREFIX§c§l削除権限がありません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.DeleteResult.HAS_ACTIVE_ACCEPTORS ->
                        data.sender.sendMessage("$PREFIX§c§l未完了の受注者がいるため削除できません")
                    com.woxloi.questpluginv2.manager.PlayerQuestManager.DeleteResult.DB_ERROR ->
                        data.sender.sendMessage("$PREFIX§c§l削除中にエラーが発生しました")
                }
            }

        root.literal("tutorial").setPlayerExecutor { data ->
            plugin.tutorialManager.markSeen(data.sender)
            plugin.tutorialManager.show(data.sender)
        }

        // ---- start <id> (V1互換) ----
        root.literal("start")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                val q = qm.getQuest(id)
                if (q == null) {
                    data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません"); return@setPlayerExecutor
                }

                val party = plugin.partyManager.getPlayerParty(data.sender.uniqueId)
                // パーティーに入っていて、かつそのクエストがパーティー対応の場合のみ
                // リーダー権限チェックを行う。ソロ開始やパーティー非対応クエストでは
                // 誰でも自分の分は開始できる。
                if (party != null && q.isPartyEnabled && !party.isLeader(data.sender.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lパーティーでの開始はリーダーのみ可能です")
                    return@setPlayerExecutor
                }

                val ok = aqm.startQuest(data.sender, q, party)
                if (!ok) data.sender.sendMessage("$PREFIX§c§lクエストを開始できませんでした")
            }

        // ---- leave ----
        root.literal("leave").setPlayerExecutor { data ->
            if (!aqm.isQuesting(data.sender.uniqueId)) {
                data.sender.sendMessage("$PREFIX§c§l現在進行中のクエストはありません"); return@setPlayerExecutor
            }
            aqm.cancelQuest(data.sender)
            data.sender.sendMessage("$PREFIX§a§lクエストを中断しました")
        }

        return root
    }

    // ---- helpers ----

    private fun sendPage(sender: org.bukkit.entity.Player, quests: List<Quest>, page: Int) {
        val perPage = 8
        val total = Math.ceil(quests.size / perPage.toDouble()).toInt().coerceAtLeast(1)
        val start = (page - 1) * perPage
        if (start >= quests.size && quests.isNotEmpty()) {
            sender.sendMessage("${QuestPluginV2.PREFIX}§c§lそのページは存在しません"); return
        }
        sender.sendMessage("§e§l--- クエスト一覧 $page/$total ---")
        quests.subList(start, minOf(start + perPage, quests.size)).forEach { q ->
            sender.sendMessage("§6§l[${q.id}] §f§l${q.name} §7§l(${q.type.name})")
        }
    }

    private fun formatSeconds(sec: Long): String = when {
        sec < 60   -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分"
        else       -> "${sec / 3600}時間"
    }
}