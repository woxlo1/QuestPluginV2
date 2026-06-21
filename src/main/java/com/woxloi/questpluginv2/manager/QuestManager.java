package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.model.party.Party;
import com.woxloi.questpluginv2.model.quest.PlayerQuestProgress;
import com.woxloi.questpluginv2.model.quest.Quest;
import com.woxloi.questpluginv2.model.quest.QuestType;
import com.woxloi.questpluginv2.model.reward.QuestReward;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * クエスト管理の中枢クラス
 * キャッシュ + MySQL の二層構造で管理
 *
 * 修正点:
 *  - 報酬付与ロジックを RewardGiver に委譲（ActiveQuestManagerとの重複解消）。
 *  - getLastCompletedTime() を COMPLETED のみに限定し、FAILED 履行記録で
 *    クールダウンが発生してしまう問題を修正（「成功時のみクールダウン開始」
 *    という仕様に合わせた）。
 *  - addProgress() の進捗保存（saveProgress）を非同期化し、WALK_DISTANCE等で
 *    プレイヤー移動ごとにメインスレッドでJDBC書き込みが発生していた
 *    パフォーマンス問題に対応。
 */
public class QuestManager {

    private final QuestPluginV2 plugin;
    private final DatabaseManager db;

    /** クエスト定義キャッシュ: questId -> Quest */
    private final Map<String, Quest> questCache = new ConcurrentHashMap<>();

    /** プレイヤー進捗キャッシュ: playerUUID -> (questId -> progress) */
    private final Map<UUID, Map<String, PlayerQuestProgress>> progressCache = new ConcurrentHashMap<>();

