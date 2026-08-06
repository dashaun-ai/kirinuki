create table clip_review (
    id         uuid         primary key,
    video_id   uuid         not null references video(id),
    clip_index integer      not null,
    status     varchar(16)  not null,
    content    text         not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    unique (video_id, clip_index)
);
