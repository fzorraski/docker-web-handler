import { describe, it, expect } from 'vitest'
import {
  ACTIVITY_CATEGORIES, categoryColor, heatColor, percentDelta,
} from '../utils/activityCategory'

describe('categoryColor', () => {
  it('gives every category its own colour in both modes', () => {
    for (const mode of ['light', 'dark'] as const) {
      const colors = ACTIVITY_CATEGORIES.map(category => categoryColor(category, mode))
      expect(new Set(colors).size).toBe(ACTIVITY_CATEGORIES.length)
    }
  })

  it('steps the dark set separately instead of reusing the light one', () => {
    // a hue picked for a white background washes out on a dark surface
    for (const category of ACTIVITY_CATEGORIES) {
      expect(categoryColor(category, 'dark')).not.toBe(categoryColor(category, 'light'))
    }
  })

  it('falls back to OTHER for a category the frontend does not know yet', () => {
    expect(categoryColor('SOMETHING_NEW', 'light')).toBe(categoryColor('OTHER', 'light'))
  })
})

describe('heatColor', () => {
  it('is transparent for a day with nothing on it', () => {
    expect(heatColor(0, 10, 'light')).toBe('transparent')
  })

  it('darkens as the count approaches the busiest cell', () => {
    const low = heatColor(1, 100, 'light')
    const high = heatColor(100, 100, 'light')
    expect(low).not.toBe(high)
  })

  it('scales against the busiest cell, not an absolute count', () => {
    // the same count reads differently depending on the week around it
    expect(heatColor(5, 5, 'light')).toBe(heatColor(50, 50, 'light'))
    expect(heatColor(5, 5, 'light')).not.toBe(heatColor(5, 50, 'light'))
  })

  it('never returns an empty value for a positive count', () => {
    for (const count of [1, 2, 7, 99, 1000]) {
      expect(heatColor(count, 1000, 'dark')).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('treats a max of zero as nothing to plot', () => {
    expect(heatColor(3, 0, 'light')).toBe('transparent')
  })
})

describe('percentDelta', () => {
  it('reports growth and decline against the previous period', () => {
    expect(percentDelta(150, 100)).toBe(50)
    expect(percentDelta(50, 100)).toBe(-50)
    expect(percentDelta(100, 100)).toBe(0)
  })

  it('has no answer when there is no baseline', () => {
    // growth from zero is a first appearance, not an infinite increase
    expect(percentDelta(10, 0)).toBeNull()
    expect(percentDelta(0, 0)).toBeNull()
  })
})
