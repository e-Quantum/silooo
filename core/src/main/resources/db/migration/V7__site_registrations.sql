-- Les inscriptions du site silooo.com : pionniers, compte Discord relié, admission lue par le jeu (join).
-- Le site est le seul à écrire ici. Le jeu lit admitted_at : non nul = admis, nul = join refuse le joueur.
-- La clé est le gamertag en minuscules, espaces réduits à un seul (SiteRegistrations.key dans le code).
CREATE TABLE site_registrations (
    gamertag_key     VARCHAR(32)  NOT NULL COMMENT 'gamertag en minuscules, espaces simples : la clé',
    gamertag         VARCHAR(32)  NOT NULL COMMENT 'tel que le joueur l''a écrit',
    discord_user_id  VARCHAR(32)  NULL     COMMENT 'identifiant Discord relié, jamais affiché',
    pioneer          BOOLEAN      NOT NULL DEFAULT TRUE COMMENT 'inscrit avant l''ouverture',
    created_at       DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    linked_at        DATETIME(3)  NULL     COMMENT 'quand le compte Discord a été relié',
    admitted_at      DATETIME(3)  NULL     COMMENT 'non nul = admis : le jeu refuse les autres dans join',
    PRIMARY KEY (gamertag_key),
    UNIQUE KEY uq_site_registrations_discord (discord_user_id),
    KEY ix_site_registrations_admitted (admitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
