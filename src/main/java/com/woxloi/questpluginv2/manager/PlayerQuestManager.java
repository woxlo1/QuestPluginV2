package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.model.playerquest.PlayerQuest;
import com.woxloi.questpluginv2.model.playerquest.PlayerQuestDraft;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 民間クエスト（プレイヤー作成の依頼）の管理クラス。
 *
 * 流れ:
 *   1. create  : ドラフト開始（メモリ上のみ）
 *   2. settarget / setamount / setmaxacceptors / setreward : ドラフトを編集
 *   3. publish : Vaultから報酬金(報酬×上限人数)をエスクローし、DBに保存して掲示板に公開
 *   4. accept  : 他プレイヤーが受注（上限人数まで）
 *   5. submit  : 受注者がインベントリのアイテムをチェックし、十分なら
 *                ・アイテムを依頼主へ送付（オンラインなら直接、オフラインならpending_item_deliveriesへ）
 *                ・報酬金を受注者へ即時付与（Vault）
 *   6. ログイン時に PlayerJoinQuitListener から deliverPendingItems() を呼び、
 *      オフライン中に溜まった納品アイテムを配達する。
 */
public class PlayerQuestManager {

    private final QuestPluginV2 plugin;
    private final DatabaseManager db;

    private final Map<Integer, PlayerQuest> quests = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerQuestDraft> drafts = new ConcurrentHashMap<>();