    public QuestManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        loadAllQuests();
    }

    // ========== クエスト定義管理 ==========

    public void loadAllQuests() {
        questCache.clear();
        try {
            db.query("SELECT * FROM quests", null, rs -> {
                while (rs.next()) {
                    Quest q = questFromResultSet(rs);
                    if (q != null) questCache.put(q.getId(), q);
                }
                return null;
            });
            for (Quest q : questCache.values()) {
                loadRewardsForQuest(q);
            }
            plugin.getLogger().info(questCache.size() + " 件のクエストを読み込みました");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "クエスト読み込み中にエラー: " + e.getMessage(), e);
        }
    }

    private Quest questFromResultSet(ResultSet rs) throws SQLException {
        QuestType type = QuestType.fromString(rs.getString("type"));
        if (type == null) return null;
        Quest q = new Quest(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                type,
                rs.getString("target_id"),
                rs.getInt("required_amount")
        );
        q.setPrerequisiteQuestId(rs.getString("prerequisite_quest"));
        q.setCooldownSeconds(rs.getLong("cooldown_seconds"));
        q.setPartyEnabled(rs.getInt("party_enabled") == 1);
        return q;
    }

    private void loadRewardsForQuest(Quest quest) throws SQLException {
        db.query("SELECT reward_data FROM quest_rewards WHERE quest_id = ? ORDER BY sort_order",
                ps -> ps.setString(1, quest.getId()),
                rs -> {
                    while (rs.next()) {
                        QuestReward reward = QuestReward.deserialize(rs.getString("reward_data"));
                        if (reward != null) quest.addReward(reward);
                    }
                    return null;
                });
    }

    public Quest getQuest(String questId) {
        return questCache.get(questId);
    }

    public Collection<Quest> getAllQuests() {
        return Collections.unmodifiableCollection(questCache.values());
    }

    public boolean saveQuest(Quest quest) {
        try {
            db.update("""
                INSERT INTO quests (id, name, description, type, target_id, required_amount,
                    prerequisite_quest, cooldown_seconds, party_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name), description = VALUES(description),
                    type = VALUES(type), target_id = VALUES(target_id),
                    required_amount = VALUES(required_amount),
                    prerequisite_quest = VALUES(prerequisite_quest),
                    cooldown_seconds = VALUES(cooldown_seconds),
                    party_enabled = VALUES(party_enabled)
                """,
                    ps -> {
                        ps.setString(1, quest.getId());
                        ps.setString(2, quest.getName());
                        ps.setString(3, quest.getDescription());
                        ps.setString(4, quest.getType().name());
                        ps.setString(5, quest.getTargetId());
                        ps.setInt(6, quest.getRequiredAmount());
                        ps.setString(7, quest.getPrerequisiteQuestId());
                        ps.setLong(8, quest.getCooldownSeconds());
                        ps.setInt(9, quest.isPartyEnabled() ? 1 : 0);
                    }
            );
            db.update("DELETE FROM quest_rewards WHERE quest_id = ?",
                    ps -> ps.setString(1, quest.getId()));
            int order = 0;
            for (QuestReward reward : quest.getRewards()) {
                final int sortOrder = order++;
                db.update("INSERT INTO quest_rewards (quest_id, reward_data, sort_order) VALUES (?, ?, ?)",
                        ps -> {
                            ps.setString(1, quest.getId());
                            ps.setString(2, reward.serialize());
                            ps.setInt(3, sortOrder);
                        });
            }
            questCache.put(quest.getId(), quest);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "クエスト保存中にエラー: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean deleteQuest(String questId) {
        try {
            db.update("DELETE FROM quests WHERE id = ?", ps -> ps.setString(1, questId));
            questCache.remove(questId);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "クエスト削除中にエラー: " + e.getMessage(), e);
            return false;
        }
    }

    // ========== プレイヤー進捗管理 ==========

    public void loadPlayerProgress(UUID playerUUID) {
        Map<String, PlayerQuestProgress> progMap = new HashMap<>();
        try {
            db.query(
                    "SELECT * FROM player_quest_progress WHERE player_uuid = ? AND status = 'ACTIVE'",
                    ps -> ps.setString(1, playerUUID.toString()),
                    rs -> {
                        while (rs.next()) {
                            String questId = rs.getString("quest_id");
                            int current = rs.getInt("current_amount");
                            PlayerQuestProgress.Status status =
                                    PlayerQuestProgress.Status.valueOf(rs.getString("status"));
                            Timestamp startedAt = rs.getTimestamp("started_at");
                            Timestamp completedAt = rs.getTimestamp("completed_at");
                            String partyId = rs.getString("party_id");

                            PlayerQuestProgress prog = new PlayerQuestProgress(
                                    playerUUID, questId, current, status,
                                    startedAt.toInstant(),
                                    completedAt != null ? completedAt.toInstant() : null,
                                    partyId
                            );
                            progMap.put(questId, prog);
                        }
                        return null;
                    }
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "進捗読み込み中にエラー: " + e.getMessage(), e);
        }
        progressCache.put(playerUUID, progMap);
    }

    public void unloadPlayerProgress(UUID playerUUID) {
        progressCache.remove(playerUUID);
    }

    public Map<String, PlayerQuestProgress> getPlayerProgress(UUID playerUUID) {
        return progressCache.getOrDefault(playerUUID, Collections.emptyMap());
    }

    public PlayerQuestProgress getProgress(UUID playerUUID, String questId) {
        Map<String, PlayerQuestProgress> map = progressCache.get(playerUUID);
        return map == null ? null : map.get(questId);
    }

    // ========== クエスト受注・進捗・完了 ==========

    public enum AcceptResult {
        SUCCESS, QUEST_NOT_FOUND, ALREADY_ACTIVE, PREREQUISITE_NOT_MET,
        MAX_ACTIVE_REACHED, ON_COOLDOWN, PARTY_DISABLED
    }

    public AcceptResult acceptQuest(Player player, String questId) {
        Quest quest = questCache.get(questId);
        if (quest == null) return AcceptResult.QUEST_NOT_FOUND;

        UUID uuid = player.getUniqueId();
        Map<String, PlayerQuestProgress> progMap =
                progressCache.computeIfAbsent(uuid, k -> new HashMap<>());

        PlayerQuestProgress existing = progMap.get(questId);
        if (existing != null && existing.isActive()) return AcceptResult.ALREADY_ACTIVE;

        String prereq = quest.getPrerequisiteQuestId();
        if (prereq != null && !isQuestCompleted(uuid, prereq)) {
            return AcceptResult.PREREQUISITE_NOT_MET;
        }

        if (!player.hasPermission("questpluginv2.bypass.limit")) {
            int maxActive = plugin.getConfig().getInt("quest.max-active-quests", 5);
            long activeCount = progMap.values().stream().filter(PlayerQuestProgress::isActive).count();
            if (activeCount >= maxActive) return AcceptResult.MAX_ACTIVE_REACHED;
        }

        if (quest.getCooldownSeconds() > 0) {
            try {
                Instant lastCompleted = getLastCompletedTime(uuid, questId);
                if (lastCompleted != null) {
                    long elapsed = Instant.now().getEpochSecond() - lastCompleted.getEpochSecond();
                    if (elapsed < quest.getCooldownSeconds()) return AcceptResult.ON_COOLDOWN;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "クールダウン確認中にエラー: " + e.getMessage(), e);
            }
        }

        PlayerQuestProgress prog = new PlayerQuestProgress(uuid, questId);
        try {
            db.update("""
                INSERT INTO player_quest_progress (player_uuid, quest_id, current_amount, status, started_at)
                VALUES (?, ?, 0, 'ACTIVE', NOW())
                ON DUPLICATE KEY UPDATE current_amount = 0, status = 'ACTIVE', started_at = NOW(),
                    completed_at = NULL, party_id = NULL
                """,
                    ps -> {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, questId);
                    }
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "クエスト受注保存中にエラー: " + e.getMessage(), e);
            return AcceptResult.QUEST_NOT_FOUND;
        }
        progMap.put(questId, prog);
        return AcceptResult.SUCCESS;
    }

    public void addProgress(UUID playerUUID, QuestType type, String targetId, int amount) {
        applyProgress(playerUUID, type, targetId, amount, false);
    }

    /**
     * 進捗を「加算」ではなく「絶対値としてセット」する。
     * LEVEL_UP のように、イベントが渡してくる値が増加量ではなく到達値である
     * クエストタイプに使用する。同じ値を2回受け取っても進捗が重複加算されない。
     */
    public void setAbsoluteProgress(UUID playerUUID, QuestType type, String targetId, int value) {
        applyProgress(playerUUID, type, targetId, value, true);
    }

    private void applyProgress(UUID playerUUID, QuestType type, String targetId, int amount, boolean absolute) {
        Map<String, PlayerQuestProgress> progMap = progressCache.get(playerUUID);
        if (progMap == null) return;

        for (Map.Entry<String, PlayerQuestProgress> entry : progMap.entrySet()) {
            PlayerQuestProgress prog = entry.getValue();
            if (!prog.isActive()) continue;

            Quest quest = questCache.get(prog.getQuestId());
            if (quest == null) continue;
            if (quest.getType() != type) continue;
            if (targetId != null && quest.getTargetId() != null
                    && !quest.getTargetId().equalsIgnoreCase(targetId)) continue;

            if (absolute) {
                if (amount <= prog.getCurrentAmount()) continue; // 後退・重複は無視
                prog.setCurrentAmount(amount);
            } else {
                prog.addProgress(amount);
            }
            saveProgressAsync(prog);

            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null) {
                player.sendMessage(QuestPluginV2.PREFIX + "§7§lクエスト§e§l" + quest.getName()
                        + "§7§lの進捗: §a§l" + prog.getCurrentAmount() + "§7§l/§a§l" + quest.getRequiredAmount());
            }

            if (prog.getCurrentAmount() >= quest.getRequiredAmount()) {
                completeQuest(playerUUID, quest, prog);
            }

            if (plugin.getConfig().getBoolean("party.progress-share") && prog.getPartyId() != null) {
                Party party = plugin.getPartyManager().getParty(prog.getPartyId());
                if (party != null) {
                    for (UUID memberUUID : party.getMemberUUIDs()) {
                        if (!memberUUID.equals(playerUUID)) {
                            applyProgress(memberUUID, type, targetId, amount, absolute);
                        }
                    }
                }
            }
        }
    }

    private void completeQuest(UUID playerUUID, Quest quest, PlayerQuestProgress prog) {
        prog.setStatus(PlayerQuestProgress.Status.COMPLETED);
        prog.setCompletedAt(Instant.now());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("""
                    UPDATE player_quest_progress
                    SET status = 'COMPLETED', completed_at = NOW()
                    WHERE player_uuid = ? AND quest_id = ?
                    """,
                        ps -> {
                            ps.setString(1, playerUUID.toString());
                            ps.setString(2, quest.getId());
                        }
                );
                db.update("""
                    INSERT INTO player_quest_history (player_uuid, quest_id, status, started_at, completed_at)
                    VALUES (?, ?, 'COMPLETED', ?, NOW())
                    """,
                        ps -> {
                            ps.setString(1, playerUUID.toString());
                            ps.setString(2, quest.getId());
                            ps.setTimestamp(3, Timestamp.from(prog.getStartedAt()));
                        }
                );
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "クエスト完了保存中にエラー: " + e.getMessage(), e);
            }
        });

        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            RewardGiver.give(plugin, player, quest);
            player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + quest.getName() + "を完了しました");
        }
    }

    // ========== recordHistory ==========

    /**
     * ActiveQuestManager から呼ばれるクエスト履歴記録メソッド
     *
     * @param playerUUID  対象プレイヤーのUUID
     * @param quest       対象クエスト
     * @param completed   true=完了 / false=失敗
     * @param finalAmount 最終進捗数
     */
    public void recordHistory(UUID playerUUID, Quest quest, boolean completed, int finalAmount) {
        String status = completed ? "COMPLETED" : "FAILED";

        // started_at を progress テーブルから取得（なければ NOW() で代替）
        Instant startedAt = Instant.now();
        Map<String, PlayerQuestProgress> progMap = progressCache.get(playerUUID);
        if (progMap != null) {
            PlayerQuestProgress prog = progMap.get(quest.getId());
            if (prog != null && prog.getStartedAt() != null) {
                startedAt = prog.getStartedAt();
            }
        }
        final Instant finalStartedAt = startedAt;

        // DB書き込みは非同期化（メインスレッドのJDBC同期アクセスを避ける）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("""
                    UPDATE player_quest_progress
                    SET status = ?, completed_at = NOW()
                    WHERE player_uuid = ? AND quest_id = ?
                    """,
                        ps -> {
                            ps.setString(1, status);
                            ps.setString(2, playerUUID.toString());
                            ps.setString(3, quest.getId());
                        }
                );
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "recordHistory: progress更新中にエラー: " + e.getMessage(), e);
            }

            try {
                db.update("""
                    INSERT INTO player_quest_history
                        (player_uuid, quest_id, status, started_at, completed_at)
                    VALUES (?, ?, ?, ?, NOW())
                    """,
                        ps -> {
                            ps.setString(1, playerUUID.toString());
                            ps.setString(2, quest.getId());
                            ps.setString(3, status);
                            ps.setTimestamp(4, Timestamp.from(finalStartedAt));
                        }
                );
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING,
                        "recordHistory: history追記中にエラー: " + e.getMessage(), e);
            }
        });

        // 完了の場合はキャッシュ上のステータスも更新
        if (completed) {
            if (progMap != null) {
                PlayerQuestProgress prog = progMap.get(quest.getId());
                if (prog != null) {
                    prog.setStatus(PlayerQuestProgress.Status.COMPLETED);
                    prog.setCompletedAt(Instant.now());
                }
            }
        }

        plugin.getLogger().info("recordHistory: " + playerUUID + " / " + quest.getId()
                + " / " + status + " / progress=" + finalAmount);
    }

    // ========== 内部ヘルパー ==========

    /**
     * 進捗をDBへ非同期保存する。
     * 以前はメインスレッドで同期的にUPDATEしていたため、
     * WALK_DISTANCE/SWIM_DISTANCE のように高頻度で呼ばれる進捗が
     * プレイヤー数の増加とともにTPS低下の原因になっていた。
     */
    private void saveProgressAsync(PlayerQuestProgress prog) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.update("""
                    UPDATE player_quest_progress SET current_amount = ?
                    WHERE player_uuid = ? AND quest_id = ?
                    """,
                        ps -> {
                            ps.setInt(1, prog.getCurrentAmount());
                            ps.setString(2, prog.getPlayerUUID().toString());
                            ps.setString(3, prog.getQuestId());
                        }
                );
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "進捗保存中にエラー: " + e.getMessage(), e);
            }
        });
    }

    public boolean isQuestCompleted(UUID playerUUID, String questId) {
        try {
            return db.query(
                    "SELECT COUNT(*) FROM player_quest_history WHERE player_uuid = ? AND quest_id = ? AND status = 'COMPLETED'",
                    ps -> { ps.setString(1, playerUUID.toString()); ps.setString(2, questId); },
                    rs -> rs.next() && rs.getInt(1) > 0
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "クエスト完了確認中にエラー: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 直近の「成功」完了時刻を取得する。
     * 修正点: 以前は status を絞らず MAX(completed_at) を取っていたため、
     * FAILED（失敗）の記録でもクールダウンが発生してしまっていた。
     * 「成功時のみクールダウン開始」という仕様に合わせ、
     * status = 'COMPLETED' の記録のみを対象にする。
     */
    private Instant getLastCompletedTime(UUID playerUUID, String questId) throws SQLException {
        return db.query(
                "SELECT MAX(completed_at) FROM player_quest_history WHERE player_uuid = ? AND quest_id = ? AND status = 'COMPLETED'",
                ps -> { ps.setString(1, playerUUID.toString()); ps.setString(2, questId); },
                rs -> {
                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp(1);
                        return ts != null ? ts.toInstant() : null;
                    }
                    return null;
                }
        );
    }

    public void abandonQuest(Player player, String questId) {
        UUID uuid = player.getUniqueId();
        Map<String, PlayerQuestProgress> map = progressCache.get(uuid);
        if (map == null) return;
        PlayerQuestProgress prog = map.get(questId);
        if (prog == null || !prog.isActive()) return;

        map.remove(questId);
        try {
            db.update("DELETE FROM player_quest_progress WHERE player_uuid = ? AND quest_id = ?",
                    ps -> { ps.setString(1, uuid.toString()); ps.setString(2, questId); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "クエスト放棄保存中にエラー: " + e.getMessage(), e);
        }
    }

    public void acceptQuestAsParty(Party party, String questId, Player leader) {
        Quest quest = questCache.get(questId);
        if (quest == null || !quest.isPartyEnabled()) {
            leader.sendMessage(QuestPluginV2.PREFIX + "§c§lこのクエストはパーティーで受注できません");
            return;
        }
        for (UUID memberUUID : party.getMemberUUIDs()) {
            Player member = Bukkit.getPlayer(memberUUID);
            if (member == null) continue;
            AcceptResult result = acceptQuest(member, questId);
            if (result == AcceptResult.SUCCESS) {
                PlayerQuestProgress prog = getProgress(memberUUID, questId);
                if (prog != null) {
                    prog.setPartyId(party.getPartyId());
                    try {
                        db.update("UPDATE player_quest_progress SET party_id = ? WHERE player_uuid = ? AND quest_id = ?",
                                ps -> {
                                    ps.setString(1, party.getPartyId());
                                    ps.setString(2, memberUUID.toString());
                                    ps.setString(3, questId);
                                });
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "パーティーID保存中にエラー: " + e.getMessage(), e);
                    }
                    member.sendMessage(QuestPluginV2.PREFIX + "§a§l" + quest.getName() + "を受注しました");
                }
            }
        }
    }
}