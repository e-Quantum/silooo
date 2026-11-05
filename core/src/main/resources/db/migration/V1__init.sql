-- Silooo : schéma initial de la base « silooo » (MariaDB 12.3, utf8mb4, dates en UTC DATETIME(3)).
-- Mêmes règles que SileneMC : le schéma n'évolue que par migrations Flyway ; seul le compte migrateur
-- modifie le schéma, le service silooo-core utilise un compte applicatif (lecture/écriture des données).
-- Les définitions (usines, bâtiments, cosmétiques, missions) viennent de fichiers versionnés du dépôt :
-- la base ne stocke que leurs identifiants (factory_id, building_type, cosmetic_id…).
--
-- Monnaies : le Donar (monnaie de course, exponentielle) est un DOUBLE ; les Lingots, Gemmes et jetons
-- sont des entiers (BIGINT) qui ne doivent jamais être arrondis.

-- ------------------------------------------------------------------ joueurs

CREATE TABLE players (
    xuid            BIGINT UNSIGNED NOT NULL COMMENT 'Identifiant Xbox, clé du joueur',
    floodgate_uuid  UUID            NOT NULL COMMENT 'UUID que Floodgate déduit du XUID sur SileneMC',
    gamertag        VARCHAR(32)     NOT NULL,
    first_seen_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    last_seen_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    tutorial_step   SMALLINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (xuid),
    UNIQUE KEY uq_players_floodgate (floodgate_uuid),
    KEY ix_players_gamertag (gamertag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Grades à vie (MVIP → Ultime, dans l'ordre) et abonnement Pilier, calqués sur SileneMC.
CREATE TABLE player_ranks (
    xuid        BIGINT UNSIGNED NOT NULL,
    rank_id     VARCHAR(16)     NOT NULL COMMENT 'mvip, vip, vip+, heros, legende, ultime',
    granted_at  DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    source      VARCHAR(16)     NOT NULL COMMENT 'achat, cadeau, staff',
    PRIMARY KEY (xuid, rank_id),
    CONSTRAINT fk_ranks_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE player_subscriptions (
    xuid            BIGINT UNSIGNED NOT NULL,
    subscription_id VARCHAR(16)     NOT NULL COMMENT 'pilier, perso',
    started_at      DATETIME(3)     NOT NULL,
    ends_at         DATETIME(3)     NOT NULL,
    months_total    SMALLINT        NOT NULL DEFAULT 1 COMMENT 'pour le badge qui évolue',
    PRIMARY KEY (xuid, subscription_id),
    CONSTRAINT fk_subs_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ monnaies personnelles

-- Soldes courants ; toute variation passe par currency_ledger dans la même transaction.
CREATE TABLE player_balances (
    xuid        BIGINT UNSIGNED NOT NULL,
    lingots     BIGINT          NOT NULL DEFAULT 0,
    gemmes      BIGINT          NOT NULL DEFAULT 0,
    jetons      BIGINT          NOT NULL DEFAULT 0 COMMENT 'jetons de la zone AFK',
    cles        BIGINT          NOT NULL DEFAULT 0 COMMENT 'clés de lootbox',
    version     INT             NOT NULL DEFAULT 0 COMMENT 'verrou optimiste',
    PRIMARY KEY (xuid),
    CONSTRAINT fk_balances_player FOREIGN KEY (xuid) REFERENCES players (xuid),
    CONSTRAINT ck_balances_positive CHECK (lingots >= 0 AND gemmes >= 0 AND jetons >= 0 AND cles >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Journal de chaque mouvement : sert à l'audit, aux classements hebdomadaires (Lingots gagnés) et au support.
CREATE TABLE currency_ledger (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    xuid        BIGINT UNSIGNED NOT NULL,
    currency    ENUM('LINGOTS','GEMMES','JETONS','CLES') NOT NULL,
    delta       BIGINT          NOT NULL,
    balance     BIGINT          NOT NULL COMMENT 'solde après le mouvement',
    reason      VARCHAR(32)     NOT NULL COMMENT 'prestige, mission, commande, vote, zone, marche, boutique, livraison…',
    ref_id      VARCHAR(64)     NULL     COMMENT 'identifiant de ce qui a causé le mouvement',
    created_at  DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_ledger_player_time (xuid, created_at),
    KEY ix_ledger_reason_time (currency, reason, created_at),
    CONSTRAINT fk_ledger_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Gemmes achetées sur la boutique de SileneMC (PayNow). Le site n'a que le droit d'INSÉRER ici ;
-- silooo-core lit les lignes non traitées, crédite le solde (une seule fois grâce à order_id) et prévient le jeu.
CREATE TABLE shop_deliveries (
    order_id      VARCHAR(64)     NOT NULL COMMENT 'identifiant de commande PayNow',
    gamertag      VARCHAR(32)     NOT NULL COMMENT 'pseudo saisi sur le site',
    xuid          BIGINT UNSIGNED NULL     COMMENT 'rempli par silooo-core quand le pseudo est reconnu',
    gemmes        BIGINT          NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    delivered_at  DATETIME(3)     NULL,
    PRIMARY KEY (order_id),
    KEY ix_deliveries_pending (delivered_at, created_at),
    CONSTRAINT ck_deliveries_positive CHECK (gemmes > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Achats faits en jeu avec des Gemmes (cosmétiques, boosts, pass…).
CREATE TABLE shop_purchases (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    xuid        BIGINT UNSIGNED NOT NULL,
    item_id     VARCHAR(48)     NOT NULL,
    gemmes      BIGINT          NOT NULL,
    for_xuid    BIGINT UNSIGNED NULL COMMENT 'cadeau à un autre joueur',
    request_id  CHAR(36)        NOT NULL COMMENT 'idempotence : un même achat envoyé deux fois ne compte qu''une fois',
    created_at  DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchases_request (request_id),
    KEY ix_purchases_player (xuid, created_at),
    CONSTRAINT fk_purchases_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE player_cosmetics (
    xuid          BIGINT UNSIGNED NOT NULL,
    cosmetic_id   VARCHAR(48)     NOT NULL,
    source        VARCHAR(16)     NOT NULL COMMENT 'boutique, pass, lootbox, evenement, parrainage',
    acquired_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (xuid, cosmetic_id),
    CONSTRAINT fk_cosmetics_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ entreprises (solo ou jusqu'à 4 associés)

-- La grille de l'archipel : une case par entreprise, dans un seul monde.
CREATE TABLE archipelago_slots (
    slot_index    INT UNSIGNED    NOT NULL,
    origin_x      INT             NOT NULL,
    origin_z      INT             NOT NULL,
    allocated_at  DATETIME(3)     NULL,
    PRIMARY KEY (slot_index),
    UNIQUE KEY uq_slots_origin (origin_x, origin_z)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE companies (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name               VARCHAR(32)     NOT NULL,
    slot_index         INT UNSIGNED    NOT NULL,
    era                TINYINT         NOT NULL DEFAULT 1 COMMENT '1 artisanale … 4 futuriste',
    stars              INT             NOT NULL DEFAULT 0,
    course_number      INT             NOT NULL DEFAULT 1 COMMENT 'numéro de la course depuis la création',
    donar              DOUBLE          NOT NULL DEFAULT 0 COMMENT 'solde de la course, remis à zéro au prestige',
    donar_earned       DOUBLE          NOT NULL DEFAULT 0 COMMENT 'gagnés depuis le début de la course (conversion en Lingots)',
    course_started_at  DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    last_collect_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3) COMMENT 'dernier calcul de production (hors ligne compris)',
    mairie_level       TINYINT         NOT NULL DEFAULT 1,
    silo_level         SMALLINT        NOT NULL DEFAULT 1,
    offline_cap_hours  DECIMAL(4,1)    NOT NULL DEFAULT 8.0,
    reputation         INT             NOT NULL DEFAULT 0,
    slogan             VARCHAR(64)     NULL,
    logo_id            VARCHAR(48)     NULL,
    colors             VARCHAR(32)     NULL,
    version            INT             NOT NULL DEFAULT 0 COMMENT 'verrou optimiste : plusieurs associés jouent en même temps',
    created_at         DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_companies_name (name),
    UNIQUE KEY uq_companies_slot (slot_index),
    CONSTRAINT fk_companies_slot FOREIGN KEY (slot_index) REFERENCES archipelago_slots (slot_index),
    CONSTRAINT ck_companies_era CHECK (era BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Un joueur appartient à une seule entreprise ; le PDG fixe la part de chacun (somme = 100).
CREATE TABLE company_members (
    company_id     BIGINT UNSIGNED NOT NULL,
    xuid           BIGINT UNSIGNED NOT NULL,
    role           ENUM('PDG','ASSOCIE') NOT NULL,
    share_percent  TINYINT UNSIGNED NOT NULL COMMENT 'part des Lingots au prestige',
    joined_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (company_id, xuid),
    UNIQUE KEY uq_members_player (xuid),
    CONSTRAINT fk_members_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_members_player FOREIGN KEY (xuid) REFERENCES players (xuid),
    CONSTRAINT ck_members_share CHECK (share_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE company_invites (
    company_id   BIGINT UNSIGNED NOT NULL,
    xuid         BIGINT UNSIGNED NOT NULL,
    invited_by   BIGINT UNSIGNED NOT NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    expires_at   DATETIME(3)     NOT NULL,
    PRIMARY KEY (company_id, xuid),
    CONSTRAINT fk_invites_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_invites_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ usines et personnel

-- Le niveau regroupe salariés et améliorations (modèle du tableur) : les salariés anonymes se déduisent du niveau.
CREATE TABLE company_factories (
    company_id      BIGINT UNSIGNED NOT NULL,
    factory_id      VARCHAR(32)     NOT NULL COMMENT 'carriere, scierie, … (fichier de définitions)',
    state           ENUM('EN_RUINE','REMISE_EN_ETAT','ACTIVE') NOT NULL DEFAULT 'EN_RUINE',
    restore_step    TINYINT         NOT NULL DEFAULT 0 COMMENT '1 dialogue, 2 tâches, 3 livraison, 4 recrutement',
    level           INT             NOT NULL DEFAULT 0,
    skin_id         VARCHAR(48)     NULL,
    updated_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (company_id, factory_id),
    CONSTRAINT fk_factories_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_factories_level CHECK (level >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Petits incidents à réparer en mini-jeu : jamais de perte, jamais pendant l'absence.
CREATE TABLE factory_incidents (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id    BIGINT UNSIGNED NOT NULL,
    factory_id    VARCHAR(32)     NOT NULL,
    kind          VARCHAR(16)     NOT NULL COMMENT 'incendie, panne, courroie',
    slowdown      DECIMAL(3,2)    NOT NULL COMMENT 'production multipliée par ce facteur tant que non réparé',
    started_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    resolved_at   DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY ix_incidents_open (company_id, resolved_at),
    CONSTRAINT fk_incidents_factory FOREIGN KEY (company_id, factory_id) REFERENCES company_factories (company_id, factory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Le personnel nommé : chefs d'équipe, directeurs, salariés remarquables recrutés à l'agence.
CREATE TABLE staff (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id    BIGINT UNSIGNED NOT NULL,
    factory_id    VARCHAR(32)     NULL COMMENT 'null = pas encore affecté',
    name          VARCHAR(32)     NOT NULL,
    skin_id       VARCHAR(48)     NOT NULL,
    role          ENUM('SALARIE','CHEF','DIRECTEUR') NOT NULL,
    rarity        ENUM('COMMUN','RARE','EPIQUE') NOT NULL COMMENT '75 / 20 / 5 %',
    bonus_percent DECIMAL(5,2)    NOT NULL DEFAULT 0,
    housed        BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'logé en ville : salaire réduit',
    hired_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_staff_company (company_id, factory_id),
    CONSTRAINT fk_staff_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- L'agence d'emploi : 5 candidats par jour et par entreprise.
CREATE TABLE agency_candidates (
    company_id    BIGINT UNSIGNED NOT NULL,
    day           DATE            NOT NULL,
    slot          TINYINT         NOT NULL COMMENT '1 à 5',
    name          VARCHAR(32)     NOT NULL,
    skin_id       VARCHAR(48)     NOT NULL,
    role          ENUM('SALARIE','CHEF','DIRECTEUR') NOT NULL,
    rarity        ENUM('COMMUN','RARE','EPIQUE') NOT NULL,
    bonus_percent DECIMAL(5,2)    NOT NULL DEFAULT 0,
    hired_staff_id BIGINT UNSIGNED NULL,
    PRIMARY KEY (company_id, day, slot),
    CONSTRAINT fk_agency_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_agency_slot CHECK (slot BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ prestige

CREATE TABLE prestige_history (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id      BIGINT UNSIGNED NOT NULL,
    course_number   INT             NOT NULL,
    era_before      TINYINT         NOT NULL,
    stars_after     INT             NOT NULL,
    threshold       DOUBLE          NOT NULL COMMENT 'seuil de la course',
    donar_earned    DOUBLE          NOT NULL,
    lingots_total   BIGINT          NOT NULL COMMENT 'avant partage entre associés',
    duration_hours  DECIMAL(8,2)    NOT NULL COMMENT 'pour le tableau de bord d''équilibrage',
    decided_by      BIGINT UNSIGNED NOT NULL COMMENT 'le PDG',
    created_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_prestige_course (company_id, course_number),
    CONSTRAINT fk_prestige_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ ville

CREATE TABLE city_zones (
    company_id    BIGINT UNSIGNED NOT NULL,
    zone_index    TINYINT         NOT NULL COMMENT '1 à 16',
    bought_by     BIGINT UNSIGNED NOT NULL COMMENT 'l''associé qui a payé en Lingots',
    lingots_paid  BIGINT          NOT NULL,
    bought_at     DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (company_id, zone_index),
    CONSTRAINT fk_zones_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_zones_index CHECK (zone_index BETWEEN 1 AND 16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bâtiments préfabriqués posés avec l'outil de construction ; la décoration libre vit dans le monde, pas en base.
CREATE TABLE city_buildings (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id     BIGINT UNSIGNED NOT NULL,
    building_type  VARCHAR(32)     NOT NULL COMMENT 'mairie, silo, maison, immeuble, commerce, garage, banque, route…',
    level          SMALLINT        NOT NULL DEFAULT 1,
    zone_index     TINYINT         NOT NULL,
    x              INT             NOT NULL,
    z              INT             NOT NULL,
    rotation       SMALLINT        NOT NULL DEFAULT 0 COMMENT '0, 90, 180, 270',
    placed_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_buildings_company (company_id, building_type),
    CONSTRAINT fk_buildings_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_buildings_rotation CHECK (rotation IN (0, 90, 180, 270))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE company_vehicles (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id    BIGINT UNSIGNED NOT NULL,
    vehicle_type  VARCHAR(32)     NOT NULL COMMENT 'camion, bateau, avion-cargo, avion-perso',
    level         SMALLINT        NOT NULL DEFAULT 1,
    skin_id       VARCHAR(48)     NULL,
    bought_at     DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_vehicles_company (company_id),
    CONSTRAINT fk_vehicles_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Visites publiques et votes des plus belles villes (concours hebdomadaire).
CREATE TABLE city_votes (
    voter_xuid    BIGINT UNSIGNED NOT NULL,
    company_id    BIGINT UNSIGNED NOT NULL,
    week          CHAR(8)         NOT NULL COMMENT 'semaine ISO, ex. 2027-W05',
    stars         TINYINT         NOT NULL,
    voted_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (voter_xuid, company_id, week),
    KEY ix_votes_week (week, company_id),
    CONSTRAINT fk_votes_voter FOREIGN KEY (voter_xuid) REFERENCES players (xuid),
    CONSTRAINT fk_votes_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_votes_stars CHECK (stars BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ missions, commandes mondiales, marché

CREATE TABLE player_missions (
    xuid          BIGINT UNSIGNED NOT NULL,
    mission_id    VARCHAR(48)     NOT NULL,
    progress      INT             NOT NULL DEFAULT 0,
    completed_at  DATETIME(3)     NULL,
    PRIMARY KEY (xuid, mission_id),
    CONSTRAINT fk_missions_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Une commande mondiale par jour : tout le serveur livre ensemble.
CREATE TABLE world_orders (
    id              INT UNSIGNED    NOT NULL AUTO_INCREMENT,
    day             DATE            NOT NULL,
    resource_id     VARCHAR(32)     NOT NULL,
    target_quantity DOUBLE          NOT NULL,
    delivered       DOUBLE          NOT NULL DEFAULT 0,
    reward_lingots  BIGINT          NOT NULL,
    completed_at    DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE world_order_contributions (
    order_id     INT UNSIGNED    NOT NULL,
    xuid         BIGINT UNSIGNED NOT NULL,
    quantity     DOUBLE          NOT NULL DEFAULT 0,
    rewarded_at  DATETIME(3)     NULL,
    PRIMARY KEY (order_id, xuid),
    CONSTRAINT fk_contrib_order FOREIGN KEY (order_id) REFERENCES world_orders (id),
    CONSTRAINT fk_contrib_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Marché de ressources entre joueurs, en Lingots, taxé à 10 % (sortie d'argent).
CREATE TABLE market_listings (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    seller_xuid     BIGINT UNSIGNED NOT NULL,
    resource_id     VARCHAR(32)     NOT NULL,
    quantity        BIGINT          NOT NULL,
    remaining       BIGINT          NOT NULL,
    unit_price      BIGINT          NOT NULL COMMENT 'en Lingots',
    status          ENUM('OUVERTE','VENDUE','RETIREE') NOT NULL DEFAULT 'OUVERTE',
    created_at      DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_listings_open (status, resource_id, unit_price),
    CONSTRAINT fk_listings_seller FOREIGN KEY (seller_xuid) REFERENCES players (xuid),
    CONSTRAINT ck_listings_qty CHECK (quantity > 0 AND remaining >= 0 AND remaining <= quantity AND unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE market_trades (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    listing_id    BIGINT UNSIGNED NOT NULL,
    buyer_xuid    BIGINT UNSIGNED NOT NULL,
    quantity      BIGINT          NOT NULL,
    total_price   BIGINT          NOT NULL,
    tax           BIGINT          NOT NULL COMMENT '10 % détruits',
    created_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_trades_time (created_at),
    CONSTRAINT fk_trades_listing FOREIGN KEY (listing_id) REFERENCES market_listings (id),
    CONSTRAINT fk_trades_buyer FOREIGN KEY (buyer_xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ saison, votes, social

CREATE TABLE season_pass (
    xuid         BIGINT UNSIGNED NOT NULL,
    season_id    VARCHAR(16)     NOT NULL,
    points       INT             NOT NULL DEFAULT 0,
    premium      BOOLEAN         NOT NULL DEFAULT FALSE,
    PRIMARY KEY (xuid, season_id),
    CONSTRAINT fk_pass_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE season_claims (
    xuid         BIGINT UNSIGNED NOT NULL,
    season_id    VARCHAR(16)     NOT NULL,
    tier         SMALLINT        NOT NULL,
    track        ENUM('GRATUIT','PREMIUM') NOT NULL,
    claimed_at   DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (xuid, season_id, tier, track),
    CONSTRAINT fk_claims_pass FOREIGN KEY (xuid, season_id) REFERENCES season_pass (xuid, season_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE server_votes (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    xuid         BIGINT UNSIGNED NULL,
    gamertag     VARCHAR(32)     NOT NULL,
    site         VARCHAR(32)     NOT NULL,
    voted_at     DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    rewarded_at  DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY ix_votes_pending (rewarded_at, voted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE friends (
    xuid         BIGINT UNSIGNED NOT NULL,
    friend_xuid  BIGINT UNSIGNED NOT NULL,
    since        DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (xuid, friend_xuid),
    CONSTRAINT fk_friends_a FOREIGN KEY (xuid) REFERENCES players (xuid),
    CONSTRAINT fk_friends_b FOREIGN KEY (friend_xuid) REFERENCES players (xuid),
    CONSTRAINT ck_friends_self CHECK (xuid <> friend_xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------------ événements à afficher en jeu

-- server-net ne permet que des requêtes sortantes : l'add-on vient chercher ici ce qui s'est passé
-- (Gemmes reçues, commande terminée, prestige d'un associé…). Curseur = id croissant.
CREATE TABLE game_events (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    xuid          BIGINT UNSIGNED NULL COMMENT 'null = pour tout le serveur',
    kind          VARCHAR(32)     NOT NULL,
    payload       JSON            NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_events_player (xuid, id),
    CONSTRAINT ck_events_payload CHECK (JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Idempotence des appels de l'add-on : un appel rejoué (coupure, nouvel essai) renvoie la même réponse.
CREATE TABLE api_requests (
    request_id    CHAR(36)        NOT NULL,
    endpoint      VARCHAR(64)     NOT NULL,
    response      JSON            NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (request_id),
    KEY ix_requests_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
