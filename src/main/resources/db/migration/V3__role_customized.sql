-- A super admin may edit the built-in roles. The startup bootstrap re-seeds
-- built-in roles so new catalog permissions reach them; without this flag it
-- would silently undo those edits on every restart.
ALTER TABLE role ADD COLUMN customized boolean NOT NULL DEFAULT false;
