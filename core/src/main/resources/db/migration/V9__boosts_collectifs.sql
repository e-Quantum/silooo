-- Les boosts collectifs de la boutique : un joueur paie des Gemmes, la production de tout le serveur monte de bonus_percent
-- entre starts_at (inclus) et ends_at (exclu). Un boost acheté pendant un autre commence à la fin du dernier boost réservé : le
-- service les enchaîne (il verrouille la fin du dernier avant d'écrire), ils ne s'empilent jamais. Le moteur lit celui qui court
-- maintenant ; les lignes échues restent comme historique. Écrite par buyItem, jamais modifiée.
CREATE TABLE server_boosts (
    id             BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    bought_by      BIGINT UNSIGNED   NOT NULL COMMENT 'l''acheteur, annoncé aux joueurs',
    starts_at      DATETIME(3)       NOT NULL,
    ends_at        DATETIME(3)       NOT NULL,
    bonus_percent  SMALLINT UNSIGNED NOT NULL COMMENT 'hausse de la production du serveur, en pourcent, figée à l''achat',
    created_at     DATETIME(3)       NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_boosts_window (ends_at, starts_at),
    KEY ix_boosts_buyer (bought_by, created_at),
    CONSTRAINT fk_boosts_player FOREIGN KEY (bought_by) REFERENCES players (xuid),
    CONSTRAINT ck_boosts_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_boosts_percent CHECK (bonus_percent > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
