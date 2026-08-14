/**
 * How loud an expiry countdown should be.
 *
 * A column where every countdown is the same amber says only "these all
 * expire", which the header already said. What the reader actually needs is
 * *which one dies next*, so the colour tracks the time left: red under an hour,
 * amber under six, and a cool blue beyond that. Blue rather than the theme's
 * default: on the dark theme "default" paints the label in near-white, the
 * highest-contrast ink available, so the calm rows were the loudest thing in
 * the column. A muted hue is both prettier and quieter than no hue at all.
 */
export type ExpirySeverity = 'error' | 'warning' | 'info'

/** Under an hour: nothing can be scheduled around it any more. */
export const URGENT_MS = 60 * 60 * 1000
/** Under six hours: still today's problem. */
export const SOON_MS = 6 * 60 * 60 * 1000

export function expirySeverity(remainingMs: number): ExpirySeverity {
  if (remainingMs < URGENT_MS) {
    return 'error'
  }
  if (remainingMs < SOON_MS) {
    return 'warning'
  }
  return 'info'
}

const MINUTE_MS = 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * The time left, at the precision that range deserves.
 *
 * <p>Precision follows urgency, like the colour does: seconds matter when the
 * container has minutes to live and are pure noise four days out, where "113h
 * 30m 1s" also forces the reader to divide by 24 to learn anything. Two units
 * at most, so the chip stays a chip.</p>
 */
export function formatRemaining(remainingMs: number): string {
  const total = Math.max(0, remainingMs)
  const days = Math.floor(total / DAY_MS)
  const hours = Math.floor((total % DAY_MS) / (60 * MINUTE_MS))
  const minutes = Math.floor((total % (60 * MINUTE_MS)) / MINUTE_MS)
  const seconds = Math.floor((total % MINUTE_MS) / 1000)

  if (days > 0) {
    return `${days}d ${hours}h`
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`
  }
  return `${seconds}s`
}
