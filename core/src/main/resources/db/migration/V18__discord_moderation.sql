-- Journal durable des dossiers de modération Discord. Une réservation passe en tentative avant l'appel Discord.
CREATE TABLE discord_moderation_cases (
    case_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id      VARCHAR(32) NOT NULL,
    target_id     VARCHAR(32) NOT NULL,
    action        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    created_at    DATETIME(3) NOT NULL,
    expires_at    DATETIME(3) NOT NULL,
    timeout_duration_seconds BIGINT NULL,
    status        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure       VARCHAR(500) NULL,
    attempted_at  DATETIME(3) NULL,
    finished_at   DATETIME(3) NULL,
    PRIMARY KEY (case_id),
    KEY ix_discord_moderation_target (target_id, created_at, case_id),
    KEY ix_discord_moderation_retention (created_at),
    CONSTRAINT ck_discord_moderation_action CHECK (action IN ('warn', 'timeout', 'restore')),
    CONSTRAINT ck_discord_moderation_status CHECK (status IN ('reserved', 'succeeded', 'failed')),
    CONSTRAINT ck_discord_moderation_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
