package com.woxloi.questpluginv2.model.npc;

import org.bukkit.entity.Villager;

import java.util.UUID;

/**
 * 独自NPCの1件分のデータ。
 * Citizens等の外部プラグインに依存せず、Villagerエンティティを
 * 自前でスポーン・管理するための保持データ。
 */
public class QuestNpc {

    private final int id;
    private String name;
    private String world;
    private double x, y, z;
    private float yaw;
    private String questId;     // null可。設定されていなければ挨拶のみ表示
    private String greeting;

    /** スポーン中のエンティティUUID。未スポーン時はnull */
    private UUID entityUUID;

    public QuestNpc(int id, String name, String world, double x, double y, double z,
                    float yaw, String questId, String greeting) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.questId = questId;
        this.greeting = greeting;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public String getQuestId() { return questId; }
    public void setQuestId(String questId) { this.questId = questId; }
    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
    public UUID getEntityUUID() { return entityUUID; }
    public void setEntityUUID(UUID uuid) { this.entityUUID = uuid; }
}