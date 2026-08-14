import type { ManagedDatabaseInfo, ServerHealth, DatabaseHealthInfo, DatabaseActivity, DatabaseTableStats, QueryResult, TopQuery } from '../types'
import fetchWithAuth, { apiErrorMessage, handleJsonResponse } from './fetchWithAuth'

const API = '/api/database/managed/'

export async function isManagedDatabasesEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'enabled')
  if (!res.ok) return false
  return res.json()
}

export async function getManagedDatabaseRepositories(): Promise<string[]> {
  const res = await fetchWithAuth(API + 'repositories')
  if (!res.ok) return []
  return res.json()
}

export async function getDatabaseActivity(repository: string, name: string): Promise<DatabaseActivity | null> {
  const res = await fetchWithAuth(API + 'activity/' + encodeURIComponent(repository) + '/' + encodeURIComponent(name))
  if (!res.ok) return null
  return res.json()
}

export async function getDatabaseTableStats(repository: string, name: string): Promise<DatabaseTableStats | null> {
  const res = await fetchWithAuth(API + 'tables/' + encodeURIComponent(repository) + '/' + encodeURIComponent(name))
  if (!res.ok) return null
  return res.json()
}

export async function getTopQueriesForTable(
  repository: string,
  databaseName: string,
  tableName: string,
  signal?: AbortSignal,
): Promise<TopQuery[]> {
  try {
    const res = await fetchWithAuth(
      API + 'queries/' + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName) + '/' + encodeURIComponent(tableName),
      signal ? { signal } : undefined,
    )
    if (!res.ok) return []
    return res.json()
  } catch {
    return []
  }
}

export async function getTopTempFileQueries(
  repository: string,
  databaseName: string,
  signal?: AbortSignal,
): Promise<TopQuery[]> {
  try {
    const res = await fetchWithAuth(
      API + 'temp-queries/' + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName),
      signal ? { signal } : undefined,
    )
    if (!res.ok) return []
    return res.json()
  } catch {
    return []
  }
}

export async function getDatabaseDetails(repository: string, name: string): Promise<{
  health: DatabaseHealthInfo | null
  activity: DatabaseActivity | null
  tableStats: DatabaseTableStats | null
} | null> {
  const res = await fetchWithAuth(API + 'details/' + encodeURIComponent(repository) + '/' + encodeURIComponent(name))
  if (!res.ok) return null
  return res.json()
}

export async function enablePgStatStatements(
  repository: string,
  name: string,
  password: string,
): Promise<{ success: boolean; alreadyInstalled?: boolean; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(name) + '/enable-pgss',
    {
      method: 'POST',
      headers: { 'X-Dump-Password': password },
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  const data = await res.json()
  return { success: true, alreadyInstalled: data.alreadyInstalled }
}

export async function resetQueryStats(
  repository: string,
  name: string,
  password: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(name) + '/reset-query-stats',
    {
      method: 'POST',
      headers: { 'X-Dump-Password': password },
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  return { success: true }
}

export async function resetTableStats(
  repository: string,
  name: string,
  password: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(name) + '/reset-table-stats',
    {
      method: 'POST',
      headers: { 'X-Dump-Password': password },
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  return { success: true }
}

export async function resetSingleTableStats(
  repository: string,
  databaseName: string,
  schemaName: string,
  tableName: string,
  password: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName)
      + '/reset-table-stats/' + encodeURIComponent(schemaName) + '/' + encodeURIComponent(tableName),
    {
      method: 'POST',
      headers: { 'X-Dump-Password': password },
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  return { success: true }
}

export async function getDatabaseHealth(repository: string, name: string): Promise<DatabaseHealthInfo | null> {
  const res = await fetchWithAuth(API + 'health/' + encodeURIComponent(repository) + '/' + encodeURIComponent(name))
  if (!res.ok) return null
  return res.json()
}

export async function getServerHealth(repository: string): Promise<ServerHealth | null> {
  const res = await fetchWithAuth(API + 'health/' + encodeURIComponent(repository))
  if (!res.ok) return null
  return res.json()
}

export async function listManagedDatabases(repository: string): Promise<ManagedDatabaseInfo[]> {
  const res = await fetchWithAuth(API + 'list/' + encodeURIComponent(repository))
  return handleJsonResponse(res)
}

export async function deleteManagedDatabase(
  repository: string,
  name: string,
  password: string,
  force?: boolean,
): Promise<{ success: boolean; error?: string; errorCode?: string; count?: number; requiresForce?: boolean; activeConnections?: number }> {
  let url = API + encodeURIComponent(repository) + '/' + encodeURIComponent(name)
  if (force) url += '?force=true'
  const res = await fetchWithAuth(url, {
    method: 'DELETE',
    headers: { 'X-Dump-Password': password },
  })
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText), errorCode: data.errorCode, count: data.count, requiresForce: data.requiresForce, activeConnections: data.activeConnections }
  }
  return { success: true }
}

