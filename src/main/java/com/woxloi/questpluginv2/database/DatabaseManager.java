package com.woxloi.questpluginv2.database;

import com.woxloi.questpluginv2.QuestPluginV2;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * MySQL接続管理クラス（HikariCPコネクションプール使用）
 *
 * 修正点: initializeTables() のテーブル定義を、resources/database.sql から
 * 読み込んで実行する方式に変更した。以前は database.sql とこのクラスの両方に
 * 同じ CREATE TABLE 文がハードコードされており、片方だけ更新すると
 * スキーマが食い違うリスクがあった。database.sql を単一の正として扱い、
 * このクラスはそれをパースして実行するだけにする。
 *
 * 修正点2: migrateSchema() を追加。CREATE TABLE IF NOT EXISTS では
 * 既存テーブルへのカラム追加が反映されないため（quest_npcs.entity_type の追加等）、
 * INFORMATION_SCHEMA でカラム存在を確認した上で必要な ALTER TABLE のみを実行する
 * 簡易マイグレーション機構。initializeTables() の直後に呼ぶこと。
 */
public class DatabaseManager {

    private final QuestPluginV2 plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(QuestPluginV2 plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            HikariConfig config = new HikariConfig();
            String host     = plugin.getConfig().getString("database.host", "localhost");
            int port        = plugin.getConfig().getInt("database.port", 3306);
            String dbName   = plugin.getConfig().getString("database.name", "questpluginv2");
            String user     = plugin.getConfig().getString("database.user", "root");
            String password = plugin.getConfig().getString("database.password", "");
            int poolSize    = plugin.getConfig().getInt("database.pool-size", 10);
            long timeout    = plugin.getConfig().getLong("database.connection-timeout", 30000);

            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName
                    + "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8");
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(poolSize);
            config.setConnectionTimeout(timeout);
            config.setPoolName("QuestPluginV2-Pool");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("MySQLへの接続に成功しました。");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "MySQLへの接続中にエラーが発生しました: " + e.getMessage(), e);
            return false;
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("MySQL接続を閉じました。");
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 全テーブルを初期化する。
     * プラグインフォルダ内の database.sql を優先して読み込み、存在しなければ
     * JAR 同梱の resources/database.sql を読み込んでセミコロンで区切った各CREATE TABLE文を実行する。
     */
    public void initializeTables() {
        List<String> statements;
        try {
            statements = loadStatements();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "database.sql の読み込みに失敗しました: " + e.getMessage(), e);
            return;
        }

        for (String sql : statements) {
            executeSql(sql);
        }

        plugin.getLogger().info("全テーブルの初期化が完了しました（" + statements.size() + " 文を実行）。");

        migrateSchema();
    }

    /**
     * CREATE TABLE IF NOT EXISTS では反映されない、既存テーブルへのカラム追加を行う。
     * 各エントリは「テーブル名・カラム名・カラム追加用のALTER文」の組。
     * カラムが既に存在する場合はスキップするため、何度実行しても安全（冪等）。
     */
    private void migrateSchema() {
        migrateColumn("quest_npcs", "entity_type",
                "ALTER TABLE quest_npcs ADD COLUMN entity_type VARCHAR(32) NOT NULL DEFAULT 'VILLAGER' AFTER name");
    }

    private void migrateColumn(String table, String column, String alterSql) {
        try {
            boolean exists = columnExists(table, column);
            if (exists) return;

            executeSql(alterSql);
            plugin.getLogger().info("マイグレーション: " + table + "." + column + " を追加しました。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "マイグレーション中にエラー (" + table + "." + column + "): " + e.getMessage(), e);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * プラグインフォルダ (`plugins/QuestPluginV2/database.sql`) を優先して読み込み、
     * なければ JAR 内の /database.sql を読み込むパーサー。
     * コメント行を除去した上でセミコロン区切りのSQL文リストへ分割する。
     * シンプルなパーサーのため、文字列リテラル内のセミコロンには対応していない。
     */
    private List<String> loadStatements() throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        // プラグインデータフォルダ内のファイルを優先
        File external = new File(plugin.getDataFolder(), "database.sql");
        if (external.exists() && external.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(external.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

                    current.append(line).append('\n');
                    if (trimmed.endsWith(";")) {
                        statements.add(current.toString().trim());
                        current.setLength(0);
                    }
                }
            }
        } else {
            // フォールバック: JAR 内のリソース
            try (InputStream in = getClass().getResourceAsStream("/database.sql")) {
                if (in == null) {
                    throw new IOException("リソースが見つかりません: /database.sql");
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;

                        current.append(line).append('\n');
                        if (trimmed.endsWith(";")) {
                            statements.add(current.toString().trim());
                            current.setLength(0);
                        }
                    }
                }
            }
        }

        if (current.length() > 0 && !current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }

        return statements;
    }

    private void executeSql(String sql) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "テーブル初期化中にエラー: " + e.getMessage() + "\nSQL: " + sql, e);
        }
    }

    /**
     * クエリ実行ヘルパー（ResultSetをラムダで処理）
     */
    public <T> T query(String sql, SqlConsumer<PreparedStatement> setter,
                       SqlFunction<ResultSet, T> handler) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (setter != null) setter.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return handler.apply(rs);
            }
        }
    }

    /**
     * 更新実行ヘルパー
     */
    public int update(String sql, SqlConsumer<PreparedStatement> setter) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (setter != null) setter.accept(ps);
            return ps.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}