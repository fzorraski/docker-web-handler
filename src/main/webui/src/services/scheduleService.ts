import type { ContainerSchedule } from '../types'
import fetchWithAuth from './fetchWithAuth'

const API = '/api/schedules/'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}

function postJson(url: string, body: object) {
  return fetchWithAuth(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function putJson(url: string, body: object) {
  return fetchWithAuth(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function isSchedulingEnabled(): Promise<boolean> {
  const res = await fetchWithAuth(API + 'enabled')
  return handleResponse(res)
}

export async function listSchedules(): Promise<ContainerSchedule[]> {
  const res = await fetchWithAuth(API + 'list')
  return handleResponse(res)
}

export async function getSchedule(id: string): Promise<ContainerSchedule> {
  const res = await fetchWithAuth(API + encodeURIComponent(id))
  return handleResponse(res)
}

export async function getSchedulesByContainer(containerId: string): Promise<ContainerSchedule[]> {
  const res = await fetchWithAuth(API + 'container/' + encodeURIComponent(containerId))
  return handleResponse(res)
}

export async function createSchedule(request: {
  name: string
  action: string
  scheduleType: string
  cronExpression?: string
  scheduledAt?: string
  containerId?: string
  containerName?: string
  createConfig?: object
  operationsPassword?: string
  tenantId?: string
  noTenant?: boolean
  sharedWithTenants?: string[]
}): Promise<ContainerSchedule> {
  const res = await postJson(API + 'create', request)
  return handleResponse(res)
}

export async function updateSchedule(id: string, request: {
  name?: string
  cronExpression?: string
  scheduledAt?: string
  containerId?: string
  containerName?: string
  createConfig?: object
}): Promise<ContainerSchedule> {
  const res = await putJson(API + encodeURIComponent(id), request)
  return handleResponse(res)
}

export async function toggleSchedule(id: string, password: string): Promise<ContainerSchedule> {
  const res = await fetchWithAuth(API + encodeURIComponent(id) + '/toggle', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Schedule-Password': password },
    body: '{}',
  })
  return handleResponse(res)
}

/** RBAC-only feature: the scheduling password is bypassed under RBAC, so none is collected. */
export async function updateScheduleSharing(
  id: string,
  sharedWithTenants: string[],
  tenantId: string | null | undefined,
): Promise<ContainerSchedule> {
  const body: Record<string, unknown> = { sharedWithTenants }
  if (tenantId !== undefined) body.tenantId = tenantId
  const res = await fetchWithAuth(API + 'sharing/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-Schedule-Password': '' },
    body: JSON.stringify(body),
  })
  return handleResponse(res)
}

export async function deleteSchedule(id: string, password: string): Promise<{ success: boolean }> {
  const res = await fetchWithAuth(API + encodeURIComponent(id), {
    method: 'DELETE',
    headers: { 'X-Schedule-Password': password },
  })
  return handleResponse(res)
}

export async function executeScheduleNow(id: string, password: string): Promise<{ message: string }> {
  const res = await fetchWithAuth(API + encodeURIComponent(id) + '/execute-now', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Schedule-Password': password },
    body: '{}',
  })
  return handleResponse(res)
}