export async function deleteManagedDatabasesBulk(
  repository: string,
  names: string[],
  password: string,
): Promise<{ success: boolean; deleted?: number; skipped?: number; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/bulk',
    {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'X-Dump-Password': password,
      },
      body: JSON.stringify(names),
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  const data = await res.json()
  return { success: true, deleted: data.deleted, skipped: data.skipped }
}

export async function updateDatabaseDescription(
  repository: string,
  name: string,
  description: string,
  password: string,
): Promise<{ success: boolean; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(name) + '/description',
    {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'X-Dump-Password': password,
      },
      body: JSON.stringify({ description }),
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  return { success: true }
}

export async function toggleDatabaseProtected(
  repository: string,
  name: string,
  password: string,
): Promise<{ success: boolean; protected?: boolean; disabledDeletionCount?: number; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(name) + '/protected',
    {
      method: 'PUT',
      headers: { 'X-Dump-Password': password },
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  const data = await res.json()
  return { success: true, protected: data.protected, disabledDeletionCount: data.disabledDeletionCount }
}

export function getDatabaseReportUrl(repository: string, databaseName: string): string {
  return API + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName) + '/report'
}

export async function cleanupIdleDatabases(
  repository: string,
  password: string,
  minDays: number,
): Promise<{ success: boolean; deleted?: number; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/cleanup-idle',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password, minDays }),
    },
  )
  const data = await res.json().catch(() => ({}))
  if (!res.ok) return { success: false, error: apiErrorMessage(data, res.statusText) }
  return { success: true, deleted: data.deleted }
}

export async function explainQuery(
  repository: string,
  databaseName: string,
  sql: string,
  analyze: boolean,
  signal?: AbortSignal,
): Promise<{ success: boolean; plan?: string; tableStats?: DatabaseTableStats; error?: string }> {
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName) + '/explain',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql, analyze }),
      signal,
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  const data = await res.json()
  return { success: true, plan: data.plan, tableStats: data.tableStats }
}

export async function isQueryEnabled(repository: string): Promise<{ enabled: boolean; writeEnabled: boolean; queryStatsResetEnabled: boolean }> {
  const res = await fetchWithAuth(API + `query-enabled?repository=${encodeURIComponent(repository)}`)
  if (!res.ok) return { enabled: false, writeEnabled: false, queryStatsResetEnabled: false }
  return res.json()
}

export async function executeQuery(
  repository: string,
  databaseName: string,
  sql: string,
  page: number,
  pageSize: number,
  password?: string,
  signal?: AbortSignal,
  totalRows?: number,
): Promise<{ success: boolean; result?: QueryResult; error?: string }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (password) headers['X-Dump-Password'] = password
  const payload: Record<string, unknown> = { sql, page, pageSize }
  if (totalRows != null && totalRows >= 0) payload.totalRows = totalRows
  const res = await fetchWithAuth(
    API + encodeURIComponent(repository) + '/' + encodeURIComponent(databaseName) + '/query',
    {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal,
    },
  )
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: apiErrorMessage(data, res.statusText) }
  }
  const data = await res.json()
  return { success: true, result: data }
}
