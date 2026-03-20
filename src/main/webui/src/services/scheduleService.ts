import type { ContainerSchedule } from '../types'

const API = '/api/schedules/'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const data = await res.json().catch(() => ({}))
    throw new Error(data.error || data.message || res.statusText)
  }
  return res.json()
}

function postJson(url: string, body: object) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function putJson(url: string, body: object) {
  return fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function isSchedulingEnabled(): Promise<boolean> {
  const res = await fetch(API + 'enabled')
  return handleResponse(res)
}

export async function listSchedules(): Promise<ContainerSchedule[]> {
  const res = await fetch(API + 'list')
  return handleResponse(res)
}

export async function getSchedule(id: string): Promise<ContainerSchedule> {
  const res = await fetch(API + encodeURIComponent(id))
  return handleResponse(res)
}

export async function getSchedulesByContainer(containerId: string): Promise<ContainerSchedule[]> {
  const res = await fetch(API + 'container/' + encodeURIComponent(containerId))
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

export async function toggleSchedule(id: string): Promise<ContainerSchedule> {
  const res = await postJson(API + encodeURIComponent(id) + '/toggle', {})
  return handleResponse(res)
}

export async function deleteSchedule(id: string, password: string): Promise<{ success: boolean }> {
  const res = await fetch(API + encodeURIComponent(id), {
    method: 'DELETE',
    headers: { 'X-Schedule-Password': password },
  })
  return handleResponse(res)
}

export async function executeScheduleNow(id: string): Promise<{ message: string }> {
  const res = await postJson(API + encodeURIComponent(id) + '/execute-now', {})
  return handleResponse(res)
}
