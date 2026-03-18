// Table header theme hook — returns theme-aware header styles.
// Dark: muted text on near-transparent bg. Light: muted text on soft gray bg.
// Both use uppercase micro-labels from the theme's MuiTableCell.head override.

import { useMemo } from 'react'
import { useTheme, type SxProps, type Theme } from '@mui/material'

interface TableHeaderTheme {
  theadBg: string
  theadColor: string
  theadSortSx: SxProps<Theme>
  theadCheckboxSx: SxProps<Theme>
}

export function useTableHeaderTheme(): TableHeaderTheme {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  return useMemo(() => ({
    theadBg: isDark ? 'rgba(255,255,255,0.03)' : '#F0F2F5',
    theadColor: isDark ? 'text.secondary' : '#5A6577',
    theadSortSx: isDark
      ? {
          color: 'text.secondary !important',
          '&.Mui-active': { color: 'text.primary !important' },
          '& .MuiTableSortLabel-icon': { color: 'text.secondary !important' },
        }
      : {
          color: '#5A6577 !important',
          '&.Mui-active': { color: '#1C2025 !important' },
          '& .MuiTableSortLabel-icon': { color: '#5A6577 !important' },
        },
    theadCheckboxSx: isDark
      ? { color: 'text.secondary', '&.Mui-checked': { color: 'primary.main' }, '&.MuiCheckbox-indeterminate': { color: 'primary.main' } }
      : { color: '#5A6577', '&.Mui-checked': { color: 'primary.main' }, '&.MuiCheckbox-indeterminate': { color: 'primary.main' } },
  }), [isDark])
}
