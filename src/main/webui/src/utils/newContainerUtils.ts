export interface ArmDbDeletionContext {
  /** DATABASE_DELETE: may delete any database. */
  canDelete: boolean
  /** DATABASE_DELETE_OWN: may delete only databases the user created. */
  canDeleteOwn: boolean
  dbMode: 'existing' | 'restore'
  /** Restore tab: whether the typed target name matches an existing database. */
  restoreDbExists: boolean
  /** Restore tab: the "create database" switch - the backend's creatingIt argument. */
  createDatabase: boolean
  /** From the conflicts fetch; undefined while loading. */
  createdByMe: boolean | undefined
}

/**
 * Whether the "Delete database on expiration" toggle may be offered at all.
 *
 * Arming deletion is a deferred drop, so it follows the deletion permissions,
 * mirroring the backend's canArmDeletion exactly. A delete-own holder qualifies
 * for a database that does not exist yet ONLY while the "create database"
 * switch is on - that switch is what the backend receives as creatingIt, and a
 * new name with the switch off would be refused at prepare time. An EXISTING
 * name follows ownership regardless of the switch: flipping it on cannot
 * reveal the toggle for someone else's database.
 * `createdByMe === true` is strict on purpose: while the conflicts fetch is in
 * flight (undefined) the toggle stays hidden rather than flickering.
 */
export function canArmDbDeletion({ canDelete, canDeleteOwn, dbMode, restoreDbExists, createDatabase, createdByMe }: ArmDbDeletionContext): boolean {
  if (canDelete) {
    return true
  }
  if (!canDeleteOwn) {
    return false
  }
  if (dbMode === 'restore' && !restoreDbExists) {
    return createDatabase
  }
  return createdByMe === true
}
