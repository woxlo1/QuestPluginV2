package com.woxloi.questpluginv2.model.quest;

import com.woxloi.questpluginv2.model.reward.QuestReward;

import java.util.ArrayList;
import java.util.List;

/**
 * クエスト定義モデル（V2拡張版）
 * V1 の QuestData に相当するフィールドを追加
 */
public class Quest {

    private final String id;
    private String name;
    private String description;
    private QuestType type;
    private String targetId;
    private int    requiredAmount;

    // メタ設定
    private String  prerequisiteQuestId;
    private long    cooldownSeconds;
    private boolean partyEnabled = true;
    private Integer partyMaxMembers;
    private boolean shareProgress   = false;
    private boolean shareCompletion = false;

    // V2 + V1 共通拡張
    private Long    timeLimitSeconds;   // タイムリミット（nullで無制限）
    private Integer maxLives;           // ライフ数（nullで無制限）
    private String  teleportWorld;
    private Double  teleportX, teleportY, teleportZ;
    private final List<String>      startCommands = new ArrayList<>();
    private final List<QuestReward> rewards       = new ArrayList<>();

    public Quest(String id, String name, String description, QuestType type,
                 String targetId, int requiredAmount) {
        this.id             = id;
        this.name           = name;
        this.description    = description;
        this.type           = type;
        this.targetId       = targetId;
        this.requiredAmount = requiredAmount;
    }

    // ---- ゲッター / セッター ----

    public String getId()                          { return id; }
    public String getName()                        { return name; }
    public void   setName(String v)                { this.name = v; }
    public String getDescription()                 { return description; }
    public void   setDescription(String v)         { this.description = v; }
    public QuestType getType()                     { return type; }
    public void   setType(QuestType v)             { this.type = v; }
    public String getTargetId()                    { return targetId; }
    public void   setTargetId(String v)            { this.targetId = v; }
    public int    getRequiredAmount()              { return requiredAmount; }
    public void   setRequiredAmount(int v)         { this.requiredAmount = v; }
    public String getPrerequisiteQuestId()         { return prerequisiteQuestId; }
    public void   setPrerequisiteQuestId(String v) { this.prerequisiteQuestId = v; }
    public long   getCooldownSeconds()             { return cooldownSeconds; }
    public void   setCooldownSeconds(long v)       { this.cooldownSeconds = v; }
    public boolean isPartyEnabled()               { return partyEnabled; }
    public void   setPartyEnabled(boolean v)       { this.partyEnabled = v; }
    public Integer getPartyMaxMembers()            { return partyMaxMembers; }
    public void   setPartyMaxMembers(Integer v)    { this.partyMaxMembers = v; }
    public boolean isShareProgress()              { return shareProgress; }
    public void   setShareProgress(boolean v)      { this.shareProgress = v; }
    public boolean isShareCompletion()            { return shareCompletion; }
    public void   setShareCompletion(boolean v)    { this.shareCompletion = v; }
    public Long   getTimeLimitSeconds()            { return timeLimitSeconds; }
    public void   setTimeLimitSeconds(Long v)      { this.timeLimitSeconds = v; }
    public Integer getMaxLives()                   { return maxLives; }
    public void   setMaxLives(Integer v)           { this.maxLives = v; }
    public String getTeleportWorld()               { return teleportWorld; }
    public void   setTeleportWorld(String v)       { this.teleportWorld = v; }
    public Double getTeleportX()                   { return teleportX; }
    public void   setTeleportX(Double v)           { this.teleportX = v; }
    public Double getTeleportY()                   { return teleportY; }
    public void   setTeleportY(Double v)           { this.teleportY = v; }
    public Double getTeleportZ()                   { return teleportZ; }
    public void   setTeleportZ(Double v)           { this.teleportZ = v; }
    public List<String>      getStartCommands()    { return startCommands; }
    public List<QuestReward> getRewards()          { return rewards; }
    public void addReward(QuestReward r)           { rewards.add(r); }

    /** 目標の表示文 */
    public String getObjectiveDescription() {
        return switch (type) {
            case KILL_MOB        -> (targetId != null ? targetId : "Mob") + " を " + requiredAmount + " 体討伐";
            case KILL_PLAYER     -> "プレイヤーを " + requiredAmount + " 人討伐";
            case BREAK_BLOCK,
                 BREAK_BLOCK_TYPE -> (targetId != null ? targetId : "ブロック") + " を " + requiredAmount + " 個破壊";
            case COLLECT_ITEM    -> (targetId != null ? targetId : "アイテム") + " を " + requiredAmount + " 個収集";
            case WALK_DISTANCE   -> requiredAmount + " ブロック歩く";
            case SWIM_DISTANCE   -> requiredAmount + " ブロック泳ぐ";
            case CRAFT_ITEM      -> (targetId != null ? targetId : "アイテム") + " を " + requiredAmount + " 個クラフト";
            case FISHING         -> "釣りで " + requiredAmount + " 個獲得";
            case FARMING         -> (targetId != null ? targetId : "作物") + " を " + requiredAmount + " 個収穫";
            case ENCHANT         -> requiredAmount + " 回エンチャントを付与";
            case TRADE           -> "村人と " + requiredAmount + " 回取引";
            case TAME            -> (targetId != null ? targetId : "エンティティ") + " を " + requiredAmount + " 体テイム";
            case PLACE_BLOCK     -> (targetId != null ? targetId : "ブロック") + " を " + requiredAmount + " 個設置";
            case TAKE_DAMAGE     -> requiredAmount + " ダメージを受ける";
            case LEVEL_UP        -> "レベル " + requiredAmount + " に到達";
            case BOSS_KILL       -> (targetId != null ? targetId : "ボス") + " を " + requiredAmount + " 体討伐";
            case CUSTOM_EVENT    -> "イベント「" + targetId + "」を達成";
            default              -> targetId + " x" + requiredAmount;
        };
    }
}