const DOW_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

export function cronToHuman(expr: string): string {
  if (!expr) return ''
  const parts = expr.trim().split(/\s+/)
  if (parts.length !== 5) return expr

  const [minute, hour, dom, month, dow] = parts

  // Every minute
  if (minute === '*' && hour === '*' && dom === '*' && month === '*' && dow === '*') {
    return 'Every minute'
  }

  // Every N minutes
  if (minute.startsWith('*/') && hour === '*' && dom === '*' && month === '*' && dow === '*') {
    const n = minute.slice(2)
    return `Every ${n} minutes`
  }

  // Every hour at :MM
  if (!minute.includes('*') && hour === '*' && dom === '*' && month === '*' && dow === '*') {
    return `Every hour at :${minute.padStart(2, '0')}`
  }

  // Daily at HH:MM
  if (!minute.includes('*') && !hour.includes('*') && dom === '*' && month === '*' && dow === '*') {
    return `Daily at ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
  }

  // Weekdays at HH:MM
  if (!minute.includes('*') && !hour.includes('*') && dom === '*' && month === '*' && dow === '1-5') {
    return `Weekdays at ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
  }

  // Specific day of week
  if (!minute.includes('*') && !hour.includes('*') && dom === '*' && month === '*' && !dow.includes('*')) {
    const days = dow.split(',').map(d => DOW_NAMES[parseInt(d)] || d).join(', ')
    return `${days} at ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
  }

  return expr
}

export function getNextOccurrences(expr: string, count: number = 5): Date[] {
  if (!expr) return []
  const parts = expr.trim().split(/\s+/)
  if (parts.length !== 5) return []

  try {
    const minutes = parseField(parts[0], 0, 59)
    const hours = parseField(parts[1], 0, 23)
    const doms = parseField(parts[2], 1, 31)
    const months = parseField(parts[3], 1, 12)
    const dows = parseField(parts[4], 0, 6)

    const results: Date[] = []
    const now = new Date()
    const candidate = new Date(now)
    candidate.setSeconds(0, 0)
    candidate.setMinutes(candidate.getMinutes() + 1)

    const maxIter = 366 * 24 * 60
    for (let i = 0; i < maxIter && results.length < count; i++) {
      const cronDow = candidate.getDay() // 0=Sunday
      if (
        months.has(candidate.getMonth() + 1) &&
        doms.has(candidate.getDate()) &&
        dows.has(cronDow) &&
        hours.has(candidate.getHours()) &&
        minutes.has(candidate.getMinutes())
      ) {
        results.push(new Date(candidate))
      }
      candidate.setMinutes(candidate.getMinutes() + 1)
    }
    return results
  } catch {
    return []
  }
}

function parseField(field: string, min: number, max: number): Set<number> {
  const result = new Set<number>()

  for (const part of field.split(',')) {
    let step = 1
    let rangePart = part

    const slashIdx = part.indexOf('/')
    if (slashIdx >= 0) {
      step = parseInt(part.slice(slashIdx + 1))
      rangePart = part.slice(0, slashIdx)
    }

    let start: number, end: number

    if (rangePart === '*') {
      start = min
      end = max
    } else if (rangePart.includes('-')) {
      const [a, b] = rangePart.split('-')
      start = parseInt(a)
      end = parseInt(b)
    } else {
      start = parseInt(rangePart)
      end = start
    }

    for (let v = start; v <= end; v += step) {
      result.add(v)
    }
  }
  return result
}