    public PlayerQuestManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        loadAll();
    }

    // ============================================================
    //  読み込み
    // ============================================================

    private void loadAll() {
        quests.clear();
        try {
            db.query("SELECT * FROM player_quests", null, rs -> {
                while (rs.next()) {
                    Material mat = Material.matchMaterial(rs.getString("target_material"));
                    if (mat == null) continue;
                    PlayerQuest q = new PlayerQuest(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("creator_uuid")),
                            rs.getString("creator_name"),
                            mat,
                            rs.getInt("total_amount"),
                            rs.getInt("max_acceptors"),
                            rs.getInt("per_person_amount"),
                            rs.getDouble("reward_money"),
                            rs.getInt("is_open") == 1,
                            rs.getTimestamp("created_at").toInstant()
                    );
                    quests.put(q.getId(), q);
                }
                return null;
            });

            db.query("SELECT * FROM player_quest_acceptors", null, rs -> {
                while (rs.next()) {
                    int questId = rs.getInt("quest_id");
                    PlayerQuest q = quests.get(questId);
                    if (q == null) continue;
                    q.getAcceptors().put(UUID.fromString(rs.getString("acceptor_uuid")), rs.getInt("completed") == 1);
                }
                return null;
            });

            plugin.getLogger().info(quests.size() + "件の民間クエストを読み込みました。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "民間クエスト読み込み中にエラー: " + e.getMessage(), e);
        }
    }

    // ============================================================
    //  ドラフト
    // ============================================================

    public PlayerQuestDraft startDraft(Player creator) {
        PlayerQuestDraft draft = new PlayerQuestDraft(creator.getUniqueId(), creator.getName());
        drafts.put(creator.getUniqueId(), draft);
        return draft;
    }

    public PlayerQuestDraft getDraft(UUID uuid) { return drafts.get(uuid); }

    public void clearDraft(UUID uuid) { drafts.remove(uuid); }

    // ============================================================
    //  公開（publish）
    // ============================================================

    public enum PublishResult {
        SUCCESS, NO_DRAFT, INVALID_MATERIAL, INVALID_AMOUNT, INVALID_MAX_ACCEPTORS,
        VAULT_DISABLED, INSUFFICIENT_FUNDS, DB_ERROR
    }

    public PublishResult publish(Player creator) {
        PlayerQuestDraft draft = drafts.get(creator.getUniqueId());
        if (draft == null) return PublishResult.NO_DRAFT;
        if (draft.getTargetMaterial() == null || !draft.getTargetMaterial().isItem()) return PublishResult.INVALID_MATERIAL;
        if (draft.getTotalAmount() <= 0) return PublishResult.INVALID_AMOUNT;
        if (draft.getMaxAcceptors() <= 0) return PublishResult.INVALID_MAX_ACCEPTORS;

        VaultManager vault = plugin.getVaultManager();
        if (!vault.isEnabled()) return PublishResult.VAULT_DISABLED;

        int perPerson = draft.getPerPersonAmount();
        double escrowTotal = draft.getRewardMoney() * draft.getMaxAcceptors();

        if (escrowTotal > 0) {
            double balance = vault.getEconomy().getBalance(creator);
            if (balance < escrowTotal) return PublishResult.INSUFFICIENT_FUNDS;

            EconomyResponse resp = vault.getEconomy().withdrawPlayer(creator, escrowTotal);
            if (!resp.transactionSuccess()) return PublishResult.INSUFFICIENT_FUNDS;
        }

        try {
            Integer newId = db.query("SELECT 1", null, rs -> null); // ダミー（型合わせ用、下のupdateで実IDを取得）
        } catch (SQLException ignored) {}

        try {
            db.update("""
                INSERT INTO player_quests
                    (creator_uuid, creator_name, target_material, total_amount, max_acceptors, per_person_amount, reward_money, is_open)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """,
                    ps -> {
                        ps.setString(1, creator.getUniqueId().toString());
                        ps.setString(2, creator.getName());
                        ps.setString(3, draft.getTargetMaterial().name());
                        ps.setInt(4, draft.getTotalAmount());
                        ps.setInt(5, draft.getMaxAcceptors());
                        ps.setInt(6, perPerson);
                        ps.setDouble(7, draft.getRewardMoney());
                    }
            );

            Integer id = db.query("SELECT LAST_INSERT_ID()", null, rs -> rs.next() ? rs.getInt(1) : null);
            if (id == null) {
                // 保存に失敗したので、引いたお金を返す
                if (escrowTotal > 0) vault.getEconomy().depositPlayer(creator, escrowTotal);
                return PublishResult.DB_ERROR;
            }

            PlayerQuest quest = new PlayerQuest(id, creator.getUniqueId(), creator.getName(),
                    draft.getTargetMaterial(), draft.getTotalAmount(), draft.getMaxAcceptors(),
                    perPerson, draft.getRewardMoney(), true, Instant.now());
            quests.put(id, quest);
            drafts.remove(creator.getUniqueId());
            return PublishResult.SUCCESS;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "民間クエスト公開中にエラー: " + e.getMessage(), e);
            if (escrowTotal > 0) vault.getEconomy().depositPlayer(creator, escrowTotal);
            return PublishResult.DB_ERROR;
        }
    }

    // ============================================================
    //  受注（accept）
    // ============================================================

    public enum AcceptResult { SUCCESS, NOT_FOUND, CLOSED, ALREADY_ACCEPTED, FULL, OWN_QUEST, DB_ERROR }

    public AcceptResult accept(Player player, int questId) {
        PlayerQuest quest = quests.get(questId);
        if (quest == null) return AcceptResult.NOT_FOUND;
        if (!quest.isOpen()) return AcceptResult.CLOSED;
        if (quest.getCreatorUUID().equals(player.getUniqueId())) return AcceptResult.OWN_QUEST;
        if (quest.isAccepting(player.getUniqueId())) return AcceptResult.ALREADY_ACCEPTED;
        if (quest.isFull()) return AcceptResult.FULL;

        try {
            db.update("INSERT INTO player_quest_acceptors (quest_id, acceptor_uuid) VALUES (?, ?)",
                    ps -> { ps.setInt(1, questId); ps.setString(2, player.getUniqueId().toString()); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "民間クエスト受注保存中にエラー: " + e.getMessage(), e);
            return AcceptResult.DB_ERROR;
        }

        quest.getAcceptors().put(player.getUniqueId(), false);

        // 上限に達したら募集終了（既存の受注者はまだsubmit可能）
        if (quest.isFull()) {
            quest.setOpen(false);
            try {
                db.update("UPDATE player_quests SET is_open = 0 WHERE id = ?", ps -> ps.setInt(1, questId));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "募集締切の保存に失敗: " + e.getMessage(), e);
            }
        }

        return AcceptResult.SUCCESS;
    }

    // ============================================================
    //  納品（submit）
    // ============================================================

    public enum SubmitResult { SUCCESS, NOT_FOUND, NOT_ACCEPTOR, ALREADY_COMPLETED, NOT_ENOUGH_ITEMS, CREATOR_INVENTORY_FULL, VAULT_DISABLED, DB_ERROR }

    /** 不足時の表示用に、最後にチェックした所持数/必要数を保持する */
    private final Map<UUID, int[]> lastSubmitCheck = new ConcurrentHashMap<>(); // [have, need]

    public SubmitResult submit(Player player, int questId) {
        PlayerQuest quest = quests.get(questId);
        if (quest == null) return SubmitResult.NOT_FOUND;
        if (!quest.isAccepting(player.getUniqueId())) return SubmitResult.NOT_ACCEPTOR;
        if (quest.hasCompleted(player.getUniqueId())) return SubmitResult.ALREADY_COMPLETED;

        VaultManager vault = plugin.getVaultManager();
        if (quest.getRewardMoney() > 0 && !vault.isEnabled()) return SubmitResult.VAULT_DISABLED;

        PlayerInventory inv = player.getInventory();
        int have = countMaterial(inv, quest.getTargetMaterial());
        int need = quest.getPerPersonAmount();

        if (have < need) {
            lastSubmitCheck.put(player.getUniqueId(), new int[]{have, need});
            return SubmitResult.NOT_ENOUGH_ITEMS;
        }

        ItemStack deliveryStack = new ItemStack(quest.getTargetMaterial(), need);

        // ---- 依頼主への納品先を確保する（先にスペース確認 → 確保できなければ何もせず中止） ----
        Player creatorOnline = Bukkit.getPlayer(quest.getCreatorUUID());
        if (creatorOnline != null && creatorOnline.isOnline()) {
            if (!hasSpaceFor(creatorOnline.getInventory(), deliveryStack)) {
                return SubmitResult.CREATOR_INVENTORY_FULL;
            }
            // ここまで来たら確実に入る。先にDB更新が失敗した場合に戻せるよう、DB更新を先に試す。
        }

        // ---- DB更新（受注者の完了フラグ） ----
        try {
            db.update("UPDATE player_quest_acceptors SET completed = 1, completed_at = NOW() WHERE quest_id = ? AND acceptor_uuid = ?",
                    ps -> { ps.setInt(1, questId); ps.setString(2, player.getUniqueId().toString()); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "民間クエスト完了保存中にエラー: " + e.getMessage(), e);
            return SubmitResult.DB_ERROR;
        }

        // ---- ここから実際の受け渡し（DB更新が成功したので実行して問題ない） ----
        removeMaterial(inv, quest.getTargetMaterial(), need);

        if (creatorOnline != null && creatorOnline.isOnline()) {
            Map<Integer, ItemStack> leftover = creatorOnline.getInventory().addItem(deliveryStack);
            if (!leftover.isEmpty()) {
                // 念のための保険（直前のhasSpaceForと矛盾するケース）：残りはメールボックスへ
                leftover.values().forEach(stack -> queueDelivery(quest.getCreatorUUID(), stack));
            }
            creatorOnline.sendMessage(QuestPluginV2.PREFIX + "§a§l" + player.getName() + "§7§lさんが依頼["
                    + quest.getId() + "]の" + quest.getTargetMaterial().name() + "を納品しました（" + need + "個）");
        } else {
            queueDelivery(quest.getCreatorUUID(), deliveryStack);
        }

        if (quest.getRewardMoney() > 0) {
            vault.getEconomy().depositPlayer(player, quest.getRewardMoney());
        }

        quest.getAcceptors().put(player.getUniqueId(), true);
        lastSubmitCheck.remove(player.getUniqueId());

        // 受注者全員が完了したらクエストを完全終了扱いに（募集自体は既にisFullで閉じている想定）
        if (quest.allAcceptorsCompleted() && quest.getAcceptedCount() > 0) {
            quest.setOpen(false);
        }

        return SubmitResult.SUCCESS;
    }

    /** submit失敗時に「あと何個必要か」を表示するためのヘルパー */
    public int[] getLastSubmitCheck(UUID uuid) {
        return lastSubmitCheck.getOrDefault(uuid, new int[]{0, 0});
    }

    // ============================================================
    //  削除（delete） / キャンセル
    // ============================================================

    public enum DeleteResult { SUCCESS, NOT_FOUND, NOT_OWNER, HAS_ACTIVE_ACCEPTORS, DB_ERROR }

    public DeleteResult delete(Player requester, int questId) {
        PlayerQuest quest = quests.get(questId);
        if (quest == null) return DeleteResult.NOT_FOUND;
        if (!quest.getCreatorUUID().equals(requester.getUniqueId()) && !requester.hasPermission("questpluginv2.op")) {
            return DeleteResult.NOT_OWNER;
        }
        boolean hasActive = quest.getAcceptors().values().stream().anyMatch(completed -> !completed);
        if (hasActive) return DeleteResult.HAS_ACTIVE_ACCEPTORS;

        try {
            db.update("DELETE FROM player_quests WHERE id = ?", ps -> ps.setInt(1, questId));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "民間クエスト削除中にエラー: " + e.getMessage(), e);
            return DeleteResult.DB_ERROR;
        }

        quests.remove(questId);

        // 使われなかった分の報酬金（未受注スロット）を返金する
        int unfilledSlots = quest.getMaxAcceptors() - quest.getCompletedCount();
        double refund = quest.getRewardMoney() * unfilledSlots;
        VaultManager vault = plugin.getVaultManager();
        if (refund > 0 && vault.isEnabled()) {
            OfflinePlayer creator = Bukkit.getOfflinePlayer(quest.getCreatorUUID());
            vault.getEconomy().depositPlayer(creator, refund);
        }

        return DeleteResult.SUCCESS;
    }

    // ============================================================
    //  クエリ
    // ============================================================

    public PlayerQuest getQuest(int id) { return quests.get(id); }

    public List<PlayerQuest> getOpenQuests() {
        List<PlayerQuest> list = new ArrayList<>();
        for (PlayerQuest q : quests.values()) {
            if (q.isOpen()) list.add(q);
        }
        return list;
    }

    public List<PlayerQuest> getQuestsByCreator(UUID uuid) {
        List<PlayerQuest> list = new ArrayList<>();
        for (PlayerQuest q : quests.values()) {
            if (q.getCreatorUUID().equals(uuid)) list.add(q);
        }
        return list;
    }

    public List<PlayerQuest> getQuestsAcceptedBy(UUID uuid) {
        List<PlayerQuest> list = new ArrayList<>();
        for (PlayerQuest q : quests.values()) {
            if (q.isAccepting(uuid)) list.add(q);
        }
        return list;
    }

    // ============================================================
    //  オフライン配送（メールボックス）
    // ============================================================

    private void queueDelivery(UUID recipient, ItemStack stack) {
        try {
            db.update("INSERT INTO pending_item_deliveries (recipient_uuid, material, amount) VALUES (?, ?, ?)",
                    ps -> {
                        ps.setString(1, recipient.toString());
                        ps.setString(2, stack.getType().name());
                        ps.setInt(3, stack.getAmount());
                    });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "納品アイテムのキュー保存中にエラー: " + e.getMessage(), e);
        }
    }

    /**
     * ログイン時に呼ぶ。オフライン中に溜まった納品アイテムを配達する。
     * DB読み込みは非同期、インベントリ操作はメインスレッドで行う。
     */
    public void deliverPendingItems(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> rowsMeta = new ArrayList<>(); // [id]
            List<ItemStack> stacks = new ArrayList<>();
            try {
                db.query("SELECT id, material, amount FROM pending_item_deliveries WHERE recipient_uuid = ?",
                        ps -> ps.setString(1, uuid.toString()),
                        rs -> {
                            while (rs.next()) {
                                Material mat = Material.matchMaterial(rs.getString("material"));
                                if (mat == null) continue;
                                rowsMeta.add(new int[]{rs.getInt("id")});
                                stacks.add(new ItemStack(mat, rs.getInt("amount")));
                            }
                            return null;
                        });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "保留納品の読み込み中にエラー: " + e.getMessage(), e);
                return;
            }

            if (stacks.isEmpty()) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                for (int i = 0; i < stacks.size(); i++) {
                    int rowId = rowsMeta.get(i)[0];
                    ItemStack stack = stacks.get(i);

                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);

                    if (leftover.isEmpty()) {
                        deleteDeliveryRow(rowId);
                    } else {
                        int delivered = stack.getAmount() - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                        int remaining = stack.getAmount() - delivered;
                        updateDeliveryRowAmount(rowId, remaining);
                        player.sendMessage(QuestPluginV2.PREFIX + "§e§lインベントリの空きが足りず、"
                                + stack.getType().name() + "を" + remaining + "個受け取れませんでした。空きを作って再ログインしてください");
                    }
                }
                player.sendMessage(QuestPluginV2.PREFIX + "§a§l民間クエストの納品アイテムを受け取りました");
            });
        });
    }

    private void deleteDeliveryRow(int rowId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("DELETE FROM pending_item_deliveries WHERE id = ?", ps -> ps.setInt(1, rowId));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "配送済み行の削除中にエラー: " + e.getMessage(), e);
            }
        });
    }

    private void updateDeliveryRowAmount(int rowId, int newAmount) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("UPDATE pending_item_deliveries SET amount = ? WHERE id = ?",
                        ps -> { ps.setInt(1, newAmount); ps.setInt(2, rowId); });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "保留納品の残数更新中にエラー: " + e.getMessage(), e);
            }
        });
    }

    // ============================================================
    //  インベントリ操作ヘルパー
    // ============================================================

    /** 実際には変更せず、stackがちょうど入りきるかだけを判定する */
    private boolean hasSpaceFor(Inventory realInv, ItemStack stack) {
        Inventory sim = Bukkit.createInventory(null, 36);
        ItemStack[] storage = realInv instanceof PlayerInventory
                ? ((PlayerInventory) realInv).getStorageContents()
                : realInv.getContents();
        sim.setContents(storage.clone());
        Map<Integer, ItemStack> leftover = sim.addItem(stack.clone());
        return leftover.isEmpty();
    }

    /** メイン+ホットバー(36スロット)内にある指定マテリアルの合計数を数える（防具・オフハンドは対象外） */
    private int countMaterial(PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** 指定マテリアルを合計amount個、複数スタックにまたがって削除する */
    private void removeMaterial(PlayerInventory inv, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) continue;

            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;

            if (item.getAmount() <= 0) {
                inv.setItem(i, null);
            } else {
                inv.setItem(i, item);
            }
        }
    }
}