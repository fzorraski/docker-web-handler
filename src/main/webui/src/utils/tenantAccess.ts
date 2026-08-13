/**
 * Selection logic for the combined tenant access control (owner + shared-with
 * in a single multi-select).
 *
 * The value is one ordered array whose FIRST element is the owning tenant and
 * whose remainder is the shared-with list. Keeping the owner at index 0 makes
 * "the owner never appears in sharedWithTenants" structural: the backend stores
 * the shared list verbatim (DumpStorageService) and only de-duplicates it, so a
 * leaked owner id would persist on the record forever.
 *
 * An empty array means no tenant at all, which the backend treats as visible to
 * everyone - including tenants created later.
 */

/** Menu row that clears the selection. Never present in the value. */
export const NONE_OPTION = ''
/** Menu row that ticks every offered tenant. Never present in the value. */
export const ALL_OPTION = '__all_tenants__'

export interface TenantAccessRules {
  /** Tenant ids currently offered: every tenant, or just the user's own. */
  optionIds: string[]
  /** TENANTS_VIEW_ALL holders may own any tenant and may clear the selection. */
  canViewAll: boolean
  /** The user's own memberships, in the order the API returned them. */
  ownTenantIds: string[]
}

/** The selection to start from: none for an admin, the first own tenant otherwise. */
export function defaultTenantSelection(rules: TenantAccessRules): string[] {
  if (rules.canViewAll || rules.ownTenantIds.length === 0) {
    return []
  }
  return [rules.ownTenantIds[0]]
}

/** Whether every offered tenant is currently selected. */
export function isAllSelected(ids: string[], rules: TenantAccessRules): boolean {
  return rules.optionIds.length > 0 && rules.optionIds.every((id) => ids.includes(id))
}

/**
 * Splits the ordered selection into the fields the API expects.
 *
 * `noTenant` is what makes "visible to everyone" stick: an omitted tenantId
 * alone means "the caller said nothing", which the backend answers with the
 * actor's own first membership. Pass `allowNone` only when the user was
 * actually offered the choice (a TENANTS_VIEW_ALL holder) - the backend
 * rejects the flag from anyone else, and a hidden selector must not send it.
 */
export function splitOwner(
  ids: string[],
  allowNone = false,
): { tenantId?: string; sharedWithTenants: string[]; noTenant?: boolean } {
  if (ids.length === 0) {
    return { tenantId: undefined, sharedWithTenants: [], noTenant: allowNone || undefined }
  }
  return { tenantId: ids[0], sharedWithTenants: ids.slice(1) }
}

/**
 * Folds the post-click set MUI hands us back into the ordered array.
 *
 * Deliberately delta-based rather than trusting the incoming order: the two
 * menu sentinels have to be intercepted anyway, and rebuilding from `prev`
 * keeps ownership independent of how MUI happens to order its value array.
 * Note that the sentinels are never part of `prev`, so clicking one always
 * arrives as an addition - even when its checkbox renders as ticked.
 */
export function reduceTenantSelection(
  prev: string[],
  next: string[],
  rules: TenantAccessRules,
): string[] {
  const added = next.filter((id) => !prev.includes(id))

  let result: string[]

  if (added.includes(NONE_OPTION)) {
    // members must own what they create, so the row is never offered to them
    if (!rules.canViewAll) return prev
    result = []
  } else if (added.includes(ALL_OPTION)) {
    if (isAllSelected(prev, rules)) {
      result = defaultTenantSelection(rules)
    } else {
      // keep whoever already owns it - selecting everything must not silently
      // hand ownership (and the right to edit sharing later) to optionIds[0]
      const owner = prev[0] ?? defaultTenantSelection(rules)[0] ?? rules.optionIds[0]
      result = owner === undefined
        ? [...rules.optionIds]
        : [owner, ...rules.optionIds.filter((id) => id !== owner)]
    }
  } else {
    // ordinary tick/untick: keep prev's order, append what is new. Unticking
    // the owner promotes the next id for free - every offered option is
    // ownable, both for a member (own tenants only) and for an admin.
    result = [...prev.filter((id) => next.includes(id)), ...added]
  }

  result = result.filter(
    (id, i, arr) => id !== NONE_OPTION && id !== ALL_OPTION && arr.indexOf(id) === i,
  )

  // unticking the last row is a no-op rather than a flicker off and back on
  if (!rules.canViewAll && result.length === 0) {
    return prev
  }
  return result
}
