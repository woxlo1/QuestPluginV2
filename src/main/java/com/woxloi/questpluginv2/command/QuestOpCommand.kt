package com.woxloi.questpluginv2.command

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.manager.NpcManager
import com.woxloi.questpluginv2.model.quest.Quest
import com.woxloi.questpluginv2.model.quest.QuestType
import com.woxloi.questpluginv2.model.reward.QuestReward
import com.woxloi.questpluginv2.model.reward.RewardType
import oraserver.orapluginapi.commandapi.OraCommandData
import oraserver.orapluginapi.commandapi.OraCommandLiteral
import oraserver.orapluginapi.commandapi.ToolTip
import oraserver.orapluginapi.commandapi.argumenttype.BooleanArg
import oraserver.orapluginapi.commandapi.argumenttype.IntArg
import oraserver.orapluginapi.commandapi.argumenttype.LongArg
import oraserver.orapluginapi.commandapi.argumenttype.StringArg
import org.bukkit.Bukkit
import org.bukkit.entity.EntityType

/**
 * 修正点: ヘルプ表示を HelpMenu に委譲（QuestCommand/QuestPartyCommandとの重複解消）。
 * 修正点2: /questop npc settype <id> <EntityType> を追加。
 *   NPC作成は引き続き /questop npc create <名前> で常にVILLAGERとして作成し、
 *   村人以外のMobにしたい場合はこのコマンドで後から変更する運用にしている
 *   （create時にtype引数を増やすより、既存コマンドの互換性を保てるため）。
 */
object QuestOpCommand {

    private val PREFIX get() = QuestPluginV2.PREFIX

    private val helpEntries = listOf(
        HelpMenu.Entry("/questop help",                                      "このヘルプを表示します",                         "questpluginv2.op"),
        HelpMenu.Entry("/questop create <id> <type> <target> <amount> <name>","新しいクエストを作成します",                    "questpluginv2.op"),
        HelpMenu.Entry("/questop create gui",                                "新しいクエストをGUIで作成します",                 "questpluginv2.op"),
        HelpMenu.Entry("/questop edit <id>",                                 "クエストをGUIで編集します",                      "questpluginv2.op"),
        HelpMenu.Entry("/questop delete <id>",                               "クエストを削除します",                           "questpluginv2.op"),
        HelpMenu.Entry("/questop npc create <名前>",                          "現在地にNPCを作成します（村人）",                  "questpluginv2.op"),
        HelpMenu.Entry("/questop npc settype <id> <EntityType>",            "NPCのMob種別を変更します",                       "questpluginv2.op"),
        HelpMenu.Entry("/questop npc remove <id>",                           "NPCを削除します",                               "questpluginv2.op"),
        HelpMenu.Entry("/questop npc setquest <id> <questId>",               "NPCにクエストを設定します",                      "questpluginv2.op"),
        HelpMenu.Entry("/questop npc setgreeting <id> <文>",                 "NPCの挨拶を設定します",                         "questpluginv2.op"),
        HelpMenu.Entry("/questop npc tp <id>",                               "NPCを現在地に移動します",                        "questpluginv2.op"),
        HelpMenu.Entry("/questop npc list",                                  "NPC一覧を表示します",                            "questpluginv2.op"),
        HelpMenu.Entry("/questop reload",                                    "クエストデータをリロードします",                   "questpluginv2.op"),
        HelpMenu.Entry("/questop setdesc <id> <desc>",                       "クエストの説明を設定します",                      "questpluginv2.op"),
        HelpMenu.Entry("/questop setcooldown <id> <seconds>",                "クールダウンを設定します",                        "questpluginv2.op"),
        HelpMenu.Entry("/questop settimelimit <id> <seconds>",               "制限時間を設定します（0=無制限）",                 "questpluginv2.op"),
        HelpMenu.Entry("/questop setmaxlives <id> <lives>",                  "ライフ数を設定します（0=無制限）",                 "questpluginv2.op"),
        HelpMenu.Entry("/questop setprereq <id> <prereqId|none>",            "前提クエストを設定します",                        "questpluginv2.op"),
        HelpMenu.Entry("/questop setparty <id> <true|false>",                "パーティー受注の可否を設定します",                "questpluginv2.op"),
        HelpMenu.Entry("/questop addreward <id> <rewardData>",               "報酬を追加します",                               "questpluginv2.op"),
        HelpMenu.Entry("/questop clearrewards <id>",                         "報酬をすべて削除します",                          "questpluginv2.op"),
        HelpMenu.Entry("/questop info <id>",                                 "クエスト詳細を表示します（管理者用）",             "questpluginv2.op"),
        HelpMenu.Entry("/questop list",                                      "全クエスト一覧を表示します",                      "questpluginv2.op"),
        HelpMenu.Entry("/questop give <player> <questId>",                   "プレイヤーにクエストを付与します",                "questpluginv2.op"),
        HelpMenu.Entry("/questop complete <player> <questId>",               "プレイヤーのクエストを強制完了します",            "questpluginv2.op"),
        HelpMenu.Entry("/questop start <player> <questId>",                  "プレイヤーのクエストを強制開始します",            "questpluginv2.op"),
    )

