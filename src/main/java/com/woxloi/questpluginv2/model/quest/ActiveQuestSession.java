package com.woxloi.questpluginv2.model.quest;

import com.woxloi.questpluginv2.util.CountdownTimer;
import com.woxloi.questpluginv2.util.QuestScoreboard;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;
import org.bukkit.inventory.ItemStack;

/**
 * 1プレイヤーの「クエスト進行中」状態を保持するデータクラス
 * V1 の ActiveQuestManager.PlayerQuestData に相当
 */
public class ActiveQuestSession {

    private final Quest          quest;
    private final long           startTime;
    private int                  progress       = 0;
    private int                  deathCount     = 0;
    private final BossBar        bossBar;
    private final CountdownTimer         timer;
    private final QuestScoreboard scoreboard;
    private Location             originalLocation;
    private ItemStack[]          inventoryBackup;
    private ItemStack[]          armorBackup;
    private ItemStack            offHandBackup;
    /** パーティーIDがあればここに格納 */
    private String               partyId;

    public ActiveQuestSession(Quest quest, long startTime,
                              BossBar bossBar, CountdownTimer timer,
                              QuestScoreboard scoreboard,
                              Location originalLocation) {
        this.quest            = quest;
        this.startTime        = startTime;
        this.bossBar          = bossBar;
        this.timer            = timer;
        this.scoreboard       = scoreboard;
        this.originalLocation = originalLocation.clone();
    }

    // ---- ゲッター / セッター ----

    public Quest           getQuest()              { return quest; }
    public long            getStartTime()           { return startTime; }
    public int             getProgress()            { return progress; }
    public void            setProgress(int p)       { this.progress = p; }
    public void            addProgress(int amount)  { this.progress += amount; }
    public int             getDeathCount()          { return deathCount; }
    public void            setDeathCount(int d)     { this.deathCount = d; }
    public void            incrementDeathCount()    { this.deathCount++; }
    public BossBar         getBossBar()             { return bossBar; }
    public CountdownTimer  getTimer()               { return timer; }
    public QuestScoreboard getScoreboard()          { return scoreboard; }
    public Location        getOriginalLocation()    { return originalLocation; }
    public void            setOriginalLocation(Location l) { this.originalLocation = l.clone(); }
    public ItemStack[]     getInventoryBackup()     { return inventoryBackup; }
    public void            setInventoryBackup(ItemStack[] b) { this.inventoryBackup = b; }
    public ItemStack[]     getArmorBackup()         { return armorBackup; }
    public void            setArmorBackup(ItemStack[] b)     { this.armorBackup = b; }
    public ItemStack       getOffHandBackup()       { return offHandBackup; }
    public void            setOffHandBackup(ItemStack b)     { this.offHandBackup = b; }
    public String          getPartyId()             { return partyId; }
    public void            setPartyId(String id)   { this.partyId = id; }
}