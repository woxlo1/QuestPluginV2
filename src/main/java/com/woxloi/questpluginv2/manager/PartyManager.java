package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.model.party.Party;
import com.woxloi.questpluginv2.model.party.PartyRole;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * パーティー管理クラス（V2 拡張版）
 *
 * 修正点: DB書き込み（acceptQuest以外のparty関連INSERT/UPDATE/DELETE）を
 * 非同期化した。以前は全て同期実行されており、特に sendPartyChat() は
 * チャット発生頻度に応じてメインスレッドでJDBC書き込みが走っていた。
 * キャッシュ（partyCache / playerPartyMap）の更新は即時にメインスレッドで
 * 行い、DBへの反映だけを非同期に回すことで、見た目の応答性を変えずに
 * TPSへの影響を抑えている。
 */
public class PartyManager {

    private final QuestPluginV2 plugin;
    private final DatabaseManager db;

    private final Map<String, Party> partyCache   = new ConcurrentHashMap<>();
    private final Map<UUID, String>  playerPartyMap = new ConcurrentHashMap<>();

    public PartyManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.db     = plugin.getDatabaseManager();
        loadAllParties();
    }

    private void loadAllParties() {
        try {
            db.query("SELECT * FROM parties", null, rs -> {
                while (rs.next()) {
                    String partyId   = rs.getString("party_id");
                    UUID   leaderUUID = UUID.fromString(rs.getString("leader_uuid"));
                    int    maxSize   = rs.getInt("max_size");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    Party party = new Party(partyId, leaderUUID, maxSize, createdAt.toInstant());
                    partyCache.put(partyId, party);
                }
                return null;
            });
            db.query("SELECT * FROM party_members", null, rs -> {
                while (rs.next()) {
                    String    partyId    = rs.getString("party_id");
                    UUID      memberUUID = UUID.fromString(rs.getString("member_uuid"));
                    PartyRole role       = PartyRole.valueOf(rs.getString("role"));
                    Party party = partyCache.get(partyId);
                    if (party != null) {
                        if (role == PartyRole.MEMBER) party.addMember(memberUUID);
                        playerPartyMap.put(memberUUID, partyId);
                    }
                }
                return null;
            });
            plugin.getLogger().info(partyCache.size() + " 件のパーティーを読み込みました。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "パーティー読み込み中にエラー: " + e.getMessage(), e);
        }
    }

    private void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    // ========== パーティー操作 ==========

    public Party createParty(Player leader) {
        if (getPlayerParty(leader.getUniqueId()) != null) return null;
        String partyId = UUID.randomUUID().toString();
        int    defaultMax = plugin.getConfig().getInt("party.default-max-size", 6);
        Party  party = new Party(partyId, leader.getUniqueId(), defaultMax);

        // パーティー作成は直後にIDをプレイヤーへ提示するため、ここは同期のままにする
        // （結果がすぐ必要で、頻度も低いため非同期化のメリットが薄い）。
        try {
            db.update("INSERT INTO parties (party_id, leader_uuid, max_size) VALUES (?, ?, ?)",
                    ps -> { ps.setString(1, partyId); ps.setString(2, leader.getUniqueId().toString()); ps.setInt(3, defaultMax); });
            db.update("INSERT INTO party_members (party_id, member_uuid, role) VALUES (?, ?, 'LEADER')",
                    ps -> { ps.setString(1, partyId); ps.setString(2, leader.getUniqueId().toString()); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "パーティー作成中にエラー: " + e.getMessage(), e);
            return null;
        }
        partyCache.put(partyId, party);
        playerPartyMap.put(leader.getUniqueId(), partyId);
        return party;
    }

    public boolean invitePlayer(Party party, Player target) {
        if (party.isFull() || playerPartyMap.containsKey(target.getUniqueId())) return false;
        party.addInvite(target.getUniqueId());
        return true;
    }

    /**
     * 招待が届いているパーティーを検索する（CommandAPI の /party accept で使用）
     */
    public Party findPendingInvite(UUID playerUUID) {
        long timeout = plugin.getConfig().getLong("party.invite-timeout-seconds", 60);
        for (Party party : partyCache.values()) {
            party.cleanExpiredInvites(timeout);
            if (party.hasInvite(playerUUID)) return party;
        }
        return null;
    }

    public boolean acceptInvite(Player player, String partyId) {
        Party party = partyCache.get(partyId);
        if (party == null) return false;
        long timeout = plugin.getConfig().getLong("party.invite-timeout-seconds", 60);
        party.cleanExpiredInvites(timeout);
        if (!party.hasInvite(player.getUniqueId())) return false;
        if (party.isFull()) return false;
        if (!party.addMember(player.getUniqueId())) return false;

        // メンバー追加自体は頻度が低く、失敗時はロールバックしたいため同期のまま
        try {
            db.update("INSERT INTO party_members (party_id, member_uuid, role) VALUES (?, ?, 'MEMBER')",
                    ps -> { ps.setString(1, partyId); ps.setString(2, player.getUniqueId().toString()); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "パーティー参加保存中にエラー: " + e.getMessage(), e);
            return false;
        }
        playerPartyMap.put(player.getUniqueId(), partyId);
        return true;
    }

    public void leaveParty(Player player) {
        String partyId = playerPartyMap.get(player.getUniqueId());
        if (partyId == null) return;
        Party party = partyCache.get(partyId);
        if (party == null) return;

        boolean wasLeader = party.isLeader(player.getUniqueId());
        UUID leavingUUID = player.getUniqueId();
        playerPartyMap.remove(leavingUUID);

        runAsync(() -> {
            try {
                db.update("DELETE FROM party_members WHERE party_id = ? AND member_uuid = ?",
                        ps -> { ps.setString(1, partyId); ps.setString(2, leavingUUID.toString()); });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "パーティー退出保存中にエラー: " + e.getMessage(), e);
            }
        });

        if (wasLeader) {
            if (!party.promoteNextLeader()) { dissolveParty(partyId); return; }
            updateLeaderInDbAsync(party);
            broadcastToParty(party, QuestPluginV2.PREFIX + "§e§l" + player.getName()
                    + "§7§lが退出しました 新リーダー: §e§l"
                    + Bukkit.getOfflinePlayer(party.getLeaderUUID()).getName());
        } else {
            party.removeMember(leavingUUID);
            broadcastToParty(party, QuestPluginV2.PREFIX + "§e§l" + player.getName() + "§7§lが退出しました");
        }
    }

    public void kickMember(Party party, UUID target) {
        party.removeMember(target);
        playerPartyMap.remove(target);
        String partyId = party.getPartyId();
        runAsync(() -> {
            try {
                db.update("DELETE FROM party_members WHERE party_id = ? AND member_uuid = ?",
                        ps -> { ps.setString(1, partyId); ps.setString(2, target.toString()); });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "キック保存中にエラー: " + e.getMessage(), e);
            }
        });
    }

    public void dissolveParty(String partyId) {
        Party party = partyCache.remove(partyId);
        if (party == null) return;
        for (UUID uuid : party.getMemberUUIDs()) {
            playerPartyMap.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(QuestPluginV2.PREFIX + "§c§lパーティーが解散されました");
        }
        runAsync(() -> {
            try {
                db.update("DELETE FROM parties WHERE party_id = ?", ps -> ps.setString(1, partyId));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "パーティー解散保存中にエラー: " + e.getMessage(), e);
            }
        });
    }

    public boolean transferLeader(Party party, UUID newLeader) {
        if (!party.transferLeader(newLeader)) return false;
        updateLeaderInDbAsync(party);
        return true;
    }

    private void updateLeaderInDbAsync(Party party) {
        String partyId = party.getPartyId();
        UUID leaderUUID = party.getLeaderUUID();
        runAsync(() -> {
            try {
                db.update("UPDATE parties SET leader_uuid = ? WHERE party_id = ?",
                        ps -> { ps.setString(1, leaderUUID.toString()); ps.setString(2, partyId); });
                db.update("UPDATE party_members SET role = 'LEADER' WHERE party_id = ? AND member_uuid = ?",
                        ps -> { ps.setString(1, partyId); ps.setString(2, leaderUUID.toString()); });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "リーダー更新中にエラー: " + e.getMessage(), e);
            }
        });
    }

    public void sendPartyChat(Party party, Player sender, String message) {
        String chatPrefix = plugin.getConfig().getString("party.chat-prefix", "§a§l[§6§lQuestPartyPlugin§d§lV2§a§l]§r");
        String formatted  = chatPrefix + "§a§l" + sender.getName() + "§7§l: §f" + message;
        for (UUID uuid : party.getMemberUUIDs()) {
            Player m = Bukkit.getPlayer(uuid);
            if (m != null) m.sendMessage(formatted);
        }

        String partyId = party.getPartyId();
        UUID senderUUID = sender.getUniqueId();
        runAsync(() -> {
            try {
                db.update("INSERT INTO party_chat_log (party_id, sender_uuid, message) VALUES (?, ?, ?)",
                        ps -> { ps.setString(1, partyId); ps.setString(2, senderUUID.toString()); ps.setString(3, message); });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "チャットログ保存中にエラー: " + e.getMessage(), e);
            }
        });
    }

    public void broadcastToParty(Party party, String message) {
        for (UUID uuid : party.getMemberUUIDs()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    // ========== ゲッター ==========

    public Party   getParty(String partyId)        { return partyCache.get(partyId); }
    public Party   getPlayerParty(UUID playerUUID) {
        String id = playerPartyMap.get(playerUUID);
        return id == null ? null : partyCache.get(id);
    }
    public boolean isInParty(UUID playerUUID)      { return playerPartyMap.containsKey(playerUUID); }
}