    /** NPCに使えるEntityType（LivingEntityのサブタイプ）のサジェスト候補を生成する */
    private fun spawnableEntityTypeTooltips(): List<ToolTip> =
        EntityType.values()
            .filter { NpcManager.isSpawnableLivingType(it) }
            .map { ToolTip(it.name) }

    fun buildArg(plugin: QuestPluginV2): OraCommandLiteral {
        val qm = plugin.questManager

        val root = OraCommandLiteral("questop")
        root.setRequirement { it.hasPermission("questpluginv2.op") }

        // ---- help ----
        root.literal("help").setExecutor { data -> HelpMenu.send(data.sender, "Quest Admin", helpEntries) }

        // ---- create ----
        val createLiteral = root.literal("create")
        createLiteral
            .argument("id",     StringArg.word())
            .argument("type",   StringArg.word())
            .suggest { _: OraCommandData -> QuestType.values().map { ToolTip(it.name) } }
            .argument("target", StringArg.word())
            .argument("amount", IntArg(1, 99999))
            .argument("name",   StringArg.greedyPhrase())
            .setExecutor { data ->
                val id     = data.getArgument("id",     String::class.java)
                val type   = data.getArgument("type",   String::class.java)
                val target = data.getArgument("target", String::class.java)
                val amount = data.getArgument("amount", Int::class.java)
                val name   = data.getArgument("name",   String::class.java)
                if (qm.getQuest(id) != null) {
                    data.sender.sendMessage("$PREFIX§c§l${id}はすでに存在します"); return@setExecutor
                }
                val qt = QuestType.fromString(type)
                if (qt == null) {
                    data.sender.sendMessage("$PREFIX§c§l無効なタイプ: $type"); return@setExecutor
                }
                val q = Quest(id, name, "説明未設定", qt, if (target.equals("none", true)) null else target, amount)
                if (qm.saveQuest(q)) data.sender.sendMessage("$PREFIX§a§l${name} (ID: $id) を作成しました")
                else data.sender.sendMessage("$PREFIX§c§l作成中にエラーが発生しました")
            }

        // ---- create gui ----
        createLiteral.literal("gui").setPlayerExecutor { data ->
            com.woxloi.questpluginv2.gui.QuestCreateGUI(plugin).open(data.sender)
        }

        // ---- edit ----
        root.literal("edit")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                if (qm.getQuest(id) == null) {
                    data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません"); return@setPlayerExecutor
                }
                com.woxloi.questpluginv2.gui.QuestEditGUI(plugin, id).open(data.sender)
            }

