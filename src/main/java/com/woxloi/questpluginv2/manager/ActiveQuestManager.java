package com.woxloi.questpluginv2.manager;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.model.party.Party;
import com.woxloi.questpluginv2.model.quest.ActiveQuestSession;
import com.woxloi.questpluginv2.model.quest.Quest;
import com.woxloi.questpluginv2.model.quest.QuestType;
import com.woxloi.questpluginv2.util.ItemBackup;
import com.woxloi.questpluginv2.util.QuestScoreboard;
import com.woxloi.questpluginv2.util.CountdownTimer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クエスト「進行中」状態の管理クラス
 * ボスバー・スコアボード・タイマー・インベントリバックアップ・リスポーン処理を担当
 *
 * 修正点:
 *  1. startQuest() でインベントリをバックアップした直後に ItemBackup.clearInventory()
 *     を呼び、クエスト開始時にインベントリを空にするようにした。
 *     以前はクリア処理が無いまま、終了時にバックアップ（開始時点）へ強制的に
 *     巻き戻すだけだったため、クエスト中のアイテム変化が全て消えてしまっていた。
 *     V1の「開始時にクリア→終了時に復元」という意図を取り戻している。
 *  2. バックアップが既存の場合（前回の復元が完了していない異常系）は
 *     開始処理自体を中止し、安全側に倒す。
 *  3. CountdownTimer が BukkitScheduler ベースになったことに伴い、
 *     tick/finish リスナー内で行っていた Bukkit.getScheduler().runTask() の
 *     二重包みを削除（既にメインスレッドで呼ばれるため不要）。
 *  4. shutdown() を追加。onDisable() から呼び、進行中の全タイマーを確実に停止する
 *     （以前は onDisable() で稼働中タイマーを止めておらず、リロード時に
 *      取り残されたタスクが残る可能性があった）。
 *  5. 報酬付与を RewardGiver に委譲（QuestManagerとの重複解消）。
 */
public class ActiveQuestManager {

    private final QuestPluginV2 plugin;

    /** playerUUID -> 進行中セッション */
    private final Map<UUID, ActiveQuestSession> sessions = new ConcurrentHashMap<>();

