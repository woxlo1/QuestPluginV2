-- QuestPluginV2 テーブル定義
--
-- このファイルが唯一のテーブル定義（single source of truth）。
-- DatabaseManager.initializeTables() はこのファイルを resources から読み込んで
-- 実行する。以前は同じ CREATE TABLE 文がこのファイルと
-- DatabaseManager.java の両方にハードコードされており、片方だけ更新すると
-- 食い違うリスクがあったため、Java側のハードコードは廃止した。
-- テーブル定義を変更する場合はこのファイルだけを編集すればよい。
--
-- 文をセミコロンで区切ったシンプルなパーサーで読み込むため、
-- 各文の末尾には必ず ";" を置くこと。コメント行は "--" で始める。
--
-- 修正点: quest_npcs に entity_type を追加（村人以外の任意Mobをスポーンできるようにするため）。
-- 既存DBには CREATE TABLE IF NOT EXISTS では反映されないため、
-- DatabaseManager.migrateSchema() で ALTER TABLE による後方互換マイグレーションを別途行う。

CREATE TABLE IF NOT EXISTS quests (
    id                  VARCHAR(64)  PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    type                VARCHAR(32)  NOT NULL,
    target_id           VARCHAR(128),
    required_amount     INT          NOT NULL DEFAULT 1,
    prerequisite_quest  VARCHAR(64),
    cooldown_seconds    BIGINT       NOT NULL DEFAULT 0,
    party_enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quest_rewards (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    quest_id    VARCHAR(64)  NOT NULL,
    reward_data TEXT         NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    FOREIGN KEY (quest_id) REFERENCES quests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quest_npcs (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NOT NULL DEFAULT 'VILLAGER',
    world       VARCHAR(64)  NOT NULL,
    x           DOUBLE       NOT NULL,
    y           DOUBLE       NOT NULL,
    z           DOUBLE       NOT NULL,
    yaw         FLOAT        NOT NULL DEFAULT 0,
    quest_id    VARCHAR(64),
    greeting    TEXT,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (quest_id) REFERENCES quests(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_quest_progress (
    player_uuid    CHAR(36)    NOT NULL,
    quest_id       VARCHAR(64) NOT NULL,
    current_amount INT         NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    started_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at   DATETIME,
    party_id       VARCHAR(36),
    PRIMARY KEY (player_uuid, quest_id),
    FOREIGN KEY (quest_id) REFERENCES quests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_quest_history (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    player_uuid    CHAR(36)    NOT NULL,
    quest_id       VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    started_at     DATETIME    NOT NULL,
    completed_at   DATETIME,
    INDEX idx_player (player_uuid),
    INDEX idx_quest  (quest_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS parties (
    party_id     CHAR(36)    PRIMARY KEY,
    leader_uuid  CHAR(36)    NOT NULL,
    max_size     INT         NOT NULL DEFAULT 6,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS party_members (
    party_id     CHAR(36)    NOT NULL,
    member_uuid  CHAR(36)    NOT NULL,
    role         VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    joined_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (party_id, member_uuid),
    FOREIGN KEY (party_id) REFERENCES parties(party_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS party_chat_log (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    party_id     CHAR(36)    NOT NULL,
    sender_uuid  CHAR(36)    NOT NULL,
    message      TEXT        NOT NULL,
    sent_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_party (party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;