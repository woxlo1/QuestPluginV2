package com.woxloi.questpluginv2.command

import com.woxloi.questpluginv2.QuestPluginV2
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import oraserver.orapluginapi.commandapi.OraCommandData
import oraserver.orapluginapi.commandapi.ToolTip
import oraserver.orapluginapi.commandapi.OraCommandLiteral
import oraserver.orapluginapi.commandapi.argumenttype.StringArg
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * /party コマンド (Kotlin 版)
 *
 * サブコマンド:
 *   help
 *   create
 *   invite <player>
 *   accept
 *   leave
 *   kick <player>
 *   disband
 *   info
 *   transfer <player>
 *   chat <message>
 *
 * 修正点:
 *  - ヘルプ表示を HelpMenu に委譲（QuestCommand/QuestOpCommandとの重複解消）。
 *  - 招待・参加メッセージで使っていた Adventure API（net.kyori.adventure...）を
 *    Bungee API（net.md_5.bungee...）に統一した。
 *    以前はヘルプにBungee、招待/参加メッセージにAdventureが混在しており、
 *    依存・見通しの両面で良くなかった。このファイルでは Bungee の
 *    ClickEvent.Action.SUGGEST_COMMAND / COPY_TO_CLIPBOARD で揃えている。
 */
object QuestPartyCommand {

    private val PREFIX get() = QuestPluginV2.PREFIX

    private val helpEntries = listOf(
        HelpMenu.Entry("/party help",             "このヘルプを表示します",                      "questpluginv2.party"),
        HelpMenu.Entry("/party create",           "新しいパーティーを作成します",                 "questpluginv2.party"),
        HelpMenu.Entry("/party invite <player>",  "プレイヤーをパーティーに招待します",           "questpluginv2.party"),
        HelpMenu.Entry("/party accept",           "パーティーへの招待を承認します",               "questpluginv2.party"),
        HelpMenu.Entry("/party leave",            "現在のパーティーを退出します",                 "questpluginv2.party"),
        HelpMenu.Entry("/party kick <player>",    "メンバーをパーティーからキックします（リーダーのみ）", "questpluginv2.party"),
        HelpMenu.Entry("/party disband",          "パーティーを解散します（リーダーのみ）",        "questpluginv2.party"),
        HelpMenu.Entry("/party info",             "パーティーの情報を表示します",                 "questpluginv2.party"),
        HelpMenu.Entry("/party transfer <player>","リーダー権を移譲します（リーダーのみ）",        "questpluginv2.party"),
        HelpMenu.Entry("/party chat <message>",   "パーティーチャットにメッセージを送ります",      "questpluginv2.party"),
    )

