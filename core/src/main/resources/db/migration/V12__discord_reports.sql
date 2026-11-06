-- Mesures reçues par le bot, sans contenu des messages. Les événements manqués hors ligne ne sont pas reconstruits.
CREATE TABLE discord_report_capture (
    id TINYINT NOT NULL PRIMARY KEY,
    started_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE discord_report_events (
    kind VARCHAR(16) NOT NULL,
    event_id VARCHAR(96) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    PRIMARY KEY (kind, event_id),
    KEY ix_report_time (occurred_at),
    KEY ix_report_member (user_id, kind, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Réservation avant l'envoi : au plus une tentative par semaine, même après un redémarrage.
CREATE TABLE discord_report_claims (
    week_start DATE NOT NULL PRIMARY KEY,
    claimed_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'attempting'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
