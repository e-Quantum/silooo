-- Le curseur du suiveur Discord : jusqu'où game_events a été lu pour les messages du serveur.
-- Une seule ligne (id = 1). Sans elle, un redémarrage de silooo-core rejouerait sur Discord des événements déjà annoncés.
-- Elle n'existe pas avant le premier passage du suiveur, qui la pose alors sur le dernier événement : rien d'ancien n'est envoyé.
CREATE TABLE discord_cursor (
    id             TINYINT UNSIGNED NOT NULL COMMENT 'toujours 1 : une seule ligne',
    last_event_id  BIGINT UNSIGNED  NOT NULL COMMENT 'dernier game_events.id lu par le suiveur',
    updated_at     DATETIME(3)      NOT NULL DEFAULT UTC_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_discord_cursor_single CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
