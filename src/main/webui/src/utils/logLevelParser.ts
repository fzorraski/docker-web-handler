export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE' | 'UNKNOWN'

export interface ParseResult {
  level: LogLevel
  isStackTrace: boolean
  isExceptionStart: boolean
}

// Stack trace continuation lines: "  at com.example.Foo.bar(Foo.java:42)", "Caused by: ...", "  ... 15 more"
// Matches ANSI SGR escape sequences: ESC[ ... m (colors, bold, reset, etc.)
// Also handles OSC sequences: ESC] ... BEL/ST
// eslint-disable-next-line no-control-regex
const ANSI_RE = /\x1b(?:\[[0-9;]*[A-Za-z]|\].*?(?:\x07|\x1b\\))/g

export function stripAnsi(text: string): string {
  return ANSI_RE.test(text) ? text.replace(ANSI_RE, '') : text
}

const STACK_TRACE_RE = /^\s+(at\s|\.{3}\s*\d+\s+more)|^Caused by:/

// Exception header lines:
//   Java:   "java.lang.NullPointerException: msg" or "NullPointerException: msg"
//   JS/TS:  "TypeError: cannot read property"
//   Python: "Traceback (most recent call last):", "ValueError: invalid literal"
//   Go:     "panic: runtime error"
const EXCEPTION_START_RE = /(?:\b[a-z]+(?:\.[a-z]+)*\.)?[A-Z]\w*(?:Exception|Error|Throwable|Fault)\s*(?::|$)|Traceback \(most recent call last\):|^panic:/

const LEVEL_RE = /\b(FATAL|SEVERE|ERROR|WARN(?:ING)?|INFO|DEBUG|FINE[R]?|TRACE|FINEST)\b/i

export function parseLogLevel(message: string, stream: string): ParseResult {
  // Check stack trace continuation lines first
  if (STACK_TRACE_RE.test(message)) {
    return { level: 'ERROR', isStackTrace: true, isExceptionStart: false }
  }

  // Skip Docker timestamp (~31 chars) to avoid false positives
  const searchFrom = message.length > 31 ? message.substring(31) : message

  // Check for exception header lines
  const isExceptionStart = EXCEPTION_START_RE.test(searchFrom)
  if (isExceptionStart) {
    return { level: 'ERROR', isStackTrace: false, isExceptionStart: true }
  }

  const match = LEVEL_RE.exec(searchFrom)

  if (match) {
    const token = match[1].toUpperCase()
    if (token === 'FATAL' || token === 'SEVERE' || token === 'ERROR') return { level: 'ERROR', isStackTrace: false, isExceptionStart: false }
    if (token === 'WARN' || token === 'WARNING') return { level: 'WARN', isStackTrace: false, isExceptionStart: false }
    if (token === 'INFO') return { level: 'INFO', isStackTrace: false, isExceptionStart: false }
    if (token === 'DEBUG' || token === 'FINE' || token === 'FINER') return { level: 'DEBUG', isStackTrace: false, isExceptionStart: false }
    if (token === 'TRACE' || token === 'FINEST') return { level: 'TRACE', isStackTrace: false, isExceptionStart: false }
  }

  if (stream === 'STDERR') {
    return { level: 'ERROR', isStackTrace: false, isExceptionStart: false }
  }

  return { level: 'UNKNOWN', isStackTrace: false, isExceptionStart: false }
}
