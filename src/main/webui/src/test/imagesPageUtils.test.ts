import { describe, it, expect } from 'vitest'
import type { DockerImage } from '../types'

// These functions are defined inline in ImagesPage.tsx but are pure and testable.
// We replicate them here to validate the filtering and sorting logic.

const filterImage = (img: DockerImage, query: string) => {
  const q = query.toLowerCase()
  return img.repository.toLowerCase().includes(q)
    || img.tag.toLowerCase().includes(q)
    || img.imageId.toLowerCase().includes(q)
    || img.size.toLowerCase().includes(q)
}

const sortImageValue = (img: DockerImage, key: string) => {
  if (key === 'inUse') return img.inUse ? '0' : '1'
  if (key === 'lastUsedAt') return img.lastUsedAt ?? ''
  return (img[key as keyof DockerImage] ?? '').toString().toLowerCase()
}

function makeImage(overrides: Partial<DockerImage> = {}): DockerImage {
  return {
    repository: 'postgres',
    tag: '16',
    imageId: 'sha256:abc123',
    created: '01/01/2025 00:00:00',
    size: '100.00 MB',
    inUse: false,
    containerCount: 0,
    parentId: '',
    childIds: [],
    lastUsedAt: null,
    ...overrides,
  }
}

describe('filterImage', () => {
  it('matches by repository', () => {
    const img = makeImage({ repository: 'postgres' })
    expect(filterImage(img, 'post')).toBe(true)
  })

  it('matches by tag', () => {
    const img = makeImage({ tag: '16.4' })
    expect(filterImage(img, '16.4')).toBe(true)
  })

  it('matches by imageId', () => {
    const img = makeImage({ imageId: 'sha256:deadbeef' })
    expect(filterImage(img, 'deadbeef')).toBe(true)
  })

  it('matches by size', () => {
    const img = makeImage({ size: '250.00 MB' })
    expect(filterImage(img, '250')).toBe(true)
  })

  it('is case insensitive', () => {
    const img = makeImage({ repository: 'PostgreSQL' })
    expect(filterImage(img, 'postgresql')).toBe(true)
  })

  it('returns false when no match', () => {
    const img = makeImage()
    expect(filterImage(img, 'zzzzz')).toBe(false)
  })

  it('matches empty query', () => {
    const img = makeImage()
    expect(filterImage(img, '')).toBe(true)
  })
})

describe('sortImageValue', () => {
  it('returns "0" for inUse=true', () => {
    const img = makeImage({ inUse: true })
    expect(sortImageValue(img, 'inUse')).toBe('0')
  })

  it('returns "1" for inUse=false', () => {
    const img = makeImage({ inUse: false })
    expect(sortImageValue(img, 'inUse')).toBe('1')
  })

  it('returns lastUsedAt value', () => {
    const img = makeImage({ lastUsedAt: '2025-06-15T10:30:00Z' })
    expect(sortImageValue(img, 'lastUsedAt')).toBe('2025-06-15T10:30:00Z')
  })

  it('returns empty string for null lastUsedAt', () => {
    const img = makeImage({ lastUsedAt: null })
    expect(sortImageValue(img, 'lastUsedAt')).toBe('')
  })

  it('returns repository lowercase', () => {
    const img = makeImage({ repository: 'PostgreSQL' })
    expect(sortImageValue(img, 'repository')).toBe('postgresql')
  })

  it('returns tag', () => {
    const img = makeImage({ tag: '16.4' })
    expect(sortImageValue(img, 'tag')).toBe('16.4')
  })

  it('returns empty string for unknown key', () => {
    const img = makeImage()
    expect(sortImageValue(img, 'nonexistent')).toBe('')
  })
})

describe('totalSize calculation', () => {
  // Replicating the inline totalSize memo logic from ImagesPage
  function calculateTotalSize(images: DockerImage[]): string {
    let bytes = 0
    for (const img of images) {
      const match = img.size.match(/([\d.]+)\s*MB/)
      if (match) bytes += parseFloat(match[1])
    }
    if (bytes >= 1024) return (bytes / 1024).toFixed(2) + ' GB'
    return bytes.toFixed(2) + ' MB'
  }

  it('sums MB sizes', () => {
    const images = [makeImage({ size: '100.00 MB' }), makeImage({ size: '200.50 MB' })]
    expect(calculateTotalSize(images)).toBe('300.50 MB')
  })

  it('converts to GB when >= 1024 MB', () => {
    const images = [makeImage({ size: '600.00 MB' }), makeImage({ size: '600.00 MB' })]
    expect(calculateTotalSize(images)).toBe('1.17 GB')
  })

  it('handles empty list', () => {
    expect(calculateTotalSize([])).toBe('0.00 MB')
  })

  it('ignores non-MB sizes', () => {
    const images = [makeImage({ size: '100.00 MB' }), makeImage({ size: '5.00 KB' })]
    expect(calculateTotalSize(images)).toBe('100.00 MB')
  })
})

describe('unused/inUse counting', () => {
  it('counts unused images', () => {
    const images = [
      makeImage({ inUse: true }),
      makeImage({ inUse: false }),
      makeImage({ inUse: false }),
    ]
    const unusedCount = images.filter(img => !img.inUse).length
    expect(unusedCount).toBe(2)
  })

  it('counts in-use images', () => {
    const images = [
      makeImage({ inUse: true }),
      makeImage({ inUse: true }),
      makeImage({ inUse: false }),
    ]
    const inUseCount = images.filter(img => img.inUse).length
    expect(inUseCount).toBe(2)
  })

  it('filters unused only', () => {
    const images = [
      makeImage({ inUse: true, repository: 'pg' }),
      makeImage({ inUse: false, repository: 'redis' }),
    ]
    const filtered = images.filter(img => !img.inUse)
    expect(filtered).toHaveLength(1)
    expect(filtered[0].repository).toBe('redis')
  })
})
