-- findByContainerId runs on every container detail view, rename/remove
-- transfer, and schedule-conflict check - it must not seq-scan.
CREATE INDEX ix_container_schedule_container ON container_schedule (container_id);
