import { describe, it, expect } from 'vitest'
import { expirySeverity, formatRemaining, URGENT_MS, SOON_MS } from '../utils/expiry'

describe('expirySeverity', () => {
  it('is quiet for anything beyond six hours', () => {
    // info, not default: MUI's default paints near-white on the dark theme,
    // which would make the calm rows the loudest ink in the column
    expect(expirySeverity(SOON_MS)).toBe('info')
    expect(expirySeverity(113 * 60 * 60 * 1000)).toBe('info')
  })

  it('warns inside six hours', () => {
    expect(expirySeverity(SOON_MS - 1)).toBe('warning')
    expect(expirySeverity(URGENT_MS)).toBe('warning')
  })

  it('is urgent inside the last hour', () => {
    expect(expirySeverity(URGENT_MS - 1)).toBe('error')
    expect(expirySeverity(8 * 60 * 1000)).toBe('error')
  })

  it('stays urgent once the deadline has passed', () => {
    // the chip lingers on "Expiring..." until the refresh lands
    expect(expirySeverity(0)).toBe('error')
    expect(expirySeverity(-5000)).toBe('error')
  })
})

describe('formatRemaining', () => {
  const seconds = (n: number) => n * 1000
  const minutes = (n: number) => n * 60_000
  const hours = (n: number) => n * 3_600_000
  const days = (n: number) => n * 86_400_000

  it('counts days once past 24 hours', () => {
    expect(formatRemaining(days(4) + hours(17))).toBe('4d 17h')
    // the 113h case from the containers table
    expect(formatRemaining(hours(113) + minutes(30))).toBe('4d 17h')
  })

  it('switches to days exactly at 24 hours', () => {
    expect(formatRemaining(hours(24))).toBe('1d 0h')
    expect(formatRemaining(hours(24) - 1000)).toBe('23h 59m')
  })

  it('drops seconds above an hour, where they are noise', () => {
    expect(formatRemaining(hours(7) + minutes(48) + seconds(31))).toBe('7h 48m')
  })

  it('keeps seconds under an hour, where they matter', () => {
    expect(formatRemaining(minutes(19) + seconds(55))).toBe('19m 55s')
    expect(formatRemaining(seconds(40))).toBe('40s')
  })

  it('never renders a negative countdown', () => {
    expect(formatRemaining(-5000)).toBe('0s')
  })
})
