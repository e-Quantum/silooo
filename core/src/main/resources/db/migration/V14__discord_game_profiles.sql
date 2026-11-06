-- Rôles de jeu effectivement gérés par Oooscar : permet le nettoyage après redémarrage.
CREATE TABLE discord_managed_game_roles (
    discord_user_id VARCHAR(32) NOT NULL,
    role_key VARCHAR(32) NOT NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (discord_user_id, role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_managed_game_profiles (
    discord_user_id VARCHAR(32) NOT NULL,
    applied_nickname VARCHAR(32) NULL,
    PRIMARY KEY (discord_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Invitation de bêta: réserver avant le DM évite tout doublon après redémarrage, même si les DM sont fermés.
CREATE TABLE discord_game_invitations (
    discord_user_id VARCHAR(32) NOT NULL,
    attempted_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'attempting',
    PRIMARY KEY (discord_user_id),
    CONSTRAINT ck_discord_game_invitation_status CHECK (status IN ('attempting', 'sent', 'dm_closed', 'discord_refused'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
