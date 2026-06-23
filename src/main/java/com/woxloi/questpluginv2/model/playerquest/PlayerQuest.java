package com.woxloi.questpluginv2.model.playerquest;

import org.bukkit.Material;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 民間クエスト（プレイヤーが作成した「○○を△個持ってきて」依頼）。
 *
 * perPersonAmount は maxAcceptors を基準に publish 時点で固定する。
 * 後から受注人数が増減しても再分配はしない（仕様: 段階的に作るため、まずはシンプルな固定割りで実装）。
 */
public class PlayerQuest {

    private final int id;
    private final UUID creatorUUID;
    private final String creatorName;
    private final Material targetMaterial;
    private final int totalAmount;
    private final int maxAcceptors;
    private final int perPersonAmount;
    private final double rewardMoney;
    private boolean open;
    private final Instant createdAt;

    /** acceptorUUID -> 完了したかどうか */
    private final Map<UUID, Boolean> acceptors = new LinkedHashMap<>();

    public PlayerQuest(int id, UUID creatorUUID, String creatorName, Material targetMaterial,
                       int totalAmount, int maxAcceptors, int perPersonAmount,
                       double rewardMoney, boolean open, Instant createdAt) {
        this.id = id;
        this.creatorUUID = creatorUUID;
        this.creatorName = creatorName;
        this.targetMaterial = targetMaterial;
        this.totalAmount = totalAmount;
        this.maxAcceptors = maxAcceptors;
        this.perPersonAmount = perPersonAmount;
        this.rewardMoney = rewardMoney;
        this.open = open;
        this.createdAt = createdAt;
    }

    public int getId()                      { return id; }
    public UUID getCreatorUUID()             { return creatorUUID; }
    public String getCreatorName()           { return creatorName; }
    public Material getTargetMaterial()      { return targetMaterial; }
    public int getTotalAmount()              { return totalAmount; }
    public int getMaxAcceptors()             { return maxAcceptors; }
    public int getPerPersonAmount()          { return perPersonAmount; }
    public double getRewardMoney()           { return rewardMoney; }
    public boolean isOpen()                  { return open; }
    public void setOpen(boolean open)        { this.open = open; }
    public Instant getCreatedAt()            { return createdAt; }

    public Map<UUID, Boolean> getAcceptors() { return acceptors; }

    public int getAcceptedCount()   { return acceptors.size(); }
    public int getCompletedCount()  { return (int) acceptors.values().stream().filter(b -> b).count(); }
    public boolean isFull()         { return acceptors.size() >= maxAcceptors; }
    public boolean isAccepting(UUID uuid)  { return acceptors.containsKey(uuid); }
    public boolean hasCompleted(UUID uuid) { return Boolean.TRUE.equals(acceptors.get(uuid)); }

    /** 全員(受注済みの人)が完了しているか。1人も受注していない場合はfalse扱い（削除可否の判定用） */
    public boolean allAcceptorsCompleted() {
        return acceptors.values().stream().allMatch(b -> b);
    }
}