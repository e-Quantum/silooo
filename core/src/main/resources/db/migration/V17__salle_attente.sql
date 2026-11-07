-- La salle d'attente (docs/deploiement.md, « La salle d'attente ») : les records du saut infini.
-- V17 provisoire : la 0.1.4 n'est pas encore sur main ; à renuméroter au rebase (docs/passation.md).
--
-- Une ligne par joueur (XUID) : un gamertag qui change ne fait que mettre à jour son nom, jamais une seconde ligne.
-- Pas de clé étrangère vers players : un visiteur peut sauter sans avoir demandé de code ni joué.
CREATE TABLE lobby_records (
    xuid      BIGINT UNSIGNED NOT NULL COMMENT 'le joueur, clé du classement',
    gamertag  VARCHAR(32)     NOT NULL COMMENT 'le dernier gamertag vu',
    best      INT UNSIGNED    NOT NULL COMMENT 'le meilleur score de tous les temps',
    best_at   DATETIME(3)     NOT NULL COMMENT 'quand ce score a été atteint la première fois (UTC)',
    PRIMARY KEY (xuid),
    KEY ix_lobby_records_best (best, best_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Le meilleur score de chaque semaine, gardé après la semaine : le podium du lundi lit la semaine qui vient de finir,
-- même si le joueur a déjà rejoué depuis minuit. La semaine part du lundi, dans le fuseau de definitions/attente.json.
CREATE TABLE lobby_week_bests (
    week_start DATE            NOT NULL COMMENT 'le lundi de la semaine',
    xuid       BIGINT UNSIGNED NOT NULL,
    gamertag   VARCHAR(32)     NOT NULL COMMENT 'le dernier gamertag vu cette semaine',
    best       INT UNSIGNED    NOT NULL,
    best_at    DATETIME(3)     NOT NULL COMMENT 'UTC',
    PRIMARY KEY (week_start, xuid),
    KEY ix_lobby_week_bests_best (week_start, best, best_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Le podium du lundi d'Oooscar, une fois par semaine : la ligne se réserve avant l'envoi (pas de doublon au redémarrage).
CREATE TABLE lobby_week_posts (
    week_start DATE        NOT NULL COMMENT 'le lundi de la semaine postée',
    posted_at  DATETIME(3) NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
