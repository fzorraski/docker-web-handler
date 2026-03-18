// Log color themes — aligned with the design system accent and semantic palette.
// Dark colors: cyan/green/amber/red on #0D0F14 surfaces (WCAG AA compliant).

import type { LogLevel } from './logLevelParser'

type ColorSet = Record<LogLevel, string>

const DARK_COLORS: ColorSet = {
  ERROR:   '#FF5252',
  WARN:    '#FFAB00',
  INFO:    '#00E676',
  DEBUG:   '#FF6D00',
  TRACE:   '#7A8494',
  UNKNOWN: '#E8ECF1',
}

const LIGHT_COLORS: ColorSet = {
  ERROR:   '#c62828',
  WARN:    '#e65100',
  INFO:    '#2e7d32',
  DEBUG:   '#E65100',
  TRACE:   '#757575',
  UNKNOWN: '#424242',
}

export function getLogLevelColors(isDark: boolean): ColorSet {
  return isDark ? DARK_COLORS : LIGHT_COLORS
}

export function getStackTraceColor(isDark: boolean): string {
  return isDark ? '#FF5252' : '#b71c1c'
}

export function getExcColor(isDark: boolean): string {
  return isDark ? '#FFAB00' : '#e65100'
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
    stackTraceColor: '#FF5252',
    excColor: '#FFAB00',
    toolbarBg: '#13151C',
    toolbarBorder: 'rgba(255,255,255,0.07)',
    logViewerBg: '#0D0F14',
    searchBg: '#1A1D27',
    searchText: '#E8ECF1',
    searchPlaceholder: '#7A8494',
    searchBorder: 'rgba(255,255,255,0.1)',
    searchBorderHover: 'rgba(255,255,255,0.2)',
    searchBorderFocus: '#FF6D00',
    iconColor: '#E8ECF1',
    iconDisabled: 'rgba(255,255,255,0.2)',
    chipInactive: '#7A8494',
    chipBorderInactive: 'rgba(255,255,255,0.1)',
    emptyText: '#7A8494',
    highlightMark: '#FFAB00',
    excHighlightBg: 'rgba(255,82,82,0.15)',
    excSubtleBg: 'rgba(255,82,82,0.05)',
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
    searchBorderFocus: '#E65100',
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
