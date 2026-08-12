-- The audit trail becomes tenant-scoped: an admin without TENANTS_VIEW_ALL
-- sees only entries stamped with one of their own tenants. A NULL tenant means
-- the action could not be attributed to a tenant (system job, super admin,
-- RBAC disabled) and is visible to cross-tenant readers only.
ALTER TABLE audit_log ADD COLUMN tenant_id text;

-- Serves "tenant_id IN (...) ORDER BY occurred_at DESC" for tenant-scoped
-- readers. ix_audit_log_occurred still serves cross-tenant readers and the
-- retention DELETE, which filters on occurred_at alone.
CREATE INDEX ix_audit_log_tenant_occurred ON audit_log (tenant_id, occurred_at DESC);

-- No foreign key to tenant(id) on purpose: an audit row must survive the
-- deletion of its tenant. A cascade would erase history and SET NULL would
-- silently reclassify entries as cross-tenant-only.
--
-- Rows written before this migration keep tenant_id NULL and are therefore
-- visible to cross-tenant readers only. They are NOT backfilled: the only
-- available source would be the actor's memberships *today*, which would
-- attribute historical actions to tenants the actor may have joined since.
