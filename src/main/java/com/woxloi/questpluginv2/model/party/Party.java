package com.woxloi.questpluginv2.model.party;

import java.time.Instant;
import java.util.*;

/**
 * パーティーモデル
 * MySQLのpartiesテーブルおよびparty_membersテーブルに対応
 */
public class Party {

    private final String partyId;
    private UUID leaderUUID;
    private final Map<UUID, PartyRole> members = new LinkedHashMap<>();
    /** 招待中のプレイヤーと招待時刻のマップ */
    private final Map<UUID, Instant> pendingInvites = new HashMap<>();
    private int maxSize;
    private final Instant createdAt;

    public Party(String partyId, UUID leaderUUID, int maxSize) {
        this.partyId = partyId;
        this.leaderUUID = leaderUUID;
        this.maxSize = maxSize;
        this.createdAt = Instant.now();
        members.put(leaderUUID, PartyRole.LEADER);
    }

    public Party(String partyId, UUID leaderUUID, int maxSize, Instant createdAt) {
        this.partyId = partyId;
        this.leaderUUID = leaderUUID;
        this.maxSize = maxSize;
        this.createdAt = createdAt;
        members.put(leaderUUID, PartyRole.LEADER);
    }

    // ---- メンバー操作 ----

    public boolean addMember(UUID uuid) {
        if (members.size() >= maxSize) return false;
        members.put(uuid, PartyRole.MEMBER);
        pendingInvites.remove(uuid);
        return true;
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public boolean isLeader(UUID uuid) {
        return leaderUUID.equals(uuid);
    }

    public PartyRole getRole(UUID uuid) {
        return members.getOrDefault(uuid, null);
    }

    /**
     * リーダーを他のメンバーへ移譲する
     */
    public boolean transferLeader(UUID newLeader) {
        if (!members.containsKey(newLeader)) return false;
        members.put(leaderUUID, PartyRole.MEMBER);
        members.put(newLeader, PartyRole.LEADER);
        leaderUUID = newLeader;
        return true;
    }

    /**
     * リーダーが退出した場合、次のメンバーへ自動移譲
     * メンバーが0人になったらfalseを返す（解散）
     */
    public boolean promoteNextLeader() {
        members.remove(leaderUUID);
        if (members.isEmpty()) return false;
        UUID next = members.keySet().iterator().next();
        members.put(next, PartyRole.LEADER);
        leaderUUID = next;
        return true;
    }

    // ---- 招待管理 ----

    public void addInvite(UUID uuid) {
        pendingInvites.put(uuid, Instant.now());
    }

    public boolean hasInvite(UUID uuid) {
        return pendingInvites.containsKey(uuid);
    }

    public Instant getInviteTime(UUID uuid) {
        return pendingInvites.get(uuid);
    }

    public void removeInvite(UUID uuid) {
        pendingInvites.remove(uuid);
    }

    public void cleanExpiredInvites(long timeoutSeconds) {
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);
        pendingInvites.entrySet().removeIf(e -> e.getValue().isBefore(threshold));
    }

    // ---- ゲッター / セッター ----

    public String getPartyId() { return partyId; }
    public UUID getLeaderUUID() { return leaderUUID; }
    public Map<UUID, PartyRole> getMembers() { return Collections.unmodifiableMap(members); }
    public Set<UUID> getMemberUUIDs() { return Collections.unmodifiableSet(members.keySet()); }
    public int getSize() { return members.size(); }
    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isFull() { return members.size() >= maxSize; }
}
