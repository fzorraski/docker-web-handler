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
    theadBg: isDark ? 'background.paper' : 'primary.main',
    theadColor: isDark ? 'text.primary' : 'white',
    theadSortSx: isDark
      ? { color: 'text.primary !important', '& .MuiTableSortLabel-icon': { color: 'text.secondary !important' } }
      : { color: 'white !important', '& .MuiTableSortLabel-icon': { color: 'white !important' } },
    theadCheckboxSx: isDark
      ? { color: 'text.primary', '&.Mui-checked': { color: 'primary.main' }, '&.MuiCheckbox-indeterminate': { color: 'primary.main' } }
      : { color: 'white', '&.Mui-checked': { color: 'white' }, '&.MuiCheckbox-indeterminate': { color: 'white' } },
  }), [isDark])
}
