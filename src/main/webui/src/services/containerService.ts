import type { DockerContainer, ApiResponse } from '../types'

const API = '/containers/'

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
