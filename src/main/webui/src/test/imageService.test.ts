import { describe, it, expect, vi, beforeEach } from 'vitest'
import { getImages, removeImage } from '../services/imageService'

const mockFetch = vi.fn()
global.fetch = mockFetch

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    statusText: ok ? 'OK' : 'Internal Server Error',
    json: () => Promise.resolve(data),
  } as Response)
}

beforeEach(() => {
  mockFetch.mockReset()
})

describe('imageService', () => {
  describe('getImages', () => {
    it('fetches and returns image list', async () => {
      const images = [
        { repository: 'postgres', tag: '16', imageId: 'sha256:abc', size: '100.00 MB', inUse: true },
      ]
      mockFetch.mockReturnValue(jsonResponse(images))

      const result = await getImages()

      expect(mockFetch).toHaveBeenCalledWith('/api/images/list')
      expect(result).toEqual(images)
    })

    it('throws on non-ok response', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      await expect(getImages()).rejects.toThrow('Internal Server Error')
    })

    it('returns empty array when no images', async () => {
      mockFetch.mockReturnValue(jsonResponse([]))
      const result = await getImages()
      expect(result).toEqual([])
    })
  })

  describe('removeImage', () => {
    it('sends POST with imageId', async () => {
      mockFetch.mockReturnValue(jsonResponse({ state: 1 }))

      const result = await removeImage('sha256:abcdef1234')

      expect(mockFetch).toHaveBeenCalledWith('/api/images/remove', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ imageId: 'sha256:abcdef1234' }),
      })
      expect(result.state).toBe(1)
    })

    it('returns IMAGE_IN_USE state', async () => {
      mockFetch.mockReturnValue(
        jsonResponse({ state: 100, message: 'image is being used' }),
      )

      const result = await removeImage('abcdef1234')

      expect(result.state).toBe(100)
      expect(result.message).toContain('image is being used')
    })

    it('returns IMAGE_HAS_CHILDREN state', async () => {
      mockFetch.mockReturnValue(
        jsonResponse({ state: 101, message: 'image has dependent child images' }),
      )

      const result = await removeImage('abcdef1234')

      expect(result.state).toBe(101)
    })

    it('throws on non-ok response', async () => {
      mockFetch.mockReturnValue(jsonResponse(null, false))
      await expect(removeImage('abcdef1234')).rejects.toThrow()
    })
  })
})
