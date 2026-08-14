-- Who armed "delete database on expiration" and which tenant the container
-- belongs to. The expiration timer runs outside any request scope, so without
-- these the automatic database drop could not be written to the audit trail
-- with a real actor, and the entry would stay invisible to the tenant's admins.
ALTER TABLE container_expiration ADD COLUMN deletion_armed_by text;
ALTER TABLE container_expiration ADD COLUMN tenant_id text;

-- Rows written before this migration keep NULL: the arming actor was never
-- recorded, and attributing old records to anyone now would be wrong.
