-- Préférences explicites et file durable des rappels privés.
CREATE TABLE discord_reminder_preferences (
    discord_user_id VARCHAR(32) NOT NULL,
    time_zone VARCHAR(64) NULL,
    silo TINYINT(1) NOT NULL DEFAULT 0,
    vote TINYINT(1) NOT NULL DEFAULT 0,
    vote_streak TINYINT(1) NOT NULL DEFAULT 0,
    order_done TINYINT(1) NOT NULL DEFAULT 0,
    company_invite TINYINT(1) NOT NULL DEFAULT 0,
    prestige TINYINT(1) NOT NULL DEFAULT 0,
    gems TINYINT(1) NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (discord_user_id),
    CONSTRAINT ck_discord_reminder_timezone CHECK (time_zone IS NULL OR length(time_zone) BETWEEN 1 AND 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_reminder_deliveries (
    delivery_id VARCHAR(160) NOT NULL,
    discord_user_id VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    proof_xuid BIGINT UNSIGNED NOT NULL,
    proof_linked_at DATETIME(3) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    status ENUM('pending','claimed','sent','failed') NOT NULL DEFAULT 'pending',
    attempted_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    failure VARCHAR(255) NULL,
    PRIMARY KEY (delivery_id),
    KEY ix_discord_reminder_due (status, occurred_at),
    KEY ix_discord_reminder_user_day (discord_user_id, status, occurred_at),
    CONSTRAINT ck_discord_reminder_category CHECK (category IN ('silo','vote','vote_streak','order_done','company_invite','prestige','gems'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_reminder_cursor (
    id TINYINT NOT NULL,
    last_event_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_discord_reminder_cursor CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Démarre au dernier événement existant : activer les rappels ne rejoue pas l'historique du jeu.
INSERT INTO discord_reminder_cursor (id, last_event_id)
SELECT 1, coalesce(max(id), 0) FROM game_events;
