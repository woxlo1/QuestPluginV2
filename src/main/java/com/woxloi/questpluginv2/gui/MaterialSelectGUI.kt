package com.woxloi.questpluginv2.gui

import com.woxloi.questpluginv2.QuestPluginV2
import oraserver.orapluginapi.inventory.OraInventory
import oraserver.orapluginapi.inventory.OraInventoryItem
import org.bukkit.Material
import org.bukkit.entity.Player

enum class MaterialCategory(val displayName: String, val icon: Material) {
    TOOLS_WEAPONS("道具・武器", Material.DIAMOND_SWORD),
    ARMOR("防具", Material.DIAMOND_CHESTPLATE),
    FOOD("食料", Material.COOKED_BEEF),
    ORES_AND_GEMS("鉱石・宝石", Material.DIAMOND),
    REDSTONE("レッドストーン", Material.REDSTONE),
    MOB_DROPS("Mobドロップ", Material.BONE),
    DECORATION("装飾・染料", Material.RED_DYE),
    BLOCKS("ブロック", Material.STONE),
    OTHER("その他", Material.CHEST);

    companion object {
        private val mobDropNames = setOf(
            "ROTTEN_FLESH", "BONE", "BONE_MEAL", "STRING", "FEATHER", "LEATHER", "RABBIT_HIDE",
            "GUNPOWDER", "SPIDER_EYE", "SLIME_BALL", "ENDER_PEARL", "GHAST_TEAR", "MAGMA_CREAM",
            "PHANTOM_MEMBRANE", "NETHER_STAR", "SHULKER_SHELL", "INK_SAC", "GLOW_INK_SAC",
            "RABBIT_FOOT", "TURTLE_SCUTE", "BLAZE_ROD", "BLAZE_POWDER", "PRISMARINE_SHARD",
            "PRISMARINE_CRYSTALS", "NAUTILUS_SHELL", "HONEYCOMB", "ARMADILLO_SCUTE"
        )

        /** マテリアルをカテゴリーに分類する。アイテムでなければ null */
        fun classify(material: Material): MaterialCategory? {
            if (!material.isItem) return null
            val n = material.name

            if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") ||
                n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.contains("BOW") ||
                n == "TRIDENT" || n == "SHIELD" || n == "FISHING_ROD" || n == "FLINT_AND_STEEL") {
                return TOOLS_WEAPONS
            }
            if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") ||
                n.endsWith("_BOOTS") || n == "ELYTRA" || n.endsWith("_HORSE_ARMOR")) {
                return ARMOR
            }
            if (material.isEdible) return FOOD
            if (n.contains("ORE") || n.contains("INGOT") || n.contains("NUGGET") || n.contains("QUARTZ") ||
                n.contains("NETHERITE") || n == "DIAMOND" || n == "EMERALD" || n == "COAL" ||
                n == "CHARCOAL" || n.startsWith("RAW_")) {
                return ORES_AND_GEMS
            }
            if (n.contains("REDSTONE") || n.contains("REPEATER") || n.contains("COMPARATOR") ||
                n.contains("OBSERVER") || n.contains("PISTON") || n.contains("HOPPER") ||
                n.contains("DISPENSER") || n.contains("DROPPER") || n.contains("RAIL")) {
                return REDSTONE
            }
            if (mobDropNames.contains(n)) return MOB_DROPS
            if (n.contains("DYE") || n.contains("BANNER") || n.contains("CARPET") || n.contains("BED") ||
                n.contains("FLOWER") || n.contains("PAINTING") || n.contains("POT") || n.contains("CANDLE")) {
                return DECORATION
            }
            if (material.isBlock) return BLOCKS
            return OTHER
        }
    }
}

/**
 * カテゴリー選択画面。クリックでそのカテゴリーの一覧(MaterialListGUI)を開く。
 * onSelect が呼ばれた時点でGUIは閉じる。呼び出し元は onSelect 内で自分のGUIを再度開き直すこと。
 */