    fun buildArg(plugin: QuestPluginV2): OraCommandLiteral {
        val pm = plugin.partyManager

        val root = OraCommandLiteral("party")
        root.setRequirement { it.hasPermission("questpluginv2.party") }

        // ---- help ----
        root.literal("help").setPlayerExecutor { data ->
            HelpMenu.send(data.sender, "Party", helpEntries)
        }

        // ---- create ----
        root.literal("create").setPlayerExecutor { data ->
            if (pm.isInParty(data.sender.uniqueId)) {
                data.sender.sendMessage("$PREFIX§c§lすでにパーティーに入っています")
                return@setPlayerExecutor
            }
            val party = pm.createParty(data.sender)
            if (party == null) {
                data.sender.sendMessage("$PREFIX§c§l作成に失敗しました")
                return@setPlayerExecutor
            }
            data.sender.sendMessage("$PREFIX§a§lパーティーを作成しました")
            plugin.logger.info("${data.sender.name}がパーティーを作成しました ID: ${party.partyId}")
        }

        // ---- invite <player> ----
        root.literal("invite")
            .argument("target", StringArg.word())
            .suggest { _ ->
                Bukkit.getOnlinePlayers().map { ToolTip(it.name) }
            }
            .setPlayerExecutor { data ->
                val party = pm.getPlayerParty(data.sender.uniqueId)
                if (party == null) {
                    data.sender.sendMessage("$PREFIX§c§lパーティーに入っていません"); return@setPlayerExecutor
                }
                if (!party.isLeader(data.sender.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lリーダーのみ招待できます"); return@setPlayerExecutor
                }
                val name = data.getArgument("target", String::class.java)
                val target = Bukkit.getPlayerExact(name)
                if (target == null) {
                    data.sender.sendMessage("$PREFIX§c§l${name}がオンラインではありません"); return@setPlayerExecutor
                }
                if (pm.isInParty(target.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§l${name}はすでにパーティーに入っています"); return@setPlayerExecutor
                }
                if (party.isFull) {
                    data.sender.sendMessage("$PREFIX§c§lパーティーが満員です"); return@setPlayerExecutor
                }
                pm.invitePlayer(party, target)
                val timeout = plugin.config.getLong("party.invite-timeout-seconds", 60)
                data.sender.sendMessage("$PREFIX§a${name}を招待しました")
                target.sendMessage("$PREFIX§e§l${data.sender.name}§7§lからパーティー招待が届きました")
                target.spigot().sendMessage(*joinSuggestComponent())

                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (party.hasInvite(target.uniqueId)) {
                        party.removeInvite(target.uniqueId)
                        target.sendMessage("$PREFIX§7§l招待の有効期限が切れました")
                        data.sender.sendMessage("$PREFIX§7§l${name}への招待が期限切れになりました")
                    }
                }, timeout * 20L)
            }

        // ---- accept ----
        root.literal("accept").setPlayerExecutor { data ->
            val found = pm.findPendingInvite(data.sender.uniqueId)
            if (found == null) {
                data.sender.sendMessage("$PREFIX§c§l招待が届いていません"); return@setPlayerExecutor
            }
            val ok = pm.acceptInvite(data.sender, found.partyId)
            if (!ok) {
                data.sender.sendMessage("$PREFIX§c§l参加できませんでした"); return@setPlayerExecutor
            }
            data.sender.sendMessage("$PREFIX§a§lパーティーに参加しました")
            pm.broadcastToParty(found, "$PREFIX§e§l${data.sender.name}§7§lが参加しました")
        }

        // ---- leave ----
        root.literal("leave").setPlayerExecutor { data ->
            if (!pm.isInParty(data.sender.uniqueId)) {
                data.sender.sendMessage("$PREFIX§c§lパーティーに入っていません"); return@setPlayerExecutor
            }
            val party = pm.getPlayerParty(data.sender.uniqueId)!!
            pm.broadcastToParty(party, "$PREFIX§e§l${data.sender.name}§7§lが退出しました")
            pm.leaveParty(data.sender)
            data.sender.sendMessage("$PREFIX§7§lパーティーを退出しました")
        }

        // ---- kick <player> ----
        root.literal("kick")
            .argument("target", StringArg.word())
            .suggest { d ->
                val p = d.sender as? Player ?: return@suggest emptyList()
                val party = pm.getPlayerParty(p.uniqueId) ?: return@suggest emptyList()
                party.memberUUIDs
                    .filter { it != p.uniqueId }
                    .mapNotNull { Bukkit.getOfflinePlayer(it).name }
                    .map { ToolTip(it) }
            }
            .setPlayerExecutor { data ->
                val party = pm.getPlayerParty(data.sender.uniqueId)
                if (party == null || !party.isLeader(data.sender.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lキックはリーダーのみ可能です"); return@setPlayerExecutor
                }
                val name = data.getArgument("target", String::class.java)
                val target = Bukkit.getPlayerExact(name)
                if (target == null || !party.isMember(target.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lそのプレイヤーはパーティーに入っていません"); return@setPlayerExecutor
                }
                if (target.uniqueId == data.sender.uniqueId) {
                    data.sender.sendMessage("$PREFIX§c§l自分をキックすることはできません"); return@setPlayerExecutor
                }
                pm.kickMember(party, target.uniqueId)
                target.sendMessage("$PREFIX§c§lパーティーからキックされました")
                pm.broadcastToParty(party, "$PREFIX§e§l${name}§7§lがキックされました")
            }

        // ---- disband ----
        root.literal("disband").setPlayerExecutor { data ->
            val party = pm.getPlayerParty(data.sender.uniqueId)
            if (party == null || !party.isLeader(data.sender.uniqueId)) {
                data.sender.sendMessage("$PREFIX§c§l解散はリーダーのみ可能です"); return@setPlayerExecutor
            }
            pm.dissolveParty(party.partyId)
        }

        // ---- info ----
        root.literal("info").setPlayerExecutor { data ->
            val party = pm.getPlayerParty(data.sender.uniqueId)
            if (party == null) {
                data.sender.sendMessage("$PREFIX§c§lパーティーに入っていません"); return@setPlayerExecutor
            }
            data.sender.sendMessage("§e§l--- パーティー情報 ---")
            data.sender.sendMessage("§7§lID: §f§l${party.partyId}")
            data.sender.sendMessage("§7§l人数: §f§l${party.size} / ${party.maxSize}")
            data.sender.sendMessage("§7§lメンバー:")
            party.members.forEach { (uuid, role) ->
                val pname = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                val online = if (Bukkit.getPlayer(uuid) != null) "§a§l[オンライン]" else "§8§l[オフライン]"
                data.sender.sendMessage("  ${role.displayName} §f§l$pname $online")
            }
        }

        // ---- transfer <player> ----
        root.literal("transfer")
            .argument("target", StringArg.word())
            .suggest { d ->
                val p = d.sender as? Player ?: return@suggest emptyList()
                val party = pm.getPlayerParty(p.uniqueId) ?: return@suggest emptyList()
                party.memberUUIDs
                    .filter { it != p.uniqueId }
                    .mapNotNull { Bukkit.getOfflinePlayer(it).name }
                    .map { ToolTip(it) }
            }
            .setPlayerExecutor { data ->
                val party = pm.getPlayerParty(data.sender.uniqueId)
                if (party == null || !party.isLeader(data.sender.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lリーダー移譲はリーダーのみ可能です"); return@setPlayerExecutor
                }
                val name = data.getArgument("target", String::class.java)
                val target = Bukkit.getPlayerExact(name)
                if (target == null || !party.isMember(target.uniqueId)) {
                    data.sender.sendMessage("$PREFIX§c§lそのプレイヤーはパーティーに入っていません"); return@setPlayerExecutor
                }
                if (pm.transferLeader(party, target.uniqueId)) {
                    pm.broadcastToParty(party, "$PREFIX§e§lリーダーが§f§l${name}§e§lに移譲されました")
                }
            }

        // ---- chat <message> ----
        root.literal("chat")
            .argument("message", StringArg.greedyPhrase())
            .setPlayerExecutor { data ->
                val party = pm.getPlayerParty(data.sender.uniqueId)
                if (party == null) {
                    data.sender.sendMessage("$PREFIX§c§lパーティーに入っていません"); return@setPlayerExecutor
                }
                pm.sendPartyChat(party, data.sender, data.getArgument("message", String::class.java))
            }

        return root
    }

    // ---- helpers ----

    private fun joinSuggestComponent(): Array<BaseComponent> =
        ComponentBuilder("$PREFIX§a§l[ここをクリックで参加コマンド自動入力]")
            .event(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/party accept"))
            .create()
}