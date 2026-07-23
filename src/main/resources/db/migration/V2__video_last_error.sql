-- Surfaces why a pipeline stage stopped; the row otherwise just stops advancing with no explanation.
alter table video add column last_error varchar(1024);
