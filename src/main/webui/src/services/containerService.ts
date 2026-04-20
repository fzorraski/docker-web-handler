import type { DockerContainer, ApiResponse, DatabaseConflict } from '../types'
import fetchWithAuth from './fetchWithAuth'

const API = '/api/containers/'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(res.statusText)
  return res.json()
}

function postJson(url: string, body: object) {
  return fetchWithAuth(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function getContainers(): Promise<DockerContainer[]> {
  const res = await fetchWithAuth(API + 'list')
  return handleResponse(res)
}

export async function stopContainer(id: string): Promise<boolean> {
  const res = await postJson(API + 'stop', { containerId: id })
  return handleResponse(res)
}

export async function lockContainers(ids: string[]): Promise<void> {
  await postJson('/api/containers/sse/lock', ids)
}

export async function unlockContainers(ids: string[]): Promise<void> {
  await postJson('/api/containers/sse/unlock', ids)
}

export class MemoryGuardError extends Error {
  constructor(public readonly availableMb: number, public readonly thresholdMb: number) {
    super('MEMORY_GUARD')
  }
}

export interface StartResult {
  success: boolean
  error?: string
  detail?: string
}

export async function startContainer(id: string): Promise<StartResult> {
  const res = await postJson(API + 'start', { containerId: id })
  if (res.status === 503) {
    const data = await res.json().catch(() => ({}))
    if (data.code === 'MEMORY_GUARD') {
      throw new MemoryGuardError(data.availableMb, data.thresholdMb)
    }
    throw new Error(res.statusText)
  }
  if (!res.ok) throw new Error(res.statusText)
  return res.json()
}

export async function removeContainer(id: string): Promise<boolean> {
  const res = await postJson(API + 'remove', { containerId: id })
  return handleResponse(res)
}

export async function getAllowedRepositories(): Promise<string[]> {
  const res = await fetchWithAuth(API + 'allowed-repositories')
  return handleResponse(res)
}

export async function getRepositoryTags(repository: string): Promise<ApiResponse> {
  const res = await fetchWithAuth(API + 'repository-tags?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getRepositoryEnvKeys(repository: string): Promise<{ key: string; value: string }[]> {
  const res = await fetchWithAuth(API + 'repository-env-keys?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getDefaultExpirationMinutes(): Promise<number> {
  const res = await fetchWithAuth(API + 'default-expiration-minutes')
  return handleResponse(res)
}

export async function getLocale(): Promise<string> {
  const res = await fetchWithAuth(API + 'locale')
  return res.text()
}

export async function getDatabaseConflicts(databaseName: string): Promise<DatabaseConflict> {
  const res = await fetchWithAuth(API + 'database-conflicts?databaseName=' + encodeURIComponent(databaseName))
  return handleResponse(res)
}

export async function isDatabaseListingEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'database-listing-enabled')
  return handleResponse(res)
}

export async function repositoryHasDatabases(repository: string): Promise<boolean> {
  const res = await fetchWithAuth(API + 'repository-has-databases?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getRepositoryDatabases(repository: string): Promise<ApiResponse> {
  const res = await fetchWithAuth(API + 'repository-databases?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function isMigrationEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'migration-enabled')
  return handleResponse(res)
}

export async function isWebhookEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'webhook-enabled')
  return handleResponse(res)
}

export interface FeatureFlags {
  memoryLimit: boolean
  memoryGuard: boolean
  deletionOnExpiration: boolean
  databaseListing: boolean
  dump: boolean
  migration: boolean
  webhook: boolean
  terminal: boolean
  defaultExpirationMinutes: number
  uploadPasswordRequired: boolean
  operationsPasswordRequired: boolean
  schedulingPasswordRequired: boolean
  terminalPasswordRequired: boolean
  terminalUpload: boolean
  terminalUploadMaxSizeMb: number
  terminalUploadDefaultPath: string
}

export async function getFeatures(): Promise<FeatureFlags> {
  const res = await fetchWithAuth(API + 'features')
  return handleResponse(res)
}

export interface HostMemoryStatus {
  supported: boolean
  enabled: boolean
  available: boolean
  totalMb: number
  usedMb: number
  availableMb: number
  thresholdMb: number
}

export async function getMemoryStatus(): Promise<HostMemoryStatus> {
  const res = await fetchWithAuth(API + 'memory-status')
  return handleResponse(res)
}

export async function isMigrationApiAvailable(repository: string): Promise<boolean> {
  const res = await fetchWithAuth(API + 'migration-api-available?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export interface MigratedDatabase {
  databaseName: string
  repository: string
  sourceVersion?: string
  targetVersion?: string
  versionsIncluded?: string[]
  totalStatements?: number
  mode: string
  migratedAt: string
}

export async function getMigratedDatabases(): Promise<MigratedDatabase[]> {
  const res = await fetchWithAuth(API + 'migrated-databases')
  return handleResponse(res)
}

export interface MigrationPreview {
  sql: string
  sourceVersion?: string
  targetVersion?: string
  totalStatements?: number
  versionsIncluded?: string[]
}

export async function previewMigration(repository: string, sourceVersion: string, targetVersion: string): Promise<MigrationPreview> {
  const params = new URLSearchParams({ repository, sourceVersion, targetVersion })
  const res = await fetchWithAuth(API + 'migration-preview?' + params)
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || res.statusText)
  }
  return res.json()
}

export async function extendExpiration(id: string, minutes: number = 10): Promise<boolean> {
  const res = await postJson(API + 'extend-expiration?minutes=' + minutes, { containerId: id })
  return handleResponse(res)
}

export async function cancelDatabaseDeletion(id: string): Promise<boolean> {
  const res = await postJson(API + 'cancel-db-deletion', { containerId: id })
  return handleResponse(res)
}

export async function cancelExpiration(id: string): Promise<boolean> {
  const res = await postJson(API + 'cancel-expiration', { containerId: id })
  return handleResponse(res)
}

export interface UpdateExpirationRequest {
  containerId: string
  expiresAt: string | null
  deleteDatabaseOnExpiration: boolean
  operationsPassword?: string
}

export async function updateContainerExpiration(request: UpdateExpirationRequest): Promise<{ success: boolean; error?: string }> {
  const res = await postJson(API + 'update-expiration', request)
  if (res.status === 403 || res.status === 400 || res.status === 404) {
    const data = await res.json().catch(() => ({}))
    return { success: false, error: data.error || res.statusText }
  }
  if (!res.ok) throw new Error(res.statusText)
  return res.json()
}

export async function validateOperationsPassword(password: string): Promise<boolean> {
  const res = await fetchWithAuth(API + 'validate-operations-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (res.status === 403) return false
  if (!res.ok) throw new Error(res.statusText)
  return true
}

export async function runContainer(
  repository: string,
  tag: string,
  containerName: string,
  envVars: string[],
  expiresAt?: string,
): Promise<ApiResponse> {
  const res = await postJson(API + 'run', {
    repository,
    tag,
    containerName,
    envVars,
    expiresAt: expiresAt || null,
  })
  return handleResponse(res)
}
