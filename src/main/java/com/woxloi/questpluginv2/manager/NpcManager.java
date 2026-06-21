package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.model.npc.QuestNpc;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Citizens等に依存しない独自NPC管理クラス。
 *
 * 任意のEntityType（LivingEntityのサブタイプ）をそのままスポーンし、
 * AIを無効化・無敵化した上で PersistentDataContainer に DB上のNPC IDを
 * 書き込んで紐付ける。プラグイン側でスポーン管理しているため、
 * ワールドのオートセーブで増殖しないよう setPersistent(false) にしている
 * （リロード/再起動時は loadAll() で毎回スポーンし直す）。
 *
 * 修正点: 以前は Villager 固定でスポーンしていたが、村人以外の任意のMobを
 * NPCとして使えるよう EntityType を保持し、LivingEntity 汎用でスポーンする
 * ように変更した。EntityType が LivingEntity を生成可能な種別かどうかは
 * isSpawnableLivingType() で検証する。
 */
public class NpcManager {

    private static final String PDC_KEY = "questpluginv2_npc_id";

    private final QuestPluginV2 plugin;
    private final DatabaseManager db;
    private final NamespacedKey npcIdKey;

    /** npcId -> データ */
    private final Map<Integer, QuestNpc> npcs = new ConcurrentHashMap<>();
    /** entityUUID -> npcId（クリック時の逆引き用） */
    private final Map<UUID, Integer> entityToNpcId = new ConcurrentHashMap<>();

