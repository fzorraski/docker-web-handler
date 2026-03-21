import type { DockerContainer, ApiResponse, DatabaseConflict } from '../types'

const API = '/api/containers/'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(res.statusText)
  return res.json()
}

function postJson(url: string, body: object) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function getContainers(): Promise<DockerContainer[]> {
  const res = await fetch(API + 'list')
  return handleResponse(res)
}

export async function stopContainer(id: string): Promise<boolean> {
  const res = await postJson(API + 'stop', { containerId: id })
  return handleResponse(res)
}

export async function startContainer(id: string): Promise<boolean> {
  const res = await postJson(API + 'start', { containerId: id })
  return handleResponse(res)
}

export async function removeContainer(id: string): Promise<boolean> {
  const res = await postJson(API + 'remove', { containerId: id })
  return handleResponse(res)
}

export async function getAllowedRepositories(): Promise<string[]> {
  const res = await fetch(API + 'allowed-repositories')
  return handleResponse(res)
}

export async function getRepositoryTags(repository: string): Promise<ApiResponse> {
  const res = await fetch(API + 'repository-tags?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getRepositoryEnvKeys(repository: string): Promise<{ key: string; value: string }[]> {
  const res = await fetch(API + 'repository-env-keys?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getDefaultExpirationMinutes(): Promise<number> {
  const res = await fetch(API + 'default-expiration-minutes')
  return handleResponse(res)
}

export async function getLocale(): Promise<string> {
  const res = await fetch(API + 'locale')
  return res.text()
}

export async function getDatabaseConflicts(databaseName: string): Promise<DatabaseConflict> {
  const res = await fetch(API + 'database-conflicts?databaseName=' + encodeURIComponent(databaseName))
  return handleResponse(res)
}

export async function isDatabaseListingEnabled(): Promise<boolean> {
  const res = await fetch(API + 'database-listing-enabled')
  return handleResponse(res)
}

export async function repositoryHasDatabases(repository: string): Promise<boolean> {
  const res = await fetch(API + 'repository-has-databases?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function getRepositoryDatabases(repository: string): Promise<ApiResponse> {
  const res = await fetch(API + 'repository-databases?repository=' + encodeURIComponent(repository))
  return handleResponse(res)
}

export async function isMigrationEnabled(): Promise<boolean> {
  const res = await fetch(API + 'migration-enabled')
  return handleResponse(res)
}

export async function isWebhookEnabled(): Promise<boolean> {
  const res = await fetch(API + 'webhook-enabled')
  return handleResponse(res)
}

export interface FeatureFlags {
  memoryLimit: boolean
  deletionOnExpiration: boolean
  databaseListing: boolean
  dump: boolean
  migration: boolean
  webhook: boolean
  terminal: boolean
  defaultExpirationMinutes: number
  uploadPasswordRequired: boolean
  operationsPasswordRequired: boolean
  terminalPasswordRequired: boolean
}

export async function getFeatures(): Promise<FeatureFlags> {
  const res = await fetch(API + 'features')
  return handleResponse(res)
}

export async function isMigrationApiAvailable(repository: string): Promise<boolean> {
  const res = await fetch(API + 'migration-api-available?repository=' + encodeURIComponent(repository))
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
  const res = await fetch(API + 'migrated-databases')
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
  const res = await fetch(API + 'migration-preview?' + params)
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
