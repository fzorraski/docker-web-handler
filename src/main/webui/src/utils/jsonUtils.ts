const MAX_PARSE_SIZE = 200_000

export function maskSensitiveFields(json: string, fields: string[], enabled: boolean): string {
  if (!enabled || fields.length === 0) return json
  if (json.length > MAX_PARSE_SIZE) return json
  try {
    const obj = JSON.parse(json)
    const lowerFields = new Set(fields.map(f => f.toLowerCase()))
    const mask = (o: unknown): unknown => {
      if (typeof o !== 'object' || o === null) return o
      if (Array.isArray(o)) return o.map(mask)
      const result: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
        result[k] = lowerFields.has(k.toLowerCase())
          ? '***'
          : typeof v === 'object' ? mask(v) : v
      }
      return result
    }
    return JSON.stringify(mask(obj), null, 2)
  } catch {
    return json
  }
}

export function tryFormatJson(text: string): { formatted: string; isJson: boolean } {
  if (text.length > MAX_PARSE_SIZE) return { formatted: text, isJson: false }
  try {
    const obj = JSON.parse(text)
    return { formatted: JSON.stringify(obj, null, 2), isJson: true }
  } catch {
    return { formatted: text, isJson: false }
  }
}
