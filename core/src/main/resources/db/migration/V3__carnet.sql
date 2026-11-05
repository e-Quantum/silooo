-- Le Carnet de trouvailles : une ligne par ressource que le joueur a vue sortir d'une de ses usines.
-- finding_id est un identifiant de ressource de definitions/usines.json (liste produit), comme world_orders.resource_id.
-- La clé primaire est le garde-fou : une trouvaille ne s'inscrit, et ne rapporte ses Lingots, qu'une seule fois.
CREATE TABLE player_findings (
    xuid        BIGINT UNSIGNED NOT NULL,
    finding_id  VARCHAR(32)     NOT NULL,
    found_at    DATETIME(3)     NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (xuid, finding_id),
    CONSTRAINT fk_findings_player FOREIGN KEY (xuid) REFERENCES players (xuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