    public NpcManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.npcIdKey = new NamespacedKey(plugin, PDC_KEY);
        loadAll();
    }

    // ============================================================
    //  読み込み・スポーン
    // ============================================================

    private void loadAll() {
        try {
            db.query("SELECT * FROM quest_npcs", null, rs -> {
                while (rs.next()) {
                    EntityType type = parseEntityType(rs.getString("entity_type"));
                    QuestNpc npc = new QuestNpc(
                            rs.getInt("id"),
                            rs.getString("name"),
                            type,
                            rs.getString("world"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getString("quest_id"),
                            rs.getString("greeting")
                    );
                    npcs.put(npc.getId(), npc);
                }
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC読み込み中にエラー: " + e.getMessage(), e);
            return;
        }

        for (QuestNpc npc : npcs.values()) {
            spawnEntity(npc);
        }
        plugin.getLogger().info(npcs.size() + "件のNPCを読み込みました。");
    }

    /**
     * DBに保存された文字列からEntityTypeへ変換する。
     * 不明な値・カラム未設定（古いレコード）の場合は VILLAGER にフォールバックする。
     */
    private EntityType parseEntityType(String raw) {
        if (raw == null || raw.isBlank()) return EntityType.VILLAGER;
        try {
            EntityType type = EntityType.valueOf(raw.toUpperCase());
            if (!isSpawnableLivingType(type)) {
                plugin.getLogger().warning("NPCのentity_typeがLivingEntityではありません。VILLAGERにフォールバックします: " + raw);
                return EntityType.VILLAGER;
            }
            return type;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("不明なentity_typeです。VILLAGERにフォールバックします: " + raw);
            return EntityType.VILLAGER;
        }
    }

    /**
     * 指定したEntityTypeがNPCとしてスポーン可能（LivingEntityのサブタイプ）かどうかを判定する。
     * ARMOR_STAND等の非LivingEntityや、PLAYER・UNKNOWN等の特殊な値を除外する。
     */
    public static boolean isSpawnableLivingType(EntityType type) {
        if (type == null) return false;
        if (type == EntityType.PLAYER || type == EntityType.UNKNOWN) return false;
        Class<?> entityClass = type.getEntityClass();
        return entityClass != null && LivingEntity.class.isAssignableFrom(entityClass);
    }

    private void spawnEntity(QuestNpc npc) {
        var world = Bukkit.getWorld(npc.getWorld());
        if (world == null) {
            plugin.getLogger().warning(npc.getId() + "のワールドが見つかりません: " + npc.getWorld());
            return;
        }
        Location loc = new Location(world, npc.getX(), npc.getY(), npc.getZ(), npc.getYaw(), 0f);

        EntityType type = isSpawnableLivingType(npc.getEntityType()) ? npc.getEntityType() : EntityType.VILLAGER;

        LivingEntity entity = (LivingEntity) world.spawnEntity(loc, type);
        entity.setCustomName(npc.getName());
        entity.setCustomNameVisible(true);
        entity.setAI(false);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setCollidable(false);
        entity.setPersistent(false); // 自前で再スポーンするので保存させない
        entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.INTEGER, npc.getId());

        // Flying系・水生Mob等が床に沈む/浮く事故を避けるため、重力は残したままにする。
        // AI無効化のみで「棒立ち」を実現する（敵対Mobであっても攻撃モーション等は発生しない）。

        npc.setEntityUUID(entity.getUniqueId());
        entityToNpcId.put(entity.getUniqueId(), npc.getId());
    }

    private void despawnEntity(QuestNpc npc) {
        if (npc.getEntityUUID() == null) return;
        var entity = Bukkit.getEntity(npc.getEntityUUID());
        if (entity != null) entity.remove();
        entityToNpcId.remove(npc.getEntityUUID());
        npc.setEntityUUID(null);
    }

    /** プラグイン無効化時に呼ぶ。スポーンした全エンティティを消す。 */
    public void despawnAll() {
        for (QuestNpc npc : npcs.values()) {
            despawnEntity(npc);
        }
    }

    // ============================================================
    //  作成・削除・設定
    // ============================================================

    /**
     * プレイヤーの現在地にNPCを作成する（常にVILLAGERとして作成）。失敗時はnullを返す。
     * 村人以外のMobにしたい場合は、作成後に setEntityType() で変更する。
     */
    public QuestNpc createNpc(Player creator, String name) {
        return createNpc(creator, name, EntityType.VILLAGER);
    }

    /**
     * プレイヤーの現在地に指定したEntityTypeでNPCを作成する。失敗時はnullを返す。
     *
     * @param creator 作成者
     * @param name    NPC名
     * @param type    スポーンするエンティティ種別（LivingEntityのサブタイプである必要がある）
     */
    public QuestNpc createNpc(Player creator, String name, EntityType type) {
        if (!isSpawnableLivingType(type)) {
            plugin.getLogger().warning("NPC作成に失敗: スポーン不可能なentity_typeです: " + type);
            return null;
        }

        Location loc = creator.getLocation();
        try {
            db.update(
                    "INSERT INTO quest_npcs (name, entity_type, world, x, y, z, yaw) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ps -> {
                        ps.setString(1, name);
                        ps.setString(2, type.name());
                        ps.setString(3, loc.getWorld().getName());
                        ps.setDouble(4, loc.getX());
                        ps.setDouble(5, loc.getY());
                        ps.setDouble(6, loc.getZ());
                        ps.setFloat(7, loc.getYaw());
                    }
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC作成中にエラー: " + e.getMessage(), e);
            return null;
        }

        int id;
        try {
            Integer lastId = db.query("SELECT LAST_INSERT_ID()", null,
                    rs -> rs.next() ? rs.getInt(1) : null);
            if (lastId == null) return null;
            id = lastId;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPCID取得中にエラー: " + e.getMessage(), e);
            return null;
        }

        QuestNpc npc = new QuestNpc(id, name, type, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), null, null);
        npcs.put(id, npc);
        spawnEntity(npc);
        return npc;
    }

    public boolean removeNpc(int id) {
        QuestNpc npc = npcs.remove(id);
        if (npc == null) return false;
        despawnEntity(npc);
        try {
            db.update("DELETE FROM quest_npcs WHERE id = ?", ps -> ps.setInt(1, id));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC削除中にエラー: " + e.getMessage(), e);
            return false;
        }
        return true;
    }

    public boolean setQuest(int id, String questId) {
        QuestNpc npc = npcs.get(id);
        if (npc == null) return false;
        npc.setQuestId(questId);
        return persistField(id, "quest_id", questId);
    }

    public boolean setGreeting(int id, String greeting) {
        QuestNpc npc = npcs.get(id);
        if (npc == null) return false;
        npc.setGreeting(greeting);
        return persistField(id, "greeting", greeting);
    }

    /**
     * NPCのエンティティ種別を変更する。既存エンティティを消して新しい種別で再スポーンする。
     *
     * @return 成功したか（NPCが存在しない、または type がLivingEntityでない場合はfalse）
     */
    public boolean setEntityType(int id, EntityType type) {
        QuestNpc npc = npcs.get(id);
        if (npc == null) return false;
        if (!isSpawnableLivingType(type)) return false;

        try {
            db.update("UPDATE quest_npcs SET entity_type = ? WHERE id = ?",
                    ps -> { ps.setString(1, type.name()); ps.setInt(2, id); });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPCタイプ更新中にエラー: " + e.getMessage(), e);
            return false;
        }

        despawnEntity(npc);
        npc.setEntityType(type);
        spawnEntity(npc);
        return true;
    }

    private boolean persistField(int id, String column, String value) {
        try {
            db.update("UPDATE quest_npcs SET " + column + " = ? WHERE id = ?",
                    ps -> { ps.setString(1, value); ps.setInt(2, id); });
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC更新中にエラー: " + e.getMessage(), e);
            return false;
        }
    }

    /** NPCを現在地にテレポートさせ直す（座標更新+再スポーン） */
    public boolean teleportNpcHere(int id, Player player) {
        QuestNpc npc = npcs.get(id);
        if (npc == null) return false;
        despawnEntity(npc);

        Location loc = player.getLocation();
        try {
            db.update("UPDATE quest_npcs SET world=?, x=?, y=?, z=?, yaw=? WHERE id=?",
                    ps -> {
                        ps.setString(1, loc.getWorld().getName());
                        ps.setDouble(2, loc.getX());
                        ps.setDouble(3, loc.getY());
                        ps.setDouble(4, loc.getZ());
                        ps.setFloat(5, loc.getYaw());
                        ps.setInt(6, id);
                    });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC移動中にエラー: " + e.getMessage(), e);
            return false;
        }

        QuestNpc moved = new QuestNpc(id, npc.getName(), npc.getEntityType(), loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), npc.getQuestId(), npc.getGreeting());
        npcs.put(id, moved);
        spawnEntity(moved);
        return true;
    }

    // ============================================================
    //  クエリ
    // ============================================================

    public QuestNpc getNpc(int id) { return npcs.get(id); }
    public Collection<QuestNpc> getAllNpcs() { return Collections.unmodifiableCollection(npcs.values()); }

    /** エンティティUUIDからNPCを逆引きする。NPCでなければnull */
    public QuestNpc getNpcByEntity(UUID entityUUID) {
        Integer id = entityToNpcId.get(entityUUID);
        return id == null ? null : npcs.get(id);
    }
}