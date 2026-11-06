-- Rôles de jeu effectivement gérés par Oooscar : permet le nettoyage après redémarrage.
CREATE TABLE discord_managed_game_roles (
    discord_user_id VARCHAR(32) NOT NULL,
    role_key VARCHAR(32) NOT NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (discord_user_id, role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
