package com.woxloi.questpluginv2.listener;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.manager.ActiveQuestManager;
import com.woxloi.questpluginv2.manager.QuestManager;
import com.woxloi.questpluginv2.model.quest.QuestType;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * クエスト進捗を検知するリスナー
 * 全QuestTypeのイベントを処理する
 *
 * 修正点: 以前は questManager（/quest accept 系）にしか進捗を通知しておらず、
 * activeQuestManager（/quest start で開始する、ボスバー・スコアボード付きの
 * クエスト）には一切進捗が反映されていなかった。MythicMobsListener の
 * BOSS_KILL 処理と同様に、両方のマネージャーへ通知するよう統一した。
 */
public class QuestProgressListener implements Listener {

    private final QuestPluginV2 plugin;
    private final QuestManager questManager;
    private final ActiveQuestManager activeQuestManager;

    public QuestProgressListener(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.questManager = plugin.getQuestManager();
        this.activeQuestManager = plugin.getActiveQuestManager();
    }

    /** questManager / activeQuestManager の両方へ加算式の進捗を通知する */
    private void addProgress(UUID playerUUID, QuestType type, String targetId, int amount) {
        questManager.addProgress(playerUUID, type, targetId, amount);
        activeQuestManager.addProgress(playerUUID, type, targetId, amount);
    }

    /** questManager / activeQuestManager の両方へ絶対値の進捗を通知する（LEVEL_UP等） */
    private void setAbsoluteProgress(UUID playerUUID, QuestType type, String targetId, int value) {
        questManager.setAbsoluteProgress(playerUUID, type, targetId, value);
        activeQuestManager.setAbsoluteProgress(playerUUID, type, targetId, value);
    }

    // ---- KILL_MOB / KILL_PLAYER ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity killed = event.getEntity();
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        UUID killerUUID = killer.getUniqueId();

        if (killed instanceof Player) {
            // KILL_PLAYER
            addProgress(killerUUID, QuestType.KILL_PLAYER, null, 1);
        } else {
            // KILL_MOB: エンティティ種別名で検索
            String entityType = killed.getType().name();
            addProgress(killerUUID, QuestType.KILL_MOB, entityType, 1);
            // カスタム名も検索対象に（例: MythicMobs以外の名前付きMob）
            if (killed.getCustomName() != null) {
                addProgress(killerUUID, QuestType.KILL_MOB, killed.getCustomName(), 1);
            }
        }
    }

    // ---- BREAK_BLOCK / BREAK_BLOCK_TYPE ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String blockType = event.getBlock().getType().name();
        addProgress(player.getUniqueId(), QuestType.BREAK_BLOCK, blockType, 1);
        addProgress(player.getUniqueId(), QuestType.BREAK_BLOCK_TYPE, blockType, 1);
    }

    // ---- PLACE_BLOCK ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String blockType = event.getBlock().getType().name();
        addProgress(player.getUniqueId(), QuestType.PLACE_BLOCK, blockType, 1);
    }

    // ---- COLLECT_ITEM ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem().getItemStack();
        String itemType = item.getType().name();
        addProgress(player.getUniqueId(), QuestType.COLLECT_ITEM, itemType, item.getAmount());
    }

    // ---- WALK_DISTANCE ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        // 水中かどうかで SWIM / WALK を分岐
        if (player.isSwimming() || player.getLocation().getBlock().isLiquid()) {
            addProgress(player.getUniqueId(), QuestType.SWIM_DISTANCE, null, 1);
        } else if (!player.isFlying() && player.isOnGround()) {
            addProgress(player.getUniqueId(), QuestType.WALK_DISTANCE, null, 1);
        }
    }

    // ---- CRAFT_ITEM ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getRecipe().getResult();
        String itemType = result.getType().name();
        // シフトクリック時の個数を考慮
        int amount = result.getAmount();
        if (event.isShiftClick()) {
            // 大まかな個数（実際の個数はインベントリ状況次第）
            int times = 1;
            for (ItemStack matrix : event.getInventory().getMatrix()) {
                if (matrix != null && matrix.getType() != Material.AIR) {
                    times = Math.max(1, matrix.getAmount());
                    break;
                }
            }
            amount = result.getAmount() * times;
        }
        addProgress(player.getUniqueId(), QuestType.CRAFT_ITEM, itemType, amount);
    }

    // ---- FISHING ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer();
        if (event.getCaught() instanceof Item item) {
            String itemType = item.getItemStack().getType().name();
            addProgress(player.getUniqueId(), QuestType.FISHING, itemType, item.getItemStack().getAmount());
        } else {
            addProgress(player.getUniqueId(), QuestType.FISHING, null, 1);
        }
    }

    // ---- FARMING (収穫) ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFarming(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlock().getType();
        // 成熟した作物かチェック
        if (isMatureCrop(type)) {
            addProgress(player.getUniqueId(), QuestType.FARMING, type.name(), 1);
        }
    }

    private boolean isMatureCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART,
                 MELON, PUMPKIN, COCOA, SUGAR_CANE -> true;
            default -> false;
        };
    }

    // ---- ENCHANT ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent event) {
        Player player = event.getEnchanter();
        addProgress(player.getUniqueId(), QuestType.ENCHANT, null, 1);
    }

    // ---- TRADE ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(org.bukkit.event.inventory.TradeSelectEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        addProgress(player.getUniqueId(), QuestType.TRADE, null, 1);
    }

    // ---- TAME ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) return;
        String entityType = event.getEntity().getType().name();
        addProgress(player.getUniqueId(), QuestType.TAME, entityType, 1);
    }

    // ---- TAKE_DAMAGE ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        int dmg = (int) event.getFinalDamage();
        if (dmg > 0) {
            addProgress(player.getUniqueId(), QuestType.TAKE_DAMAGE, null, dmg);
        }
    }

    // ---- LEVEL_UP ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLevelChange(PlayerLevelChangeEvent event) {
        Player player = event.getPlayer();
        int newLevel = event.getNewLevel();
        // レベルアップした場合（到達値なので絶対値セットを使用する）
        if (newLevel > player.getLevel()) {
            setAbsoluteProgress(player.getUniqueId(), QuestType.LEVEL_UP, String.valueOf(newLevel), newLevel);
        }
    }
}