-- Étend le journal existant sans modifier les dossiers ni les migrations déjà appliquées.
ALTER TABLE discord_moderation_cases
    DROP CONSTRAINT ck_discord_moderation_action,
    ADD CONSTRAINT ck_discord_moderation_action
        CHECK (action IN ('warn', 'timeout', 'restore', 'kick', 'ban', 'unban'));
