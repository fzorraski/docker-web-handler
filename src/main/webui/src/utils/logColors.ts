import type { LogLevel } from './logLevelParser'

type ColorSet = Record<LogLevel, string>

// Colors optimized for dark backgrounds (#1a1a2e)
const DARK_COLORS: ColorSet = {
  ERROR:   '#ff6b6b',
  WARN:    '#ffd93d',
  INFO:    '#6bcb77',
  DEBUG:   '#4d96ff',
  TRACE:   '#8b8b8b',
  UNKNOWN: '#e0e0e0',
}

// Colors optimized for light backgrounds (#f8f9fb)
const LIGHT_COLORS: ColorSet = {
  ERROR:   '#c62828',
  WARN:    '#e65100',
  INFO:    '#2e7d32',
  DEBUG:   '#1565c0',
  TRACE:   '#757575',
  UNKNOWN: '#424242',
}

export function getLogLevelColors(isDark: boolean): ColorSet {
  return isDark ? DARK_COLORS : LIGHT_COLORS
}

export function getStackTraceColor(isDark: boolean): string {
  return isDark ? '#cc5555' : '#b71c1c'
}

export function getExcColor(isDark: boolean): string {
  return isDark ? '#ff9f43' : '#e65100'
}

export interface LogTheme {
  levelColors: ColorSet
  stackTraceColor: string
  excColor: string
  toolbarBg: string
  toolbarBorder: string
  logViewerBg: string
  searchBg: string
  searchText: string
  searchPlaceholder: string
  searchBorder: string
  searchBorderHover: string
  searchBorderFocus: string
  iconColor: string
  iconDisabled: string
  chipInactive: string
  chipBorderInactive: string
  emptyText: string
  highlightMark: string
  excHighlightBg: string
  excSubtleBg: string
}

export function getLogTheme(isDark: boolean): LogTheme {
  return isDark ? {
    levelColors: DARK_COLORS,
    stackTraceColor: '#cc5555',
    excColor: '#ff9f43',
    toolbarBg: '#1e1e3a',
    toolbarBorder: '#333355',
    logViewerBg: '#1a1a2e',
    searchBg: '#12122a',
    searchText: '#e0e0e0',
    searchPlaceholder: '#666',
    searchBorder: '#444',
    searchBorderHover: '#666',
    searchBorderFocus: '#4d96ff',
    iconColor: '#ccc',
    iconDisabled: '#444',
    chipInactive: '#555',
    chipBorderInactive: '#444',
    emptyText: '#666',
    highlightMark: '#ffd93d',
    excHighlightBg: 'rgba(255,159,67,0.2)',
    excSubtleBg: 'rgba(255,159,67,0.05)',
  } : {
    levelColors: LIGHT_COLORS,
    stackTraceColor: '#b71c1c',
    excColor: '#e65100',
    toolbarBg: '#eef1f6',
    toolbarBorder: '#d0d7e0',
    logViewerBg: '#e8eaed',
    searchBg: '#ffffff',
    searchText: '#333',
    searchPlaceholder: '#999',
    searchBorder: '#c0c8d0',
    searchBorderHover: '#90a0b0',
    searchBorderFocus: '#1565c0',
    iconColor: '#555',
    iconDisabled: '#ccc',
    chipInactive: '#999',
    chipBorderInactive: '#ccc',
    emptyText: '#999',
    highlightMark: '#fff176',
    excHighlightBg: 'rgba(230,81,0,0.12)',
    excSubtleBg: 'rgba(230,81,0,0.04)',
  }
}