class MaterialSelectCategoryGUI(
    private val questplugin: QuestPluginV2,
    private val viewer: Player,
    private val onSelect: (Material) -> Unit,
    private val onCancel: () -> Unit = {}
) : OraInventory(plugin = questplugin, title = "§6§lアイテム選択 — カテゴリーを選んでください", rows = 4) {

    private val glass = OraInventoryItem(Material.GRAY_STAINED_GLASS_PANE).setDisplayName("§r").setCanClick(false)

    override fun onOpen(player: Player): Boolean {
        for (i in 0 until 36) setItem(i, glass)

        MaterialCategory.values().forEachIndexed { index, category ->
            if (index >= 9) return@forEachIndexed
            setItem(9 + index, OraInventoryItem(category.icon)
                .setDisplayName("§e§l${category.displayName}")
                .setLore(listOf("§7クリックして一覧を表示"))
                .setCanClick(false)
                .setClickEvent { e ->
                    val clicker = e.whoClicked as? Player ?: return@setClickEvent
                    MaterialListGUI(questplugin, clicker, category, 0, onSelect, onCancel).open(clicker)
                }
            )
        }

        setItem(31, OraInventoryItem(Material.BARRIER)
            .setDisplayName("§c§lキャンセル")
            .setCanClick(false)
            .setClickEvent { e ->
                val clicker = e.whoClicked as? Player ?: return@setClickEvent
                clicker.closeInventory()
                onCancel()
            }
        )
        return true
    }
}

/** 選択したカテゴリー内のマテリアル一覧（45件/ページ） */
class MaterialListGUI(
    private val questplugin: QuestPluginV2,
    private val viewer: Player,
    private val category: MaterialCategory,
    private val page: Int,
    private val onSelect: (Material) -> Unit,
    private val onCancel: () -> Unit
) : OraInventory(plugin = questplugin, title = "§6§l${category.displayName} (ページ ${page + 1})", rows = 6) {

    companion object {
        // サーバー起動中はマテリアル一覧が変わらないのでキャッシュする
        private val cache = mutableMapOf<MaterialCategory, List<Material>>()

        private fun materialsOf(category: MaterialCategory): List<Material> =
            cache.getOrPut(category) {
                Material.values()
                    .filter { !it.isLegacy && MaterialCategory.classify(it) == category }
                    .sortedBy { it.name }
            }
    }

    override fun onOpen(player: Player): Boolean {
        val all = materialsOf(category)
        val perPage = 45
        val maxPage = ((all.size - 1) / perPage).coerceAtLeast(0)
        val currentPage = page.coerceIn(0, maxPage)
        val pageItems = all.drop(currentPage * perPage).take(perPage)

        pageItems.forEachIndexed { i, material ->
            setItem(i, OraInventoryItem(material)
                .setDisplayName("§e${material.name}")
                .setLore(listOf("§7クリックで選択"))
                .setCanClick(false)
                .setClickEvent { e ->
                    val clicker = e.whoClicked as? Player ?: return@setClickEvent
                    clicker.closeInventory()
                    onSelect(material)
                }
            )
        }

        if (currentPage > 0) {
            setItem(45, OraInventoryItem(Material.ARROW)
                .setDisplayName("§e前のページ")
                .setCanClick(false)
                .setClickEvent { e ->
                    val clicker = e.whoClicked as? Player ?: return@setClickEvent
                    MaterialListGUI(questplugin, clicker, category, currentPage - 1, onSelect, onCancel).open(clicker)
                }
            )
        }

        setItem(49, OraInventoryItem(Material.BOOK)
            .setDisplayName("§fページ ${currentPage + 1}/${maxPage + 1}")
            .setLore(listOf("§7クリックでカテゴリーへ戻る"))
            .setCanClick(false)
            .setClickEvent { e ->
                val clicker = e.whoClicked as? Player ?: return@setClickEvent
                MaterialSelectCategoryGUI(questplugin, clicker, onSelect, onCancel).open(clicker)
            }
        )

        if (currentPage < maxPage) {
            setItem(53, OraInventoryItem(Material.ARROW)
                .setDisplayName("§e次のページ")
                .setCanClick(false)
                .setClickEvent { e ->
                    val clicker = e.whoClicked as? Player ?: return@setClickEvent
                    MaterialListGUI(questplugin, clicker, category, currentPage + 1, onSelect, onCancel).open(clicker)
                }
            )
        }

        setItem(48, OraInventoryItem(Material.BARRIER)
            .setDisplayName("§c§lキャンセル")
            .setCanClick(false)
            .setClickEvent { e ->
                val clicker = e.whoClicked as? Player ?: return@setClickEvent
                clicker.closeInventory()
                onCancel()
            }
        )
        return true
    }
}