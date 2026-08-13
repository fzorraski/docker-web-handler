import { describe, it, expect } from 'vitest'
import { toCsv } from '../utils/csv'

describe('toCsv', () => {
  it('writes a header row followed by the data', () => {
    expect(toCsv(['User', 'Count'], [['alice', 12]])).toBe('User,Count\r\nalice,12')
  })

  it('quotes cells holding a separator, a quote or a line break', () => {
    expect(toCsv(['a'], [['x,y']])).toBe('a\r\n"x,y"')
    expect(toCsv(['a'], [['say "hi"']])).toBe('a\r\n"say ""hi"""')
    expect(toCsv(['a'], [['one\ntwo']])).toBe('a\r\n"one\ntwo"')
  })

  it('renders empty cells for null and undefined', () => {
    expect(toCsv(['a', 'b'], [[null, undefined]])).toBe('a,b\r\n,')
  })

  it('defuses cells a spreadsheet would execute', () => {
    // actor names come from whoever typed them at the login form, and the
    // failed-sign-in export is exactly where a hostile one would land
    expect(toCsv(['a'], [['=1+1']])).toBe("a\r\n'=1+1")
    expect(toCsv(['a'], [['+cmd']])).toBe("a\r\n'+cmd")
    expect(toCsv(['a'], [['@SUM(A1)']])).toBe("a\r\n'@SUM(A1)")
    expect(toCsv(['a'], [['-2+3']])).toBe("a\r\n'-2+3")
  })

  it('leaves an ordinary negative number alone once it is a number', () => {
    // only strings are guarded - our own numeric counts must stay summable
    expect(toCsv(['a'], [[-5]])).toBe('a\r\n-5')
  })
})
