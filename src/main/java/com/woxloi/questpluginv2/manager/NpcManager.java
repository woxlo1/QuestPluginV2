package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.model.npc.QuestNpc;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Citizens等に依存しない独自NPC管理クラス。
 *
 * Villagerエンティティをそのままスポーンし、AIを無効化・無敵化した上で
 * PersistentDataContainer に DB上のNPC IDを書き込んで紐付ける。
 * プラグイン側でスポーン管理しているため、ワールドのオートセーブで
 * 増殖しないよう Villager#setPersistent(false) にしている
 * （リロード/再起動時は loadAll() で毎回スポーンし直す）。
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
                    QuestNpc npc = new QuestNpc(
                            rs.getInt("id"),
                            rs.getString("name"),
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

    private void spawnEntity(QuestNpc npc) {
        var world = Bukkit.getWorld(npc.getWorld());
        if (world == null) {
            plugin.getLogger().warning( + npc.getId() + "のワールドが見つかりません: " + npc.getWorld());
            return;
        }
        Location loc = new Location(world, npc.getX(), npc.getY(), npc.getZ(), npc.getYaw(), 0f);

        Villager villager = world.spawn(loc, Villager.class, v -> {
            v.setCustomName(npc.getName());
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setPersistent(false); // 自前で再スポーンするので保存させない
            v.getPersistentDataContainer().set(npcIdKey, PersistentDataType.INTEGER, npc.getId());
        });

        npc.setEntityUUID(villager.getUniqueId());
        entityToNpcId.put(villager.getUniqueId(), npc.getId());
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

    /** プレイヤーの現在地にNPCを作成する。失敗時はnullを返す。 */
    public QuestNpc createNpc(Player creator, String name) {
        Location loc = creator.getLocation();
        int id;
        try {
            id = db.query(
                    "INSERT INTO quest_npcs (name, world, x, y, z, yaw) VALUES (?, ?, ?, ?, ?, ?)",
                    ps -> {
                        ps.setString(1, name);
                        ps.setString(2, loc.getWorld().getName());
                        ps.setDouble(3, loc.getX());
                        ps.setDouble(4, loc.getY());
                        ps.setDouble(5, loc.getZ());
                        ps.setFloat(6, loc.getYaw());
                    },
                    rs -> -1 // ダミー。実IDはinsert直後に別途取得する
            );
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC作成中にエラー: " + e.getMessage(), e);
            return null;
        }

        // 直前のINSERTのIDを取得（DatabaseManagerのqueryはPreparedStatementを使うため
        // LAST_INSERT_ID()を別クエリで引く）
        try {
            Integer lastId = db.query("SELECT LAST_INSERT_ID()", null,
                    rs -> rs.next() ? rs.getInt(1) : null);
            if (lastId == null) return null;
            id = lastId;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "NPCID取得中にエラー: " + e.getMessage(), e);
            return null;
        }

        QuestNpc npc = new QuestNpc(id, name, loc.getWorld().getName(),
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

        QuestNpc moved = new QuestNpc(id, npc.getName(), loc.getWorld().getName(),
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