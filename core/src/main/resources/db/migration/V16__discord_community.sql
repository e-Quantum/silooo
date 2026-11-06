-- Les sondages, les bureaux d'entreprise et les curseurs des tableaux de communauté Discord.
-- Toutes les dates sont en UTC. Les voix ne conservent qu'un choix courant par XUID prouvé.

CREATE TABLE discord_polls (
    poll_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id           VARCHAR(32) NOT NULL,
    message_id           VARCHAR(32) NULL,
    question             VARCHAR(200) NOT NULL,
    options_json         LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ends_at              DATETIME(3) NOT NULL,
    created_by           VARCHAR(32) NOT NULL COMMENT 'identifiant Discord du membre du staff',
    created_at           DATETIME(3) NOT NULL,
    closed_at            DATETIME(3) NULL,
    result_published_at  DATETIME(3) NULL COMMENT 'le résultat a été modifié ou republié dans Discord',
    PRIMARY KEY (poll_id),
    KEY ix_discord_polls_pending (result_published_at, created_at),
    KEY ix_discord_polls_expiry (closed_at, ends_at),
    CONSTRAINT ck_discord_polls_options_json CHECK (JSON_VALID(options_json)),
    CONSTRAINT ck_discord_polls_close_order CHECK (closed_at IS NULL OR closed_at >= ends_at),
    CONSTRAINT ck_discord_polls_result_closed CHECK (result_published_at IS NULL OR closed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_poll_votes (
    poll_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_xuid   BIGINT UNSIGNED NOT NULL,
    option_index  TINYINT UNSIGNED NOT NULL,
    voted_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (poll_id, player_xuid),
    KEY ix_discord_poll_votes_player (player_xuid, voted_at),
    CONSTRAINT fk_discord_poll_votes_poll FOREIGN KEY (poll_id) REFERENCES discord_polls (poll_id) ON DELETE CASCADE,
    CONSTRAINT fk_discord_poll_votes_player FOREIGN KEY (player_xuid) REFERENCES players (xuid) ON DELETE CASCADE,
    CONSTRAINT ck_discord_poll_votes_option CHECK (option_index BETWEEN 0 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_company_bureaus (
    company_id  BIGINT UNSIGNED NOT NULL,
    channel_id  VARCHAR(32) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (company_id),
    UNIQUE KEY uq_discord_company_bureaus_channel (channel_id),
    CONSTRAINT fk_discord_company_bureaus_company FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_game_event_cursor (
    cursor_key  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id    BIGINT UNSIGNED NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (cursor_key),
    KEY ix_discord_game_event_cursor_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_community_state (
    state_key    VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_value  VARCHAR(255) NOT NULL,
    updated_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (state_key),
    KEY ix_discord_community_state_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
