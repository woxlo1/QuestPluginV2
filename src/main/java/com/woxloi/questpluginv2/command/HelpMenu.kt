package com.woxloi.questpluginv2.command

import com.woxloi.questpluginv2.QuestPluginV2
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
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
 *
 * 修正点: ホバー表示・クリックで補完入力する機能を廃止し、
 * コマンド文字列をクリップボードへコピーするだけの単純な形式にした。
 * 補完入力だと環境によってチャット欄の状態が意図せず変わってしまう
 * ことがあり、コピーのみの方が安全で分かりやすいため。
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
            // コンソール等、クリップボード操作ができない相手にはプレーンテキストで
            visible.forEach { entry ->
                sender.sendMessage("§e§l${entry.usage}  §d§l- ${entry.description}")
            }
        }

        sender.sendMessage("§a§l" + "-".repeat(32))
        if (sender is Player) {
            sender.sendMessage("§f§lコマンドをクリックするとことでコピーできます")
        }
    }

    private fun buildLine(entry: Entry): Array<BaseComponent> {
        // usage部分・説明文はクリックイベント無しのプレーンテキストとして表示し、
        // 末尾の "[コピー]" にだけ COPY_TO_CLIPBOARD を付与する。
        return ComponentBuilder("§e§l${entry.usage} §7§l- §d§l${entry.description} ")
            .append("§b§l[ここをクリックでコピー]")
            .event(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, entry.usage))
            .create()
    }
}