/**
 * Colours and helpers for the activity dashboard.
 *
 * The category order is fixed and hues are assigned by slot, never cycled: a
 * filter that removes a category must not repaint the ones that remain, or the
 * same colour would mean two different things between two screenshots.
 *
 * Light and dark are separate sets stepped for their own surface rather than
 * one set reused - a hue that clears contrast on white washes out on the dark
 * background. Both sets were checked for lightness band, chroma, colour-vision
 * separation and contrast; three light steps sit below 3:1, which is why every
 * chart here also carries a legend and a table.
 */
export const ACTIVITY_CATEGORIES = ['CONTAINER', 'DATABASE', 'TERMINAL', 'ADMIN', 'AUTH', 'OTHER'] as const

export type ActivityCategoryName = (typeof ACTIVITY_CATEGORIES)[number]

const LIGHT: Record<ActivityCategoryName, string> = {
  CONTAINER: '#eb6834',
  DATABASE: '#2a78d6',
  TERMINAL: '#1baf7a',
  ADMIN: '#4a3aa7',
  AUTH: '#e87ba4',
  OTHER: '#eda100',
}

const DARK: Record<ActivityCategoryName, string> = {
  CONTAINER: '#d95926',
  DATABASE: '#3987e5',
  TERMINAL: '#199e70',
  ADMIN: '#9085e9',
  AUTH: '#d55181',
  OTHER: '#c98500',
}

export type ThemeMode = 'light' | 'dark'

/** Slot colour for a category; an unknown name falls back to OTHER. */
export function categoryColor(category: string, mode: ThemeMode): string {
  const set = mode === 'dark' ? DARK : LIGHT
  return set[category as ActivityCategoryName] ?? set.OTHER
}

/**
 * Single-hue ramp for the heatmap, light to dark on a light surface and dark to
 * light on a dark one, so "near zero" always recedes towards the background
 * instead of glowing against it.
 */
const HEAT_LIGHT = ['#e8f1fd', '#cde2fb', '#9ec5f4', '#6da7ec', '#3987e5', '#256abf', '#184f95']
const HEAT_DARK = ['#12263f', '#173a63', '#1c5cab', '#2a78d6', '#5598e7', '#86b6ef', '#b7d3f6']

/**
 * Ramp step for a value, given the busiest cell in the grid. Ranks by relative
 * intensity rather than absolute count: a quiet week and a busy one both use
 * the whole ramp, which is what makes the pattern visible in either.
 */
export function heatColor(count: number, max: number, mode: ThemeMode): string {
  const ramp = mode === 'dark' ? HEAT_DARK : HEAT_LIGHT
  if (count <= 0 || max <= 0) return 'transparent'
  // sqrt, so a single outlier does not flatten every other cell to the palest step
  const intensity = Math.sqrt(count / max)
  const step = Math.min(ramp.length - 1, Math.max(0, Math.ceil(intensity * ramp.length) - 1))
  return ramp[step]
}

/**
 * Change against the previous period as a percentage, or null when there is no
 * baseline to compare against - growth from zero is not "infinite", it is a
 * first appearance, and the UI says so in words.
 */
export function percentDelta(current: number, previous: number): number | null {
  if (previous === 0) return null
  return ((current - previous) / previous) * 100
}
