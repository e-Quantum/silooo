-- Prise en charge des tickets par le staff, conservée au redémarrage.
alter table discord_tickets
    add column assigned_to varchar(32) null,
    add column claimed_at datetime(3) null;
