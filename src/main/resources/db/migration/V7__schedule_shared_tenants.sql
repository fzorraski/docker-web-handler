-- Schedules join dumps and snapshots in being shareable across tenants: the
-- owning tenant stays in tenant_id, and the tenants it was shared with go here.
-- A shared schedule is managed, not just read - the same rule the container
-- guard applies - so this list grants edit, disable and execute-now as well.
ALTER TABLE container_schedule ADD COLUMN shared_with_tenants jsonb;

-- Existing rows keep NULL, which reads back as an empty list: nothing shared,
-- exactly the behaviour they had before the column existed. No backfill is
-- possible or wanted - sharing is a deliberate act by the owning tenant.
