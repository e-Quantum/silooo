create table discord_message_reports (
    id bigint not null auto_increment,
    reporter_id varchar(32) not null,
    channel_id varchar(32) not null,
    message_id varchar(32) not null,
    reason varchar(1000) not null,
    state varchar(24) not null,
    assigned_to varchar(32) null,
    post_channel_id varchar(32) null,
    post_message_id varchar(32) null,
    delivery_state varchar(16) not null default 'reserve',
    last_action_by varchar(32) null,
    closed_by varchar(32) null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    unique key uq_discord_message_reports_reporter_message (reporter_id, message_id),
    key ix_discord_message_reports_reporter_created (reporter_id, created_at),
    key ix_discord_message_reports_created (created_at)
) engine=InnoDB default character set utf8mb4 collate utf8mb4_unicode_ci;

create table discord_message_report_cooldowns (
    reporter_id varchar(32) not null,
    last_reported_at datetime(6) not null,
    primary key (reporter_id)
) engine=InnoDB default character set utf8mb4 collate utf8mb4_unicode_ci;
