package com.woxloi.questpluginv2;

import com.woxloi.questpluginv2.command.QuestCommand;
import com.woxloi.questpluginv2.command.QuestOpCommand;
import com.woxloi.questpluginv2.command.QuestPartyCommand;
import com.woxloi.questpluginv2.database.DatabaseManager;
import com.woxloi.questpluginv2.listener.MythicMobsListener;
import com.woxloi.questpluginv2.listener.PartyListener;
import com.woxloi.questpluginv2.listener.PlayerJoinQuitListener;
import com.woxloi.questpluginv2.listener.QuestProgressListener;
import com.woxloi.questpluginv2.listener.QuestDeathRespawnListener;
import com.woxloi.questpluginv2.manager.ActiveQuestManager;
import com.woxloi.questpluginv2.manager.PartyManager;
import com.woxloi.questpluginv2.manager.QuestManager;
import com.woxloi.questpluginv2.manager.VaultManager;
import net.milkbowl.vault.economy.Economy;
import oraserver.orapluginapi.OraPlugin;
import oraserver.orapluginapi.commandapi.OraCommandLiteral;
import oraserver.orapluginapi.nms.OraPluginNms;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * QuestPluginV2 メインクラス
 *
 * onEnable() の末尾でコマンドを OraPluginAPI の NMS 経由で Brigadier に登録する。
 * Kotlin オブジェクト (QuestCommand / QuestOpCommand / QuestPartyCommand) の
 * buildArg() を呼び出し、返ってきた OraCommandLiteral を NMS に渡す。
 */
public class QuestPluginV2 extends JavaPlugin {

    private static QuestPluginV2 instance;

    private ActiveQuestManager activeQuestManager;
    private DatabaseManager    databaseManager;
    private QuestManager       questManager;
    private PartyManager       partyManager;
    private VaultManager       vaultManager;
    private boolean mythicMobsEnabled = false;

    public static final String PREFIX = "§a§l[§6§lQuestPlugin§d§lV2§a§l]§r";

    // ============================================================
    //  ライフサイクル
    // ============================================================

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        // --- データベース初期化 ---
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("MySQLへの接続に失敗しました。プラグインを無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        databaseManager.initializeTables();

        // --- マネージャー初期化 ---
        questManager      = new QuestManager(this);
        activeQuestManager = new ActiveQuestManager(this);  // QuestManager の後に初期化
        partyManager      = new PartyManager(this);
        vaultManager      = new VaultManager(this);

        // --- Vault 連携 ---
        if (getConfig().getBoolean("vault.enabled") && setupEconomy()) {
            getLogger().info("Vault連携が有効になりました。");
        }

        // --- MythicMobs 連携チェック ---
        if (getServer().getPluginManager().getPlugin("MythicMobs") != null
                && getConfig().getBoolean("mythicmobs.enabled")) {
            mythicMobsEnabled = true;
            getLogger().info("MythicMobs連携が有効になりました。");
        }

        // --- リスナー登録 ---
        registerListeners();

        // --- コマンド登録 ---
        registerCommands();

        getLogger().info("QuestPluginV2 v1.0.0 が有効化されました。");
    }

    @Override
    public void onDisable() {
        // 進行中クエストのタイマーを全停止（リロード/シャットダウン時にスレッドが残らないように）
        if (activeQuestManager != null) {
            activeQuestManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        getLogger().info("QuestPluginV2 が無効化されました。");
    }

    // ============================================================
    //  リスナー登録
    // ============================================================

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new QuestProgressListener(this),    this);
        getServer().getPluginManager().registerEvents(new PartyListener(this),             this);
        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(this),   this);
        getServer().getPluginManager().registerEvents(new QuestDeathRespawnListener(this), this);
        if (mythicMobsEnabled) {
            getServer().getPluginManager().registerEvents(new MythicMobsListener(this), this);
        }
    }

    // ============================================================
    //  コマンド登録 (OraPluginAPI / Brigadier)
    // ============================================================

    /**
     * OraPluginAPI の NMS レイヤーを経由して Brigadier にコマンドを登録する。
     *
     * Kotlin の companion object / object で定義した buildArg() を呼び出し、
     * 返ってきた OraCommandLiteral を OraPluginNms#registerCommand() に渡す。
     *
     * 登録タイミング:
     *   - 通常は onEnable() の末尾で問題ない。
     *   - /reload やプラグインの再起動後にも再登録される。
     */
    private void registerCommands() {
        try {
            OraPluginNms nms = OraPlugin.Companion.getNms();

            // /quest
            OraCommandLiteral questRoot = QuestCommand.INSTANCE.buildArg(this);
            nms.registerCommand(questRoot);

            // /questop
            OraCommandLiteral questAdminRoot = QuestOpCommand.INSTANCE.buildArg(this);
            nms.registerCommand(questAdminRoot);

            // /party
            OraCommandLiteral partyRoot = QuestPartyCommand.INSTANCE.buildArg(this);
            nms.registerCommand(partyRoot);

            getLogger().info("コマンドの登録が完了しました。");

        } catch (Exception e) {
            getLogger().severe("コマンド登録中にエラーが発生しました: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============================================================
    //  Vault セットアップ
    // ============================================================

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        vaultManager.setEconomy(rsp.getProvider());
        return true;
    }

    // ============================================================
    //  ゲッター
    // ============================================================

    public static QuestPluginV2 getInstance()          { return instance; }
    public ActiveQuestManager   getActiveQuestManager(){ return activeQuestManager; }
    public DatabaseManager      getDatabaseManager()   { return databaseManager; }
    public QuestManager         getQuestManager()      { return questManager; }
    public PartyManager         getPartyManager()      { return partyManager; }
    public VaultManager         getVaultManager()      { return vaultManager; }
    public boolean              isMythicMobsEnabled()  { return mythicMobsEnabled; }
}