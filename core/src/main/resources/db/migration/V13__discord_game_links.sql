-- D3 : le jeu émet la preuve avant de connaître le Discord. Les codes du site (V11) restent distincts.
-- Ce verrou ne concerne que ces transactions courtes, jamais l'économie du jeu.
CREATE TABLE discord_game_link_guard (
    id TINYINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_discord_game_guard CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_game_codes (
    player_xuid BIGINT UNSIGNED NOT NULL,
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 ; aucun code en clair',
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (player_xuid),
    UNIQUE KEY uq_discord_game_code_hash (code_hash),
    CONSTRAINT fk_discord_game_code_player FOREIGN KEY (player_xuid) REFERENCES players (xuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_game_links (
    player_xuid BIGINT UNSIGNED NOT NULL,
    discord_user_id VARCHAR(32) NOT NULL,
    gamertag_key VARCHAR(32) NOT NULL COMMENT 'clé canonique de site_registrations au moment de la preuve',
    linked_at DATETIME(3) NOT NULL,
    PRIMARY KEY (player_xuid),
    UNIQUE KEY uq_discord_game_link_discord (discord_user_id),
    UNIQUE KEY uq_discord_game_link_registration (gamertag_key),
    CONSTRAINT fk_discord_game_link_player FOREIGN KEY (player_xuid) REFERENCES players (xuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cinq saisies au plus dans une fenêtre de dix minutes, conservées après redémarrage et déliaison.
CREATE TABLE discord_game_link_attempts (
    discord_user_id VARCHAR(32) NOT NULL,
    window_started_at DATETIME(3) NOT NULL,
    attempts SMALLINT NOT NULL,
    last_linked_at DATETIME(3) NULL COMMENT 'version monotone des confirmations, même après déliaison',
    PRIMARY KEY (discord_user_id),
    CONSTRAINT ck_discord_game_attempts CHECK (attempts BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
