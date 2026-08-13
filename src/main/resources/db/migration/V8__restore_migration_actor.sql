-- Who performed the last restore and who ran a migration, next to the
-- timestamps that already exist. Same source as created_by (ActorResolver):
-- the username under RBAC, the CI service name, "system" for workers, and
-- NULL in legacy password mode where there is no identity to record.
ALTER TABLE managed_database ADD COLUMN last_restored_by text;
ALTER TABLE database_migration ADD COLUMN migrated_by text;

-- Rows written before this migration keep NULL: the actor was never recorded,
-- and attributing old restores to whoever looks at them now would be wrong.
