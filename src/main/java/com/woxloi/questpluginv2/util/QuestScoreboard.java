package com.woxloi.questpluginv2.util;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.woxloi.questpluginv2.model.party.Party;
import com.woxloi.questpluginv2.model.quest.ActiveQuestSession;
import com.woxloi.questpluginv2.model.quest.Quest;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * クエスト進行中に表示するサイドバースコアボード
 */

public class QuestScoreboard {

    private final QuestPluginV2 plugin;
    private final Player        player;
    private final Quest         quest;
    private Scoreboard          board;
    private Objective           objective;

    private int  currentAmount = 0;
    private Long remainingTimeSeconds = null;

    public QuestScoreboard(QuestPluginV2 plugin, Player player, Quest quest) {
        this.plugin = plugin;
        this.player = player;
        this.quest  = quest;
    }

    public void show() {
        board     = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("quest", Criteria.DUMMY, ChatColor.RED + "" + ChatColor.BOLD + "QuestPlugin");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        update();
        player.setScoreboard(board);
    }

    public void updateProgress(int newAmount) {
        this.currentAmount = Math.min(newAmount, quest.getRequiredAmount());
        update();
    }

    public void updateRemainingTime(Long seconds) {
        this.remainingTimeSeconds = seconds;
        update();
    }

    public void update() {
        if (board == null || objective == null) return;

        // 既存スコアをリセット
        for (String entry : board.getEntries()) board.resetScores(entry);

        int score = 15;

        set("§e§lクエスト名: §f" + quest.getName(), score--);
        set("§c§lクエスト進行中", score--);
        set("§a§l目標: §f" + quest.getObjectiveDescription(), score--);
        set("§b§l進行状況: §f" + currentAmount + "/" + quest.getRequiredAmount(), score--);

        Party party = plugin.getPartyManager().getPlayerParty(player.getUniqueId());

        // パーティーメンバー（自分含む、重複なし）一覧を取得
        List<Player> partyMembers = new ArrayList<>();
        if (party != null) {
            Set<UUID> seen = new LinkedHashSet<>();
            for (UUID uuid : party.getMemberUUIDs()) {
                if (!seen.add(uuid)) continue;
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) partyMembers.add(p);
            }
        }

        // ライフ残り 合計
        if (quest.getMaxLives() != null) {
            List<Player> lifeMembers = new ArrayList<>(partyMembers);
            if (!lifeMembers.contains(player)) lifeMembers.add(player);

            int totalMaxLives = quest.getMaxLives() * lifeMembers.size();
            int totalDeaths = 0;
            for (Player member : lifeMembers) {
                ActiveQuestSession session = plugin.getActiveQuestManager().getSession(member.getUniqueId());
                if (session != null) totalDeaths += session.getDeathCount();
            }
            int remainingLives = Math.max(0, totalMaxLives - totalDeaths);

            set("§d§l総ライフ数: §f" + remainingLives, score--);
        }

        // パーティーメンバーの表示（観戦モードは除外）
        List<Player> visibleMembers = partyMembers.stream()
                .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                .toList();

        if (!visibleMembers.isEmpty()) {
            set("§b§lパーティーメンバー:", score--);
            for (Player member : visibleMembers) {
                int currentHP = (int) member.getHealth();
                set("§f§l" + member.getName() + " §d§l♥§7§l" + currentHP, score--);
            }
        }

        // 制限時間の表示（最後に表示）
        if (remainingTimeSeconds != null) {
            long min = remainingTimeSeconds / 60;
            long sec = remainingTimeSeconds % 60;
            set(String.format("§c§l残り時間: §f%02d:%02d", min, sec), score--);
        }
    }

    public void hide() {
        if (player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void set(String text, int score) {
        // スコアボードのエントリは一意でなければならないのでリセットカラーで重複回避
        String padded = text;
        while (board.getEntries().contains(padded)) padded += ChatColor.RESET;
        objective.getScore(padded).setScore(score);
    }
}