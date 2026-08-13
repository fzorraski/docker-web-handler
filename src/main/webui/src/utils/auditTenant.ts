/**
 * Label for an audit entry's tenant.
 *
 * The API serialises with JSON-B, which omits null properties entirely, so an
 * entry belonging to no tenant arrives with **no** `tenantId` key rather than
 * an explicit null. Comparing with `=== null` therefore misses it and the row
 * renders an empty chip; every check here is `== null` on purpose.
 */
export function resolveTenantLabel(
  tenantId: string | null | undefined,
  tenants: Map<string, { name: string }>,
  systemLabel: string,
): string {
  if (tenantId == null || tenantId === '') {
    return systemLabel
  }
  return tenants.get(tenantId)?.name ?? tenantId
}

/** Whether this entry belongs to no tenant (system or super-admin action). */
export function isSystemEntry(tenantId: string | null | undefined): boolean {
  return tenantId == null || tenantId === ''
}
