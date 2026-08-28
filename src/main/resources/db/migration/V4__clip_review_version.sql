-- Optimistic locking for concurrent review edits. Existing rows start at 0; a null version would make
-- Spring Data treat a loaded row as new and attempt an insert.
alter table clip_review add column version bigint not null default 0;
