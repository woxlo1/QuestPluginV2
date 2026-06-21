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
 * 編集GUI
 */
class QuestEditGUI(private val questplugin: QuestPluginV2) : OraInventory(
    plugin = questplugin,
    title  = "§a§l",
    rows   = 6
) {
    
}