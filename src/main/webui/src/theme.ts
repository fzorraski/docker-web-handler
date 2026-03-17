import { createTheme, type PaletteMode } from '@mui/material'

const DARK_SURFACE = '#1c2333'

const shared = {
  typography: {
    fontFamily: "'Poppins', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif",
  },
  shape: {
    borderRadius: 8,
  },
}

export function buildTheme(mode: PaletteMode) {
  return createTheme({
    ...shared,
    palette:
      mode === 'light'
        ? {
            mode: 'light',
            primary: { main: '#1e3d59' },
            secondary: { main: '#ff6f61' },
            background: { default: '#f4f6f9', paper: '#ffffff' },
          }
        : {
            mode: 'dark',
            primary: { main: '#58a6ff', dark: DARK_SURFACE },
            secondary: { main: '#ff8a76' },
            background: { default: '#0e1117', paper: '#161b22' },
            text: { primary: '#e6edf3', secondary: '#8b949e' },
            info: { main: '#58a6ff' },
            success: { main: '#3fb950' },
            warning: { main: '#d29922' },
            error: { main: '#f85149' },
          },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            transition: 'background-color 0.3s ease, color 0.3s ease',
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: {
            transition: 'background-color 0.3s ease, box-shadow 0.3s ease',
          },
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: {
            ...(mode === 'dark' && {
              backgroundColor: DARK_SURFACE,
              backgroundImage: 'none',
            }),
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: {
            transition: 'background-color 0.3s ease, color 0.3s ease',
          },
        },
      },
      MuiDialog: {
        styleOverrides: {
          paper: {
            ...(mode === 'dark' && {
              backgroundImage: 'none',
            }),
          },
        },
      },
      MuiLink: {
        styleOverrides: {
          root: {
            ...(mode === 'dark' && {
              color: '#58a6ff',
            }),
          },
        },
      },
      MuiButton: {
        styleOverrides: {
          containedPrimary: {
            ...(mode === 'dark' && {
              backgroundColor: '#58a6ff',
              color: '#0e1117',
              '&:hover': { backgroundColor: '#79b8ff' },
            }),
          },
        },
      },
    },
  })
}

// default export for backwards compatibility
export default buildTheme('light')