    public ActiveQuestManager(QuestPluginV2 plugin) {
        this.plugin = plugin;

        // 定期的にリスポーン基点を更新（10秒ごと）
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, ActiveQuestSession> entry : sessions.entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline() && !p.isDead()) {
                    entry.getValue().setOriginalLocation(p.getLocation());
                }
            }
        }, 20L * 10, 20L * 10);
    }

    // ============================================================
    //  クエスト開始
    // ============================================================

    /**
     * プレイヤー（またはパーティー全員）にクエストを開始させる。
     * クエスト開始時にインベントリをバックアップしてクリアし、
     * 終了・失敗・キャンセル時に復元する。
     *
     * @param leader 開始を要求したプレイヤー
     * @param quest  対象クエスト
     * @param party  パーティー（ソロなら null）
     * @return 成功したか
     */
    public boolean startQuest(Player leader, Quest quest, Party party) {
        List<Player> members = resolveMembers(leader, quest, party);
        if (members == null) return false;

        // パーティー上限チェック
        if (quest.getPartyMaxMembers() != null && members.size() > quest.getPartyMaxMembers()) {
            leader.sendMessage(QuestPluginV2.PREFIX + "§c§lパーティー上限を超えています（最大 "
                    + quest.getPartyMaxMembers() + " 人）");
            return false;
        }

        // 既に進行中チェック
        for (Player m : members) {
            if (sessions.containsKey(m.getUniqueId())) {
                m.sendMessage(QuestPluginV2.PREFIX + "§c§l既にクエストを進行中です");
                return false;
            }
        }

        // 既存バックアップが残っていないかを事前確認（異常系の保護）
        for (Player m : members) {
            File backupFile = ItemBackup.getBackupFile(plugin.getDataFolder(), m.getUniqueId());
            if (ItemBackup.hasExistingBackup(backupFile)) {
                m.sendMessage(QuestPluginV2.PREFIX + "§c§l前回のインベントリバックアップが残っているため開始できません");
                m.sendMessage(QuestPluginV2.PREFIX + "§c§l管理者に連絡してください");
                if (m.isOp()) {
                    m.sendMessage(QuestPluginV2.PREFIX + "§c§l" + m.getName() + "の既存バックアップが残存しています");
                }
                plugin.getLogger().warning("クエスト開始を中止: " + m.getName() + " の既存バックアップが残存しています");
                return false;
            }
        }

        BossBar bossBar = createBossBar(quest);

        // タイムリミットが設定されている場合のみタイマーを作成
        CountdownTimer timer = null;
        if (quest.getTimeLimitSeconds() != null && quest.getTimeLimitSeconds() > 0) {
            timer = new CountdownTimer(plugin, quest.getTimeLimitSeconds().intValue());
            final CountdownTimer finalTimer = timer;

            // CountdownTimer は内部で BukkitScheduler#runTaskTimer を使うため
            // 既にメインスレッド上で呼ばれる。Bukkit.getScheduler().runTask() による
            // 二重包みは不要。
            timer.addTickListener(remaining -> {
                double progress = (double) remaining / finalTimer.getDuration();
                bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                for (Player m : members) {
                    ActiveQuestSession s = sessions.get(m.getUniqueId());
                    if (s != null) {
                        s.getScoreboard().updateRemainingTime((long) remaining);
                    }
                }
            });

            timer.addFinishListener(() -> {
                bossBar.setProgress(0.0);
                for (Player m : members) {
                    m.sendMessage(QuestPluginV2.PREFIX + "§c§l時間切れ...");
                    failQuest(m);
                }
            });

            timer.start();
        }

        // 各メンバーにセッションを作成
        for (Player m : members) {
            QuestScoreboard sb = new QuestScoreboard(plugin, m, quest);

            long startTime = System.currentTimeMillis();
            ActiveQuestSession session = new ActiveQuestSession(
                    quest, startTime, bossBar, timer, sb, m.getLocation());

            // ---- インベントリバックアップ（ファイルベース） ----
            File backupFile = ItemBackup.getBackupFile(plugin.getDataFolder(), m.getUniqueId());
            boolean backedUp = ItemBackup.saveInventoryToFile(m, backupFile);

            // ---- インベントリクリア ----
            // バックアップに成功した場合のみクリアする。
            // バックアップに失敗した状態でクリアしてしまうと、アイテムが
            // 完全に失われてしまうため、その場合はクリアせず継続する
            // （プレイヤーには既にItemBackup側でエラーメッセージ済み）。
            if (backedUp) {
                ItemBackup.clearInventory(m);
            }

            if (party != null) session.setPartyId(party.getPartyId());

            sessions.put(m.getUniqueId(), session);

            bossBar.addPlayer(m);
            sb.show();

            // 開始コマンド
            for (String cmd : quest.getStartCommands()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        cmd.replace("%player%", m.getName()));
            }

            // テレポート
            if (quest.getTeleportWorld() != null) {
                var world = Bukkit.getWorld(quest.getTeleportWorld());
                if (world != null) {
                    m.teleport(new org.bukkit.Location(world,
                            quest.getTeleportX(), quest.getTeleportY(), quest.getTeleportZ()));
                    m.sendMessage(QuestPluginV2.PREFIX + "§a§lクエスト開始地点へテレポートしました");
                }
            }

            m.sendMessage(QuestPluginV2.PREFIX + "§a§l" + quest.getName() + "を開始しました！");
        }

        return true;
    }

    // ============================================================
    //  進捗追加
    // ============================================================

    public void addProgress(UUID playerUUID, QuestType type, String targetId, int amount) {
        applyProgress(playerUUID, type, targetId, amount, false);
    }

    /**
     * 進捗を「加算」ではなく「絶対値としてセット」する。
     * LEVEL_UP のように、イベントが渡してくる値が増加量ではなく到達値である
     * クエストタイプに使用する。
     */
    public void setAbsoluteProgress(UUID playerUUID, QuestType type, String targetId, int value) {
        applyProgress(playerUUID, type, targetId, value, true);
    }

    private void applyProgress(UUID playerUUID, QuestType type, String targetId, int amount, boolean absolute) {
        ActiveQuestSession session = sessions.get(playerUUID);
        if (session == null) return;

        Quest quest = session.getQuest();
        if (quest.getType() != type) return;
        if (targetId != null && quest.getTargetId() != null
                && !quest.getTargetId().equalsIgnoreCase(targetId)) return;

        if (absolute) {
            if (amount <= session.getProgress()) return; // 後退・重複は無視
            session.setProgress(amount);
        } else {
            session.addProgress(amount);
        }
        session.getScoreboard().updateProgress(session.getProgress());
        updateBossBar(playerUUID, session);

        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null) {
            player.sendMessage(QuestPluginV2.PREFIX + "§e§l" + quest.getName()
                    + "進捗: §a§l" + session.getProgress() + "§7§l/§a§l" + quest.getRequiredAmount());
        }

        // 完了チェック
        if (session.getProgress() >= quest.getRequiredAmount()) {
            completeQuest(playerUUID);
            return;
        }

        // パーティー共有
        if (plugin.getConfig().getBoolean("party.progress-share") && session.getPartyId() != null) {
            Party party = plugin.getPartyManager().getParty(session.getPartyId());
            if (party != null) {
                for (UUID memberUUID : party.getMemberUUIDs()) {
                    if (!memberUUID.equals(playerUUID)) {
                        applyProgress(memberUUID, type, targetId, amount, absolute);
                    }
                }
            }
        }
    }

    /**
     * クエスト終了（完了/失敗/キャンセル）に伴うパーティー解散処理。
     * V1の PartyManager.disbandParty(player) 相当だが、V2の
     * PartyManager.dissolveParty(partyId) は void で全メンバーへの
     * 解散通知も内部で行うため、ここでは「まだパーティーが存在するか」を
     * 確認した上で解散し、要求元プレイヤーにのみ追加メッセージを送る。
     * （パーティー全体で complete/fail が連鎖する際に二重解散・誤った
     *  失敗メッセージが出ないようにするための分岐）
     */
    private void dissolvePartyOnQuestEnd(Player player, ActiveQuestSession session) {
        String partyId = session.getPartyId();
        if (partyId == null) return;

        Party party = plugin.getPartyManager().getParty(partyId);
        if (party != null) {
            plugin.getPartyManager().dissolveParty(partyId);
            if (player != null) {
                player.sendMessage(QuestPluginV2.PREFIX + "§a§lクエスト終了とともにパーティーが解散されました");
            }
        }
    }

    // ============================================================
    //  クエスト完了
    // ============================================================

    public void completeQuest(UUID playerUUID) {
        ActiveQuestSession session = sessions.remove(playerUUID);
        if (session == null) return;

        Player player = Bukkit.getPlayer(playerUUID);
        Quest  quest  = session.getQuest();

        cleanupSession(playerUUID, session);

        if (player != null) {
            // ---- インベントリ復元（ファイルベース） ----
            File backupFile = ItemBackup.getBackupFile(plugin.getDataFolder(), playerUUID);
            ItemBackup.loadInventoryFromFile(player, backupFile);
            backupFile.delete();

            RewardGiver.give(plugin, player, quest);
            player.sendMessage(QuestPluginV2.PREFIX + "§a§l" + quest.getName() + "を完了しました！");
        }

        plugin.getQuestManager().recordHistory(playerUUID, quest, true, session.getProgress());

        if (session.getPartyId() != null && quest.isShareCompletion()) {
            Party party = plugin.getPartyManager().getParty(session.getPartyId());
            if (party != null) {
                for (UUID memberUUID : party.getMemberUUIDs()) {
                    if (!memberUUID.equals(playerUUID)) {
                        completeQuest(memberUUID);
                    }
                }
            }
        }

        dissolvePartyOnQuestEnd(player, session);
    }

    // ============================================================
    //  クエスト失敗 / キャンセル
    // ============================================================

    public void failQuest(Player player) {
        UUID               uuid    = player.getUniqueId();
        ActiveQuestSession session = sessions.remove(uuid);
        if (session == null) return;

        cleanupSession(uuid, session);

        // ---- インベントリ復元（ファイルベース） ----
        File backupFile = ItemBackup.getBackupFile(plugin.getDataFolder(), uuid);
        ItemBackup.loadInventoryFromFile(player, backupFile);
        backupFile.delete();

        plugin.getQuestManager().recordHistory(uuid, session.getQuest(), false, session.getProgress());
        player.sendMessage(QuestPluginV2.PREFIX + "§c§lクエストに失敗しました");

        dissolvePartyOnQuestEnd(player, session);
    }

    /**
     * leave コマンドや手動キャンセル時に呼ぶ。インベントリを復元して終了する。
     */
    public void cancelQuest(Player player) {
        UUID               uuid    = player.getUniqueId();
        ActiveQuestSession session = sessions.remove(uuid);
        if (session == null) return;

        cleanupSession(uuid, session);

        // ---- インベントリ復元（ファイルベース） ----
        File backupFile = ItemBackup.getBackupFile(plugin.getDataFolder(), uuid);
        ItemBackup.loadInventoryFromFile(player, backupFile);
        backupFile.delete();

        plugin.getQuestManager().recordHistory(uuid, session.getQuest(), false, session.getProgress());

        dissolvePartyOnQuestEnd(player, session);
    }

    // ============================================================
    //  死亡処理
    // ============================================================

    /**
     * プレイヤーが死んだときに呼ぶ。
     * @return 残りライフ数（maxLives 未設定なら -1）
     */
    public int onPlayerDeath(Player player) {
        ActiveQuestSession session = sessions.get(player.getUniqueId());
        if (session == null) return -1;

        Quest quest = session.getQuest();
        if (quest.getMaxLives() == null) return -1;

        session.incrementDeathCount();

        // パーティー合算ライフ計算
        int totalMax    = quest.getMaxLives();
        int totalDeaths = session.getDeathCount();
        if (session.getPartyId() != null) {
            Party party = plugin.getPartyManager().getParty(session.getPartyId());
            if (party != null) {
                totalMax    = quest.getMaxLives() * party.getSize();
                totalDeaths = 0;
                for (UUID uuid : party.getMemberUUIDs()) {
                    ActiveQuestSession ms = sessions.get(uuid);
                    if (ms != null) totalDeaths += ms.getDeathCount();
                }
            }
        }

        int remaining = totalMax - totalDeaths;
        session.getScoreboard().update();

        if (remaining <= 0) {
            Player finalPlayer = player;
            Bukkit.getScheduler().runTask(plugin, () -> failQuest(finalPlayer));
        }

        return Math.max(0, remaining);
    }

    /**
     * リスポーン時の位置を決定する。
     */
    public org.bukkit.Location getRespawnLocation(Player player) {
        ActiveQuestSession session = sessions.get(player.getUniqueId());
        if (session == null) return null;

        // パーティーで生存メンバーが居ればその近くに
        if (session.getPartyId() != null) {
            Party party = plugin.getPartyManager().getParty(session.getPartyId());
            if (party != null) {
                Quest quest = session.getQuest();
                for (UUID uuid : party.getMemberUUIDs()) {
                    if (uuid.equals(player.getUniqueId())) continue;
                    ActiveQuestSession ms = sessions.get(uuid);
                    if (ms == null) continue;
                    if (quest.getMaxLives() != null && ms.getDeathCount() >= quest.getMaxLives()) continue;
                    Player member = Bukkit.getPlayer(uuid);
                    if (member != null && member.isOnline() && !member.isDead()) {
                        return member.getLocation().clone().add(1, 0, 1);
                    }
                }
            }
        }
        return session.getOriginalLocation().clone();
    }

    // ============================================================
    //  クエリ
    // ============================================================

    public boolean isQuesting(UUID playerUUID)               { return sessions.containsKey(playerUUID); }
    public ActiveQuestSession getSession(UUID playerUUID)    { return sessions.get(playerUUID); }
    public Quest getActiveQuest(UUID playerUUID) {
        ActiveQuestSession s = sessions.get(playerUUID);
        return s == null ? null : s.getQuest();
    }

    // ============================================================
    //  シャットダウン
    // ============================================================

    /**
     * プラグイン無効化時に呼ぶ。進行中の全タイマーを確実に停止する。
     * 複数セッションが同じタイマーインスタンスを共有している場合は重複停止を避ける。
     */
    public void shutdown() {
        Set<CountdownTimer> stopped = new HashSet<>();
        for (ActiveQuestSession session : sessions.values()) {
            CountdownTimer timer = session.getTimer();
            if (timer != null && stopped.add(timer)) {
                timer.stop();
            }
        }
    }

    // ============================================================
    //  内部ヘルパー
    // ============================================================

    /** セッションの BossBar / タイマー / スコアボードを後片付けする。 */
    private void cleanupSession(UUID uuid, ActiveQuestSession session) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            session.getBossBar().removePlayer(p);
        }
        // タイマーは他のセッションが共有していない場合のみ停止
        if (session.getTimer() != null) {
            boolean timerStillNeeded = sessions.values().stream()
                    .anyMatch(s -> s.getTimer() == session.getTimer());
            if (!timerStillNeeded) {
                session.getTimer().stop();
            }
        }
        session.getScoreboard().hide();
    }

    /** クエストタイプごとの動作動詞を返す（V1のgetActionVerb相当） */
    private String getActionVerb(QuestType type) {
        return switch (type) {
            case KILL_MOB, KILL_PLAYER, BOSS_KILL -> "討伐";
            case BREAK_BLOCK, BREAK_BLOCK_TYPE     -> "破壊";
            case PLACE_BLOCK                       -> "設置";
            case COLLECT_ITEM                      -> "収集";
            case CRAFT_ITEM                        -> "クラフト";
            case FISHING                           -> "釣り上げ";
            case FARMING                           -> "収穫";
            case ENCHANT                           -> "付与";
            case TRADE                             -> "取引";
            case TAME                              -> "テイム";
            case WALK_DISTANCE, SWIM_DISTANCE      -> "移動";
            case TAKE_DAMAGE                        -> "被弾";
            case LEVEL_UP                           -> "到達";
            default                                  -> "達成";
        };
    }

    /** クエストタイプごとの数え方（助数詞）を返す */
    private String getCounterUnit(QuestType type) {
        return switch (type) {
            case KILL_MOB, KILL_PLAYER, BOSS_KILL, TAME -> "体";
            case BREAK_BLOCK, BREAK_BLOCK_TYPE,
                 PLACE_BLOCK, COLLECT_ITEM,
                 CRAFT_ITEM, FISHING, FARMING        -> "個";
            case ENCHANT, TRADE                       -> "回";
            case WALK_DISTANCE, SWIM_DISTANCE         -> "ブロック";
            case TAKE_DAMAGE                          -> "ダメージ";
            case LEVEL_UP                             -> "レベル";
            default                                    -> "個";
        };
    }

    /** BossBarを新規作成する。 */
    private BossBar createBossBar(Quest quest) {
        String action = getActionVerb(quest.getType());
        String unit   = getCounterUnit(quest.getType());
        String title  = "§b" + quest.getTargetId() + "を" + quest.getRequiredAmount() + unit + action;
        return Bukkit.createBossBar(title, BarColor.GREEN, BarStyle.SOLID);
    }

    /** BossBarのタイトル・進捗を更新する。 */
    private void updateBossBar(UUID uuid, ActiveQuestSession session) {
        Quest  quest    = session.getQuest();
        int    prog     = session.getProgress();
        int    required = quest.getRequiredAmount();
        double pct      = required > 0 ? Math.min(1.0, (double) prog / required) : 0;
        String action   = getActionVerb(quest.getType());
        String unit     = getCounterUnit(quest.getType());

        session.getBossBar().setProgress(pct);
        session.getBossBar().setTitle("§b" + quest.getTargetId() + "を" + quest.getRequiredAmount() + unit + action);
    }

    /**
     * ソロ or パーティーのメンバーリストを解決する。
     * 問題があれば null を返す。
     */
    private List<Player> resolveMembers(Player leader, Quest quest, Party party) {
        if (party == null || !quest.isPartyEnabled()) {
            return List.of(leader);
        }
        List<Player> list = new java.util.ArrayList<>();
        for (UUID uuid : party.getMemberUUIDs()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) list.add(p);
        }
        if (list.isEmpty()) {
            leader.sendMessage(QuestPluginV2.PREFIX + "§c§lパーティーメンバーがオンラインではありません");
            return null;
        }
        return list;
    }
}