-- Later feature tables (clip, generated content, ...) arrive with their own features.
create table video (
    id               uuid          primary key,
    youtube_id       varchar(16)   not null unique,
    source_url       varchar(512)  not null,
    title            varchar(512)  not null,
    duration_seconds integer       not null,
    uploader         varchar(255),
    status           varchar(32)   not null,
    ingested_at      timestamp(6) with time zone not null
);
