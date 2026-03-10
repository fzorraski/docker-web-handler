import type { DockerImage, ApiResponse } from '../types'

const API = '/images/'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(res.statusText)
  return res.json()
}

export async function getImages(): Promise<DockerImage[]> {
  const res = await fetch(API + 'list')
  return handleResponse(res)
}

export async function removeImage(imageId: string): Promise<ApiResponse> {
  const res = await fetch(API + 'remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageId }),
  })
  return handleResponse(res)
}
