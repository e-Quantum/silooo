-- Fermeture reprenable : les pages sont durables avant tout envoi ou suppression du salon.
create table discord_ticket_archives (
    archive_id char(36) character set ascii collate ascii_bin not null,
    ticket_id int not null,
    requested_by varchar(32) not null,
    requested_at datetime(3) not null,
    channel_id varchar(32) not null,
    state varchar(16) character set ascii collate ascii_bin not null,
    before_id varchar(32) null,
    after_id varchar(32) null,
    head_id varchar(32) null,
    next_part int not null default 0,
    revision bigint not null default 0,
    lease_token char(36) character set ascii collate ascii_bin null,
    lease_until datetime(3) null,
    finished_at datetime(3) null,
    last_error varchar(64) null,
    primary key (archive_id),
    unique key uq_discord_ticket_archive (ticket_id),
    key ix_discord_ticket_archive_state (state, finished_at),
    constraint ck_discord_ticket_archive_state check (state in ('collecting','delivering','deleting','done','purging'))
) engine=InnoDB default character set utf8mb4 collate utf8mb4_unicode_ci;

create table discord_ticket_archive_parts (
    archive_id char(36) character set ascii collate ascii_bin not null,
    part_number int not null,
    body mediumtext null,
    sending boolean not null default false,
    anchor_id varchar(32) null,
    message_id varchar(32) null,
    primary key (archive_id, part_number),
    constraint fk_discord_ticket_archive_part foreign key (archive_id)
        references discord_ticket_archives (archive_id) on delete cascade
) engine=InnoDB default character set utf8mb4 collate utf8mb4_unicode_ci;
