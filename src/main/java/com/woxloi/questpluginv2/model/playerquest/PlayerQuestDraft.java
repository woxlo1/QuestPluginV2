package com.woxloi.questpluginv2.model.playerquest;

import org.bukkit.Material;
import java.util.UUID;

/**
 * 民間クエスト作成中のドラフト（DBには保存せず、メモリ上のみで保持する）。
 * /quest player create の開始から publish までの間だけ存在する。
 */
public class PlayerQuestDraft {

    private final UUID creatorUUID;
    private final String creatorName;

    private Material targetMaterial;
    private int totalAmount = 0;
    private int maxAcceptors = 1;
    private double rewardMoney = 0;

    public PlayerQuestDraft(UUID creatorUUID, String creatorName) {
        this.creatorUUID = creatorUUID;
        this.creatorName = creatorName;
    }

    public UUID getCreatorUUID()              { return creatorUUID; }
    public String getCreatorName()            { return creatorName; }
    public Material getTargetMaterial()       { return targetMaterial; }
    public void setTargetMaterial(Material m) { this.targetMaterial = m; }
    public int getTotalAmount()               { return totalAmount; }
    public void setTotalAmount(int v)         { this.totalAmount = v; }
    public int getMaxAcceptors()              { return maxAcceptors; }
    public void setMaxAcceptors(int v)        { this.maxAcceptors = v; }
    public double getRewardMoney()            { return rewardMoney; }
    public void setRewardMoney(double v)      { this.rewardMoney = v; }

    /** 1人あたりの必要数（切り上げ）。プレビュー表示に使う。 */
    public int getPerPersonAmount() {
        if (maxAcceptors <= 0) return totalAmount;
        return (totalAmount + maxAcceptors - 1) / maxAcceptors;
    }

    public boolean isReadyToPublish() {
        return targetMaterial != null && targetMaterial.isItem()
                && totalAmount > 0 && maxAcceptors > 0;
    }
}