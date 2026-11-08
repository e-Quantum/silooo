-- Audit sans contenu des messages. Une tentative n'est jamais rejouée automatiquement.
CREATE TABLE discord_channel_cases (
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    actor_id VARCHAR(32) NOT NULL,
    channel_id VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    attempted_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    plan_json LONGTEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    affected INT NULL,
    KEY ix_discord_channel_cases_retention (created_at),
    KEY ix_discord_channel_cases_channel (channel_id, created_at),
    CONSTRAINT ck_discord_channel_plan CHECK (JSON_VALID(plan_json)),
    CONSTRAINT ck_discord_channel_status CHECK (status IN ('reserved', 'attempted', 'succeeded', 'failed', 'uncertain')),
    CONSTRAINT ck_discord_channel_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indépendant de la rétention de l'audit : on ne perd jamais l'état permettant de restaurer un verrou.
CREATE TABLE discord_channel_locks (
    channel_id VARCHAR(32) NOT NULL PRIMARY KEY,
    original_json LONGTEXT NOT NULL,
    locked_json LONGTEXT NOT NULL,
    version BIGINT NOT NULL,
    lock_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    available_after DATETIME(3) NOT NULL,
    CONSTRAINT ck_discord_channel_original CHECK (JSON_VALID(original_json)),
    CONSTRAINT ck_discord_channel_locked CHECK (JSON_VALID(locked_json)),
    CONSTRAINT ck_discord_channel_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
