-- Les comptes du site silooo.com : deux portes (Xbox, Discord), la liste d'attente d'avant l'ouverture, les codes de
-- « Relier Discord », l'accès à la bêta et les sessions. Le service est le seul à écrire ici.
-- Ni l'identifiant Discord ni le XUID Xbox ne s'affichent jamais : ils servent à retrouver un compte, jamais à le nommer.
-- Toutes les dates sont en UTC. site_registrations (V7) reste écrite quand le gamertag est connu : le jeu et le bot la lisent.

-- Un compte du site. Il entre par Xbox (le gamertag est connu tout de suite) ou par Discord (le gamertag se relie plus tard).
CREATE TABLE web_accounts (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    player_xuid      BIGINT UNSIGNED NULL     COMMENT 'empreinte du gamertag (Empreinte.duGamertag), la clé de players ; nulle tant que le gamertag est inconnu',
    gamertag         VARCHAR(32)  NULL        COMMENT 'tel que Xbox ou le jeu l''a donné',
    xbox_xuid        VARCHAR(32)  NULL        COMMENT 'vrai XUID Xbox du compte entré par Xbox, jamais affiché',
    discord_user_id  VARCHAR(32)  NULL        COMMENT 'identifiant Discord relié, jamais affiché',
    created_at       DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    last_login_at    DATETIME(3)  NULL        COMMENT 'la dernière connexion',
    PRIMARY KEY (id),
    UNIQUE KEY uq_web_accounts_player (player_xuid),
    UNIQUE KEY uq_web_accounts_xbox (xbox_xuid),
    UNIQUE KEY uq_web_accounts_discord (discord_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les comptes inscrits avant l'ouverture. Le Pionnier lui-même est donné par le jeu (player_cosmetics) :
-- le site ne note ici que le moment où le jeu le lui a dit.
CREATE TABLE waitlist (
    account_id        BIGINT UNSIGNED NOT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    pioneer_given_at  DATETIME(3)  NULL     COMMENT 'quand le jeu a donné le Pionnier ; nul tant qu''il ne l''a pas fait',
    PRIMARY KEY (account_id),
    CONSTRAINT fk_waitlist_account FOREIGN KEY (account_id) REFERENCES web_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les codes de « Relier Discord » : six caractères sans O, 0, I ni 1, dix minutes, un seul code vivant par compte.
-- Le joueur le tape en jeu ; un code consommé ou expiré est effacé.
CREATE TABLE discord_links (
    code         CHAR(6)         NOT NULL,
    account_id   BIGINT UNSIGNED NOT NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    expires_at   DATETIME(3)     NOT NULL,
    PRIMARY KEY (code),
    UNIQUE KEY uq_discord_links_account (account_id),
    KEY ix_discord_links_expires (expires_at),
    CONSTRAINT fk_discord_links_account FOREIGN KEY (account_id) REFERENCES web_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les empreintes admises en bêta, et qui les a admises. Une admission n'est jamais retirée.
CREATE TABLE beta_access (
    player_xuid  BIGINT UNSIGNED NOT NULL COMMENT 'empreinte du gamertag admis',
    admitted_by  VARCHAR(64)     NOT NULL COMMENT 'qui a admis : le nom d''une règle ou d''un membre du staff',
    admitted_at  DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (player_xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les sessions du site, trente jours. Le cookie ne porte que le jeton ; la base n'en garde que l'empreinte SHA-256.
CREATE TABLE web_sessions (
    token_hash   CHAR(64)        NOT NULL COMMENT 'SHA-256 du jeton du cookie, en hexadécimal',
    account_id   BIGINT UNSIGNED NOT NULL,
    csrf         VARCHAR(64)     NOT NULL COMMENT 'le jeton anti-CSRF des formulaires de cette session',
    created_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    expires_at   DATETIME(3)     NOT NULL,
    PRIMARY KEY (token_hash),
    KEY ix_web_sessions_expires (expires_at),
    KEY ix_web_sessions_account (account_id),
    CONSTRAINT fk_web_sessions_account FOREIGN KEY (account_id) REFERENCES web_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
