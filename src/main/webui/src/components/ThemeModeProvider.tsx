import { createContext, useContext, useState, useEffect, useMemo, useCallback, type ReactNode } from 'react'
import { ThemeProvider, CssBaseline, type PaletteMode, useMediaQuery } from '@mui/material'
import { buildTheme } from '../theme'

const STORAGE_KEY = 'app-theme-mode'

interface ThemeModeContextValue {
  mode: PaletteMode
  toggleMode: () => void
}

const ThemeModeContext = createContext<ThemeModeContextValue>({
  mode: 'light',
  toggleMode: () => {},
})

export function useThemeMode() {
  return useContext(ThemeModeContext)
}

function getStoredMode(): PaletteMode | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch { /* ignore */ }
  return null
}

export default function ThemeModeProvider({ children }: { children: ReactNode }) {
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)', { noSsr: true })
  const [mode, setMode] = useState<PaletteMode>(() => getStoredMode() ?? (prefersDark ? 'dark' : 'light'))

  useEffect(() => {
    if (!getStoredMode()) {
      setMode(prefersDark ? 'dark' : 'light')
    }
  }, [prefersDark])

  const toggleMode = useCallback(() => {
    setMode((prev) => {
      const next = prev === 'light' ? 'dark' : 'light'
      try { localStorage.setItem(STORAGE_KEY, next) } catch { /* ignore */ }
      return next
    })
  }, [])

  const theme = useMemo(() => buildTheme(mode), [mode])
  const contextValue = useMemo(() => ({ mode, toggleMode }), [mode, toggleMode])

  return (
    <ThemeModeContext.Provider value={contextValue}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </ThemeModeContext.Provider>
  )
}