        // ---- delete ----
        root.literal("delete")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val id = data.getArgument("id", String::class.java)
                if (qm.getQuest(id) == null) {
                    data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor
                }
                if (qm.deleteQuest(id)) data.sender.sendMessage("$PREFIX§a§l削除しました")
                else data.sender.sendMessage("$PREFIX§c§lエラーが発生しました")
            }

        // ---- npc ----
        val npcLiteral = root.literal("npc")

        npcLiteral.literal("create")
            .argument("name", StringArg.greedyPhrase())
            .setPlayerExecutor { data ->
                val name = data.getArgument("name", String::class.java)
                val npc = plugin.npcManager.createNpc(data.sender, name)
                if (npc != null) {
                    data.sender.sendMessage("$PREFIX§a§l${name}を作成しました ID: §e§l${npc.id}")
                    data.sender.sendMessage("$PREFIX§7§l村人以外のMobにしたい場合は §f/questop npc settype ${npc.id} <種別>")
                } else {
                    data.sender.sendMessage("$PREFIX§c§l作成に失敗しました")
                }
            }

        // ---- npc settype <id> <EntityType> ----
        npcLiteral.literal("settype")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .suggest { _: OraCommandData -> plugin.npcManager.allNpcs.map { ToolTip(it.id.toString(), it.name) } }
            .argument("type", StringArg.word())
            .suggest { _: OraCommandData -> spawnableEntityTypeTooltips() }
            .setExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                val typeStr = data.getArgument("type", String::class.java)

                if (plugin.npcManager.getNpc(id) == null) {
                    data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません"); return@setExecutor
                }

                val type = try {
                    EntityType.valueOf(typeStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    data.sender.sendMessage("$PREFIX§c§l不明なMob種別です: $typeStr"); return@setExecutor
                }

                if (!com.woxloi.questpluginv2.manager.NpcManager.isSpawnableLivingType(type)) {
                    data.sender.sendMessage("$PREFIX§c§lNPCにできない種別です: $typeStr")
                    return@setExecutor
                }

                if (plugin.npcManager.setEntityType(id, type)) {
                    data.sender.sendMessage("$PREFIX§a§l${id}のMob種別を§e§l${type.name}§a§lに変更しました")
                } else {
                    data.sender.sendMessage("$PREFIX§c§l変更に失敗しました")
                }
            }

        npcLiteral.literal("remove")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .setExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                if (plugin.npcManager.removeNpc(id)) data.sender.sendMessage("$PREFIX§a§l${id}を削除しました")
                else data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません")
            }

        npcLiteral.literal("setquest")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .argument("questId", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                val questId = data.getArgument("questId", String::class.java)
                val quest = qm.getQuest(questId)
                if (quest == null) {
                    data.sender.sendMessage("$PREFIX§c§l${questId}は存在しません")
                    return@setExecutor Unit
                }
                val success = plugin.npcManager.setQuest(id, questId)
                if (success) {
                    data.sender.sendMessage("$PREFIX§a§l${id}に${questId}を設定しました")
                } else {
                    data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません")
                }
            }

        npcLiteral.literal("setgreeting")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .argument("text", StringArg.greedyPhrase())
            .setExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                val text = data.getArgument("text", String::class.java)
                if (plugin.npcManager.setGreeting(id, text)) data.sender.sendMessage("$PREFIX§a§l挨拶を設定しました")
                else data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません")
            }

        npcLiteral.literal("tp")
            .argument("id", IntArg(1, Int.MAX_VALUE))
            .setPlayerExecutor { data ->
                val id = data.getArgument("id", Int::class.java)
                if (plugin.npcManager.teleportNpcHere(id, data.sender)) data.sender.sendMessage("$PREFIX§a§l${id}を現在地に移動しました")
                else data.sender.sendMessage("$PREFIX§c§l${id}が見つかりません")
            }

        npcLiteral.literal("list").setExecutor { data ->
            val npcs = plugin.npcManager.allNpcs
            data.sender.sendMessage("§e§l--- NPC一覧 (${npcs.size}件) ---")
            npcs.forEach { n ->
                data.sender.sendMessage("§6§l[${n.id}] §f§l${n.name} §7§l(§d§l${n.entityType.name}§7§l/${n.world}) クエスト: §a§l${n.questId ?: "なし"}")
            }
        }
        // ---- reload ----
        root.literal("reload").setExecutor { data ->
            plugin.reloadConfig(); qm.loadAllQuests()
            data.sender.sendMessage("$PREFIX§a§lリロードしました")
        }

        // ---- setdesc ----
        root.literal("setdesc")
            .argument("id",   StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("desc", StringArg.greedyPhrase())
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                q.description = data.getArgument("desc", String::class.java)
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§l説明を更新しました")
            }

        // ---- setcooldown ----
        root.literal("setcooldown")
            .argument("id",      StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("seconds", LongArg(0, Long.MAX_VALUE))
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                q.cooldownSeconds = data.getArgument("seconds", Long::class.java)
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§lクールダウンを設定しました")
            }

        // ---- settimelimit ----
        root.literal("settimelimit")
            .argument("id",      StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("seconds", LongArg(0, Long.MAX_VALUE))
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val s = data.getArgument("seconds", Long::class.java)
                q.timeLimitSeconds = if (s == 0L) null else s
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§l制限時間を設定しました（0=無制限）")
            }

        // ---- setmaxlives ----
        root.literal("setmaxlives")
            .argument("id",    StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("lives", IntArg(0, 999))
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val v = data.getArgument("lives", Int::class.java)
                q.maxLives = if (v == 0) null else v
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§lライフ数を設定しました（0=無制限）")
            }

        // ---- setprereq ----
        root.literal("setprereq")
            .argument("id",     StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("prereq", StringArg.word())
            .suggest { _: OraCommandData ->
                val list = qm.allQuests.map { ToolTip(it.id, it.name) }.toMutableList()
                list.add(0, ToolTip("none", "前提なし"))
                list
            }
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val prereq = data.getArgument("prereq", String::class.java)
                q.prerequisiteQuestId = if (prereq.equals("none", true)) null else prereq
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§l前提クエストを設定しました")
            }

        // ---- setparty ----
        root.literal("setparty")
            .argument("id",    StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("value", BooleanArg())
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                q.isPartyEnabled = data.getArgument("value", Boolean::class.java)
                qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§lパーティー対応を設定しました")
            }

        // ---- addreward ----
        root.literal("addreward")
            .argument("id",         StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .argument("rewardData", StringArg.greedyPhrase())
            .suggest { _: OraCommandData -> RewardType.values().map { ToolTip("${it.name}:", it.name) } }
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val raw = data.getArgument("rewardData", String::class.java)
                val reward = QuestReward.deserialize(raw)
                if (reward == null) {
                    data.sender.sendMessage("$PREFIX§c§l不正な報酬データ: $raw"); return@setExecutor
                }
                q.addReward(reward); qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§l報酬を追加しました")
            }

        // ---- clearrewards ----
        root.literal("clearrewards")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                q.rewards.clear(); qm.saveQuest(q)
                data.sender.sendMessage("$PREFIX§a§l報酬をすべて削除しました")
            }

        // ---- info ----
        root.literal("info")
            .argument("id", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val q = qm.getQuest(data.getArgument("id", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val s = data.sender
                s.sendMessage("§e§l--- [Op] ${q.name} ---")
                s.sendMessage("§7§lID: §f§l${q.id}")
                s.sendMessage("§7§l説明: §f§l${q.description}")
                s.sendMessage("§7§lタイプ: §f§l${q.type.name}")
                s.sendMessage("§7§lターゲット: §f§l${q.targetId}")
                s.sendMessage("§7§l必要数: §f§l${q.requiredAmount}")
                s.sendMessage("§7§l前提: §f§l${q.prerequisiteQuestId ?: "なし"}")
                s.sendMessage("§7§lクールダウン: §f§l${q.cooldownSeconds}秒")
                s.sendMessage("§7§l制限時間: §f§l${q.timeLimitSeconds?.let { "${it}秒" } ?: "なし"}")
                s.sendMessage("§7§lライフ数: §f§l${q.maxLives ?: "無制限"}")
                s.sendMessage("§7§lパーティー対応: §f§l${q.isPartyEnabled}")
                s.sendMessage("§7§l報酬 (${q.rewards.size}件):")
                q.rewards.forEach { r -> s.sendMessage("  §a§l- ${r.serialize()}") }
            }

        // ---- list ----
        root.literal("list").setExecutor { data ->
            val quests = qm.allQuests
            data.sender.sendMessage("§e§l--- クエスト一覧 (${quests.size}件) ---")
            quests.forEach { q -> data.sender.sendMessage("§6§l[${q.id}] §f§l${q.name} §7§l(${q.type.name})") }
        }

        // ---- give ----
        root.literal("give")
            .argument("player",  StringArg.word())
            .suggest { _: OraCommandData -> Bukkit.getOnlinePlayers().map { ToolTip(it.name) } }
            .argument("questId", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val target = Bukkit.getPlayerExact(data.getArgument("player", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§lオンラインではありません"); return@setExecutor }
                val result = qm.acceptQuest(target, data.getArgument("questId", String::class.java))
                data.sender.sendMessage("$PREFIX§a§l結果: ${result.name}")
            }

        // ---- complete ----
        root.literal("complete")
            .argument("player",  StringArg.word())
            .suggest { _: OraCommandData -> Bukkit.getOnlinePlayers().map { ToolTip(it.name) } }
            .argument("questId", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val target = Bukkit.getPlayerExact(data.getArgument("player", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§lオンラインではありません"); return@setExecutor }
                val q = qm.getQuest(data.getArgument("questId", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                qm.addProgress(target.uniqueId, q.type, q.targetId, q.requiredAmount)
                data.sender.sendMessage("$PREFIX§a§l${target.name}の${q.name}を強制完了しました")
            }

        // ---- start ----
        root.literal("start")
            .argument("player",  StringArg.word())
            .suggest { _: OraCommandData -> Bukkit.getOnlinePlayers().map { ToolTip(it.name) } }
            .argument("questId", StringArg.word())
            .suggest { _: OraCommandData -> qm.allQuests.map { ToolTip(it.id, it.name) } }
            .setExecutor { data ->
                val target = Bukkit.getPlayerExact(data.getArgument("player", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§lオンラインではありません"); return@setExecutor }
                val q = qm.getQuest(data.getArgument("questId", String::class.java))
                    ?: run { data.sender.sendMessage("$PREFIX§c§l見つかりません"); return@setExecutor }
                val ok = plugin.activeQuestManager.startQuest(target, q, null)
                data.sender.sendMessage(if (ok) "$PREFIX§a§l開始しました" else "$PREFIX§c§l開始できませんでした")
            }

        return root
    }
}