package com.woxloi.questpluginv2.model.quest;

import java.time.Instant;
import java.util.UUID;

/**
 * プレイヤーのクエスト進捗モデル
 * MySQLのplayer_quest_progressテーブルに1行対応
 */
public class PlayerQuestProgress {

    public enum Status {
        ACTIVE,     // 受注中
        COMPLETED,  // 完了
        FAILED      // 失敗（期限切れ等）
    }

    private final UUID playerUUID;
    private final String questId;
    private int currentAmount;
    private Status status;
    private final Instant startedAt;
    private Instant completedAt;
    /** パーティー経由で受注した場合のパーティーID */
    private String partyId;

    public PlayerQuestProgress(UUID playerUUID, String questId) {
        this.playerUUID = playerUUID;
        this.questId = questId;
        this.currentAmount = 0;
        this.status = Status.ACTIVE;
        this.startedAt = Instant.now();
    }

    public PlayerQuestProgress(UUID playerUUID, String questId, int currentAmount,
                                Status status, Instant startedAt, Instant completedAt, String partyId) {
        this.playerUUID = playerUUID;
        this.questId = questId;
        this.currentAmount = currentAmount;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.partyId = partyId;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public String getQuestId() { return questId; }
    public int getCurrentAmount() { return currentAmount; }
    public void setCurrentAmount(int currentAmount) { this.currentAmount = currentAmount; }
    public void addProgress(int amount) { this.currentAmount += amount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }

    public boolean isCompleted() { return status == Status.COMPLETED; }
    public boolean isActive() { return status == Status.ACTIVE; }
}
