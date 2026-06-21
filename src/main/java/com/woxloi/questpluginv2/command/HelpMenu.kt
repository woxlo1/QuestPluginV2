package com.woxloi.questpluginv2.command

import com.woxloi.questpluginv2.QuestPluginV2
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * /quest, /questop, /party のヘルプ表示を共通化するユーティリティ。
 *
 * 以前は QuestCommand / QuestOpCommand / QuestPartyCommand の3ファイルに
 * ほぼ同一のヘルプ表示コードが重複していたため、ここに集約した。
 *
 * 見た目は他プラグインのヘルプ（多色・記号・絵文字を使った装飾）と被りにくいよう、
 * 装飾記号や絵文字を使わず、左揃えのプレーンな一覧形式にしている。
 * コマンド名をクリックするとそのまま入力欄に補完されるようにし、
 * 説明文と権限はホバーで確認できる。
 */
object HelpMenu {

    data class Entry(
        val usage: String,
        val description: String,
        val permission: String
    )

    /**
     * ヘルプ全体を送る。
     *
     * @param sender    送信先
     * @param menuTitle 例: "Quest", "Quest Admin", "Party"
     * @param entries   表示するコマンド一覧
     */
    fun send(sender: CommandSender, menuTitle: String, entries: List<Entry>) {
        val visible = entries.filter { sender.hasPermission(it.permission) }

        sender.sendMessage("§a§l" + "-".repeat(32))

        if (sender is Player) {
            visible.forEach { entry ->
                sender.spigot().sendMessage(*buildLine(entry))
            }
        } else {
            // コンソール等、ホバー表示ができない相手にはプレーンテキストで
            visible.forEach { entry ->
                sender.sendMessage("§e§l${entry.usage}  §d§l- ${entry.description}")
            }
        }

        sender.sendMessage("§a§l" + "-".repeat(32))
        if (sender is Player) {
            sender.sendMessage("§f§lコマンドをクリックすると入力欄に補完されます")
        }
    }

    private fun buildLine(entry: Entry): Array<BaseComponent> {
        val hover = "§d${entry.description}\n§aPermission: §4${entry.permission}"
        // 末尾に半角スペースを付けておくことで、補完後すぐ引数を打てるようにする
        val suggest = entry.usage.substringBefore(" [").substringBefore(" <") + " "
        return ComponentBuilder()
            .append("§e§l${entry.usage}")
            .event(HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(hover)))
            .event(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest))
            .create()
    }
}