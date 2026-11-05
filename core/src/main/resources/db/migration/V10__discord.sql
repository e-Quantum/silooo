-- Le Discord de Silooo, tenu par le bot Oooscar (ADR 0007, docs/discord.md). Seul le bot écrit ici.
-- Un identifiant Discord ne s'affiche jamais ; il sert à retrouver un membre, jamais à le nommer.
-- Toutes les dates sont en UTC ; les heures du jour (nuit, rapport du lundi) se calculent dans le fuseau
-- de definitions/discord.json.

-- Les membres : l'arrivée, l'accueil guidé, le bonjour, le tuto, le départ. Nourrit le rapport du lundi.
CREATE TABLE discord_members (
    discord_user_id  VARCHAR(32)  NOT NULL,
    first_joined_at  DATETIME(3)  NOT NULL COMMENT 'première arrivée ; un retour ne la change pas',
    joined_at        DATETIME(3)  NOT NULL COMMENT 'dernière arrivée',
    onboarded_at     DATETIME(3)  NULL     COMMENT 'fin de l''accueil guidé',
    platform         VARCHAR(16)  NULL     COMMENT 'console, telephone ou pc : le rôle choisi à l''accueil',
    greeted_at       DATETIME(3)  NULL     COMMENT 'le bonjour d''Oooscar posté',
    tutorial_at      DATETIME(3)  NULL     COMMENT 'le tuto envoyé en privé',
    tutorial_refused BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'messages privés fermés : le bouton Mon tuto reste',
    left_at          DATETIME(3)  NULL     COMMENT 'départ ; nul tant que le membre est là',
    PRIMARY KEY (discord_user_id),
    KEY ix_discord_members_joined (joined_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les messages du bot qui vivent (tableaux, le monte-charge, le chantier) : modifiés sur place, jamais repostés,
-- même après un redémarrage. La clé est celle que le code donne au message (« monte-charge », « chantier »).
CREATE TABLE discord_bot_messages (
    message_key  VARCHAR(64)  NOT NULL,
    channel_id   VARCHAR(32)  NOT NULL,
    message_id   VARCHAR(32)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (message_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- L'XP de tchat : le total, le niveau atteint, les points de la semaine (remis à zéro le lundi).
CREATE TABLE discord_chat_xp (
    discord_user_id  VARCHAR(32)  NOT NULL,
    points           BIGINT       NOT NULL DEFAULT 0,
    chat_level       INT          NOT NULL DEFAULT 0 COMMENT 'le dernier niveau franchi',
    week_points      BIGINT       NOT NULL DEFAULT 0,
    week_start       DATE         NOT NULL COMMENT 'le lundi de la semaine comptée',
    last_counted_at  DATETIME(3)  NULL     COMMENT 'le dernier message qui a compté : un seul par intervalle',
    updated_at       DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (discord_user_id),
    KEY ix_discord_chat_xp_week (week_start, week_points)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les messages qui ont compté, pour reprendre leurs points s'ils sont supprimés. Purgés après la semaine.
CREATE TABLE discord_xp_messages (
    message_id       VARCHAR(32)  NOT NULL,
    discord_user_id  VARCHAR(32)  NOT NULL,
    points           INT          NOT NULL,
    counted_at       DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (message_id),
    KEY ix_discord_xp_messages_counted (counted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les coulisses : les spoils déposés par le staff, publiés l'un après l'autre quand le précédent a assez de réactions.
CREATE TABLE discord_spoils (
    spoil_number    INT          NOT NULL COMMENT 'l''ordre de publication, à partir de 1',
    spoil_text      TEXT         NOT NULL,
    image_name      VARCHAR(64)  NULL,
    image           MEDIUMBLOB   NULL     COMMENT 'le fichier déposé avec /spoil, 16 Mo au plus',
    next_threshold  INT          NOT NULL COMMENT 'les réactions qui publient le spoil suivant',
    created_by      VARCHAR(32)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    message_id      VARCHAR(32)  NULL     COMMENT 'le message publié ; nul tant qu''il attend',
    published_at    DATETIME(3)  NULL,
    reactions       INT          NOT NULL DEFAULT 0 COMMENT 'le dernier compte des membres qui ont réagi',
    PRIMARY KEY (spoil_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Le top réactions de la semaine : combien de réactions chaque membre a données, une par message.
CREATE TABLE discord_reactions (
    week_start       DATE         NOT NULL COMMENT 'le lundi de la semaine',
    discord_user_id  VARCHAR(32)  NOT NULL,
    reactions_given  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (week_start, discord_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les messages où chaque membre a déjà été compté, pour qu'une réaction par message suffise. Purgés après la semaine.
CREATE TABLE discord_reaction_marks (
    message_id       VARCHAR(32)  NOT NULL,
    discord_user_id  VARCHAR(32)  NOT NULL,
    marked_at        DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (message_id, discord_user_id),
    KEY ix_discord_reaction_marks_marked (marked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les meilleurs moments : un message recopié une seule fois, quand assez de membres ont réagi.
CREATE TABLE discord_highlights (
    message_id         VARCHAR(32)  NOT NULL COMMENT 'le message d''origine',
    channel_id         VARCHAR(32)  NOT NULL,
    author_id          VARCHAR(32)  NOT NULL,
    repost_message_id  VARCHAR(32)  NOT NULL COMMENT 'la copie dans les meilleurs moments',
    reposted_at        DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- L'Aide du mois : combien de réponses retenues (bouton « Résolu par ») chaque membre a par mois.
CREATE TABLE discord_help (
    month_start      DATE         NOT NULL COMMENT 'le premier jour du mois',
    discord_user_id  VARCHAR(32)  NOT NULL,
    answers          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (month_start, discord_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les sujets d'aide déjà résolus : un seul « Résolu par » par sujet.
CREATE TABLE discord_help_marks (
    thread_id    VARCHAR(32)  NOT NULL COMMENT 'le sujet du forum d''aide',
    resolver_id  VARCHAR(32)  NOT NULL COMMENT 'le membre désigné',
    marked_by    VARCHAR(32)  NOT NULL COMMENT 'qui a appuyé : l''auteur du sujet ou le staff',
    marked_at    DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les petits jeux : où en est chaque jeu (le nombre du comptage, l'étage du monte-charge) et qui a joué en dernier.
CREATE TABLE discord_games (
    game           VARCHAR(32)  NOT NULL COMMENT 'comptage ou monte-charge',
    current_value  BIGINT       NOT NULL DEFAULT 0,
    last_user_id   VARCHAR(32)  NULL,
    updated_at     DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (game)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les records des petits jeux, rappelés par Oooscar et affichés au Panthéon.
CREATE TABLE discord_records (
    game        VARCHAR(32)  NOT NULL,
    best        BIGINT       NOT NULL,
    holder_id   VARCHAR(32)  NULL     COMMENT 'le membre qui a posé le record, s''il y en a un seul',
    reached_at  DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (game)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les giveaways : un bouton pour participer, un tirage à la fin.
CREATE TABLE discord_giveaways (
    id            INT          NOT NULL AUTO_INCREMENT,
    title         VARCHAR(80)  NOT NULL,
    prize_kind    VARCHAR(16)  NOT NULL COMMENT 'cosmetique, gemmes ou lingots',
    prize_amount  BIGINT       NOT NULL DEFAULT 0 COMMENT 'le nombre de Gemmes ou de Lingots ; 0 pour un cosmétique',
    prize_label   VARCHAR(80)  NULL     COMMENT 'le cosmétique, par son identifiant de definitions/',
    channel_id    VARCHAR(32)  NOT NULL,
    message_id    VARCHAR(32)  NULL     COMMENT 'la carte du giveaway, une fois postée',
    ends_at       DATETIME(3)  NOT NULL,
    drawn_at      DATETIME(3)  NULL,
    winner_id     VARCHAR(32)  NULL,
    created_by    VARCHAR(32)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_discord_giveaways_ends (ends_at, drawn_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_giveaway_entries (
    giveaway_id      INT          NOT NULL,
    discord_user_id  VARCHAR(32)  NOT NULL,
    entered_at       DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (giveaway_id, discord_user_id),
    CONSTRAINT fk_discord_giveaway_entries_giveaway FOREIGN KEY (giveaway_id) REFERENCES discord_giveaways (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les lots gagnés, gardés sur l'identifiant Discord et crédités en jeu à la liaison prouvée.
CREATE TABLE discord_prizes (
    id               INT          NOT NULL AUTO_INCREMENT,
    discord_user_id  VARCHAR(32)  NOT NULL,
    giveaway_id      INT          NULL,
    prize_kind       VARCHAR(16)  NOT NULL,
    prize_amount     BIGINT       NOT NULL DEFAULT 0,
    prize_label      VARCHAR(80)  NULL,
    won_at           DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    delivered_at     DATETIME(3)  NULL     COMMENT 'crédité en jeu',
    delivered_to     VARCHAR(32)  NULL     COMMENT 'le xuid crédité',
    PRIMARY KEY (id),
    KEY ix_discord_prizes_pending (discord_user_id, delivered_at),
    CONSTRAINT fk_discord_prizes_giveaway FOREIGN KEY (giveaway_id) REFERENCES discord_giveaways (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les tickets : un salon privé par demande, archivé dans le journal à la fermeture.
CREATE TABLE discord_tickets (
    id               INT          NOT NULL AUTO_INCREMENT,
    discord_user_id  VARCHAR(32)  NOT NULL,
    kind             VARCHAR(32)  NOT NULL COMMENT 'le choix du menu : jeu, achat, compte, sanction, createurs',
    channel_id       VARCHAR(32)  NOT NULL,
    opened_at        DATETIME(3)  NOT NULL DEFAULT UTC_TIMESTAMP(3),
    closed_at        DATETIME(3)  NULL,
    closed_by        VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY ix_discord_tickets_open (discord_user_id, closed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les cartes d'admission : une par inscription de site_registrations, avec la décision du staff.
CREATE TABLE discord_admissions (
    gamertag_key  VARCHAR(32)  NOT NULL COMMENT 'la clé de site_registrations',
    message_id    VARCHAR(32)  NULL     COMMENT 'la carte postée dans le salon des admissions',
    decision      VARCHAR(16)  NOT NULL DEFAULT 'attente' COMMENT 'attente, admis, refuse ou plus-tard',
    decided_by    VARCHAR(32)  NULL,
    decided_at    DATETIME(3)  NULL,
    PRIMARY KEY (gamertag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Les compteurs du jour pour le rapport du lundi : messages, messages privés échoués, signalements.
CREATE TABLE discord_counters (
    day_utc  DATE         NOT NULL,
    counter  VARCHAR(32)  NOT NULL,
    total    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (day_utc, counter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
