package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import com.woxloi.questpluginv2.model.quest.Quest
import com.woxloi.questpluginv2.model.quest.QuestType
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import oraserver.orapluginapi.text.OraInput
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * クエスト作成GUIウィザード
 *
 * Page 1: タイプ選択（6×9グリッド）
 * Page 2: ターゲット入力（チャット）
 * Page 3: 必要数入力（チャット）
 * Page 4: 名前入力（チャット）
 * → 完了後クエストを保存
 */
class QuestCreateGUI(private val questplugin: QuestPluginV2) : OraInventory(
    plugin = questplugin,
    title  = "§6§lクエスト作成 — タイプ選択",
    rows   = 6
) {

    // ---- 状態 ----
    private var selectedType: QuestType? = null

    // ---- 背景・装飾アイテム ----
    private val glass = OraInventoryItem(Material.LIME_STAINED_GLASS_PANE)
        .setDisplayName("§r")
        .setCanClick(false)

    private val borderGlass = OraInventoryItem(Material.BLUE_STAINED_GLASS_PANE)
        .setDisplayName("§r")
        .setCanClick(false)

    // ---- 各タイプのマテリアル定義 ----
    private val typeEntries: List<Triple<QuestType, Material, String>> = listOf(
        Triple(QuestType.KILL_MOB,        Material.ZOMBIE_HEAD,        "§c§lMob討伐\n§7指定したMobを討伐する"),
        Triple(QuestType.KILL_PLAYER,     Material.PLAYER_HEAD,        "§4§lPvP討伐\n§7プレイヤーを討伐する"),
        Triple(QuestType.BREAK_BLOCK,     Material.IRON_PICKAXE,       "§a§lブロック破壊\n§7指定ブロックを破壊する"),
        Triple(QuestType.BREAK_BLOCK_TYPE,Material.DIAMOND_PICKAXE,    "§b§lブロック種別破壊\n§7種別でブロックを破壊する"),
        Triple(QuestType.PLACE_BLOCK,     Material.OAK_LOG,            "§2§lブロック設置\n§7ブロックを設置する"),
        Triple(QuestType.COLLECT_ITEM,    Material.CHEST,              "§e§lアイテム収集\n§7アイテムを集める"),
        Triple(QuestType.WALK_DISTANCE,   Material.LEATHER_BOOTS,      "§f§l歩行距離\n§7指定距離を歩く"),
        Triple(QuestType.SWIM_DISTANCE,   Material.WATER_BUCKET,       "§9§l水泳距離\n§7指定距離を泳ぐ"),
        Triple(QuestType.CRAFT_ITEM,      Material.CRAFTING_TABLE,     "§6§lクラフト\n§7アイテムをクラフトする"),
        Triple(QuestType.FISHING,         Material.FISHING_ROD,        "§3§l釣り\n§7釣りで魚を釣る"),
        Triple(QuestType.FARMING,         Material.WHEAT,              "§a§l農業\n§7作物を収穫する"),
        Triple(QuestType.ENCHANT,         Material.ENCHANTING_TABLE,   "§5§lエンチャント\n§7アイテムにエンチャントする"),
        Triple(QuestType.TRADE,           Material.EMERALD,            "§2§l取引\n§7村人と取引する"),
        Triple(QuestType.TAME,            Material.BONE,               "§f§lテイム\n§7動物をテイムする"),
        Triple(QuestType.TAKE_DAMAGE,     Material.SHIELD,             "§c§lダメージ耐性\n§7指定ダメージを受ける"),
        Triple(QuestType.LEVEL_UP,        Material.EXPERIENCE_BOTTLE,  "§a§lレベルアップ\n§7指定レベルに達する"),
        Triple(QuestType.BOSS_KILL,       Material.WITHER_SKELETON_SKULL, "§4§lボス討伐\n§7MythicMobsのボスを討伐する"),
        Triple(QuestType.CUSTOM_EVENT,    Material.COMMAND_BLOCK,      "§d§lカスタムイベント\n§7カスタムイベントを達成する"),
    )

    // ==========================================================
    //  OraInventory — onOpen
    // ==========================================================

    override fun onOpen(player: Player): Boolean {
        // 外枠を黒ガラスで塗りつぶし
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
            .setDisplayName("§6§l【クエスト作成】")
            .setLore("§7クエストのタイプを選んでください")
            .setCanClick(false)
        )

        // タイプボタンを内側（row=1〜4, col=1〜7 の 4*7=28スロット）に配置
        val contentSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                contentSlots.add(row * 9 + col)
            }
        }

        typeEntries.forEachIndexed { index, (type, material, desc) ->
            if (index >= contentSlots.size) return@forEachIndexed
            val slot = contentSlots[index]
            val lines = desc.split("\n")
            val lore = mutableListOf<String>()
            if (lines.size > 1) lore.addAll(lines.drop(1))
            lore.add("")
            lore.add("§7クリックして選択")

            val item = OraInventoryItem(material)
                .setDisplayName(lines[0])
                .setLore(lore)
                .setCanClick(false)
                .setClickEvent { e ->
                    val clicker = e.whoClicked as? Player ?: return@setClickEvent
                    selectedType = type
                    clicker.closeInventory()
                    startInputWizard(clicker, type)
                }
            setItem(slot, item)
        }

        // キャンセルボタン (slot 49)
        setItem(49, OraInventoryItem(Material.BARRIER)
            .setDisplayName("§c§lキャンセル")
            .setLore("§7クエスト作成を中止します")
            .setCanClick(false)
            .setClickEvent { e ->
                val clicker = e.whoClicked as? Player ?: return@setClickEvent
                clicker.closeInventory()
                clicker.sendMessage("${QuestPluginV2.PREFIX}§c§lクエスト作成をキャンセルしました")
            }
        )

        return true
    }

    // ==========================================================
    //  入力ウィザード (チャット)
    // ==========================================================

    private fun startInputWizard(player: Player, type: QuestType) {
        player.sendMessage("${QuestPluginV2.PREFIX}§e§l${type.name}§a§lを選択しました")

        // ---- Step 1: ターゲットID ----
        askTarget(player, type) { target ->
            // ---- Step 2: 必要数 ----
            askAmount(player) { amount ->
                // ---- Step 3: クエスト名 ----
                askName(player) { name ->
                    // ---- Step 4: クエストID (自動生成 or 入力) ----
                    askId(player) { id ->
                        saveQuest(player, id, name, type, target, amount)
                    }
                }
            }
        }
    }

    private fun askTarget(player: Player, type: QuestType, callback: (String?) -> Unit) {
        // ターゲット不要なタイプ
        val noTargetTypes = setOf(
            QuestType.KILL_PLAYER,
            QuestType.WALK_DISTANCE,
            QuestType.SWIM_DISTANCE,
            QuestType.TAKE_DAMAGE,
            QuestType.LEVEL_UP,
            QuestType.ENCHANT,
            QuestType.TRADE
        )
        if (type in noTargetTypes) {
            callback(null)
            return
        }

        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§lターゲットIDを入力してください\n§e§lスキップ: none)")
            cancelKeyword("cancel")
            convert { it }
            onCancel {
                player.sendMessage("${QuestPluginV2.PREFIX}§c§lキャンセルしました")
            }
            onReceive { raw ->
                callback(if (raw.equals("none", ignoreCase = true)) null else raw.uppercase())
            }
        }
    }

    private fun askAmount(player: Player, callback: (Int) -> Unit) {
        OraInput.int(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§l必要数を入力してください §7(例: 10)")
            cancelKeyword("cancel")
            validate { it > 0 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l1以上の整数を入力してください") }
            onCancel { player.sendMessage("${QuestPluginV2.PREFIX}§c§lキャンセルしました") }
            onReceive { callback(it) }
        }
    }

    private fun askName(player: Player, callback: (String) -> Unit) {
        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX} §a§lクエスト名を入力してください §7§l(例: 最初のMob討伐)")
            cancelKeyword("cancel")
            convert { it.trim() }
            validate { it.isNotBlank() && it.length <= 64 }
            onInvalid { player.sendMessage("${QuestPluginV2.PREFIX}§c§l1〜64文字で入力してください") }
            onCancel { player.sendMessage("${QuestPluginV2.PREFIX}§c§lキャンセルしました") }
            onReceive { callback(it) }
        }
    }

    private fun askId(player: Player, callback: (String) -> Unit) {
        OraInput.chat<String>(player, plugin) {
            prompt("${QuestPluginV2.PREFIX}§a§lクエストIDを入力してください §7§l(英小文字・数字・_ / 例: quest_001)")
            cancelKeyword("cancel")
            convert { it.trim().lowercase() }
            validate { id ->
                id.matches(Regex("[a-z0-9_]{1,64}")) &&
                        questplugin.questManager.getQuest(id) == null
            }
            onInvalid { raw ->
                if (questplugin.questManager.getQuest(raw.trim().lowercase()) != null) {
                    player.sendMessage("${QuestPluginV2.PREFIX}§c§l${raw}はすでに存在するIDです")
                } else {
                    player.sendMessage("${QuestPluginV2.PREFIX}§c§l英小文字・数字・_で1〜64文字のIDを入力してください")
                }
            }
            onCancel { player.sendMessage("${QuestPluginV2.PREFIX}§c§lキャンセルしました") }
            onReceive { callback(it) }
        }
    }

    // ==========================================================
    //  クエスト保存
    // ==========================================================

    private fun saveQuest(
        player: Player,
        id: String,
        name: String,
        type: QuestType,
        target: String?,
        amount: Int
    ) {
        val quest = Quest(id, name, "説明未設定", type, target, amount)

        if (questplugin.questManager.saveQuest(quest)) {
            player.sendMessage("${QuestPluginV2.PREFIX}§a§lクエスト作成完了！")
            player.sendMessage("§7§lID: §f§l$id")
            player.sendMessage("§7§l名前: §f§l$name")
            player.sendMessage("§7§lタイプ: §f§l${type.name}")
            player.sendMessage("§7§lターゲット: §f§l${target ?: "なし"}")
            player.sendMessage("§7§l必要数: §f§l$amount")
            player.sendMessage("§7§l説明・報酬などは /questop で設定できます。")
        } else {
            player.sendMessage("${QuestPluginV2.PREFIX}§c§l保存中にエラーが発生しました。コンソールを確認してください")
        }
    }
}