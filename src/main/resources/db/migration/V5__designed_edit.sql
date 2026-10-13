CREATE TABLE designed_edit (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    clip_index INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_error TEXT,
    version BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_video_clip UNIQUE (video_id, clip_index)
);

CREATE INDEX idx_designed_edit_video ON designed_edit(video_id);
