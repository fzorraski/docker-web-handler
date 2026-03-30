// Design system: Vivid Orange on deep dark surfaces.
// Aesthetic: professional DevOps tooling (Datadog, Railway, Planetscale).
// Accent: #FF6D00 (vivid orange). Surfaces layered via micro-contrast borders, not shadows.
// Typography: Inter for UI, JetBrains Mono for technical data (applied per-component via sx).

import { createTheme, type PaletteMode } from '@mui/material'

// ── Accent tokens ──
const ACCENT = '#FF6D00'
const ACCENT_HOVER = '#FF8F33'
const ACCENT_DARK = '#E65100'

// ── Dark palette surfaces (layered depth) ──
const D_BG = '#0D0F14'
const D_SURFACE = '#1A1D27'
const D_NAV = '#13151C'
const D_ELEVATED = '#222639'
const D_TEXT = '#E8ECF1'
const D_TEXT_MUTED = '#7A8494'

// ── Shared borders ──
const BORDER_SUBTLE = 'rgba(255,255,255,0.07)'
const BORDER_MEDIUM = 'rgba(255,255,255,0.12)'

const shared = {
  typography: {
    fontFamily: "'Inter', 'DM Sans', system-ui, sans-serif",
  },
  shape: {
    borderRadius: 10,
  },
}

export function buildTheme(mode: PaletteMode) {
  const isDark = mode === 'dark'

  return createTheme({
    ...shared,
    palette: isDark
      ? {
          mode: 'dark',
          primary: { main: ACCENT, dark: ACCENT_DARK, light: ACCENT_HOVER },
          secondary: { main: '#7C4DFF' },
          background: { default: D_BG, paper: D_SURFACE },
          text: { primary: D_TEXT, secondary: D_TEXT_MUTED },
          info: { main: '#58A6FF' },
          success: { main: '#00E676' },
          warning: { main: '#FFD600' },
          error: { main: '#FF5252' },
          divider: BORDER_SUBTLE,
        }
      : {
          mode: 'light',
          primary: { main: '#E65100', dark: '#BF360C', light: '#FF6D00' },
          secondary: { main: '#5E35B1' },
          background: { default: '#F5F6FA', paper: '#FFFFFF' },
          text: { primary: '#1C2025', secondary: '#5A6577' },
          info: { main: '#0277BD' },
          success: { main: '#2E7D32' },
          warning: { main: '#F9A825' },
          error: { main: '#C62828' },
          divider: 'rgba(0,0,0,0.08)',
        },
    components: {
      // ── Baseline ──
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            transition: 'background-color 0.3s ease, color 0.3s ease',
          },
          ...(isDark && {
            '*': {
              scrollbarWidth: 'thin',
              scrollbarColor: `${BORDER_MEDIUM} transparent`,
            },
            '*::-webkit-scrollbar': {
              width: 8,
              height: 8,
            },
            '*::-webkit-scrollbar-track': {
              background: 'transparent',
            },
            '*::-webkit-scrollbar-thumb': {
              background: BORDER_MEDIUM,
              borderRadius: 4,
            },
            '*::-webkit-scrollbar-thumb:hover': {
              background: 'rgba(255,255,255,0.2)',
            },
          }),
        },
      },

      // ── Surfaces ──
      MuiPaper: {
        defaultProps: { elevation: isDark ? 0 : 1 },
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            transition: 'background-color 0.3s ease, box-shadow 0.3s ease',
            ...(isDark
              ? { border: `1px solid ${BORDER_SUBTLE}` }
              : { border: '1px solid rgba(0,0,0,0.1)', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }
            ),
          },
        },
      },
      MuiAppBar: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            backgroundColor: isDark ? D_NAV : '#FFFFFF',
            color: isDark ? D_TEXT : '#1C2025',
            borderBottom: `1px solid ${isDark ? BORDER_SUBTLE : 'rgba(0,0,0,0.1)'}`,
          },
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            ...(isDark && {
              backgroundColor: D_NAV,
              backgroundImage: 'none',
              borderLeft: `1px solid ${BORDER_SUBTLE}`,
            }),
          },
        },
      },
      MuiDialog: {
        styleOverrides: {
          paper: {
            backgroundImage: 'none',
            ...(isDark && {
              backgroundColor: D_SURFACE,
              border: `1px solid ${BORDER_MEDIUM}`,
            }),
          },
        },
      },
      MuiMenu: {
        styleOverrides: {
          paper: {
            ...(isDark && {
              backgroundColor: D_ELEVATED,
              backgroundImage: 'none',
              border: `1px solid ${BORDER_MEDIUM}`,
            }),
          },
        },
      },

      // ── Tables ──
      MuiTableContainer: {
        styleOverrides: {
          root: {
            borderRadius: 10,
            overflowX: 'auto',
            ...(!isDark && {
              border: '1px solid rgba(0,0,0,0.1)',
              boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
            }),
          },
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: {
            transition: 'background-color 0.15s ease',
            '&:nth-of-type(even):not(.MuiTableRow-head)': {
              backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.015)',
            },
            '&.MuiTableRow-hover:hover': {
              backgroundColor: isDark
                ? 'rgba(255,255,255,0.04) !important'
                : 'rgba(0,0,0,0.03) !important',
            },
            '&.Mui-selected, &.Mui-selected:hover': {
              backgroundColor: isDark
                ? 'rgba(255, 109, 0, 0.10) !important'
                : 'rgba(255, 109, 0, 0.08) !important',
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            borderBottom: isDark
              ? `1px solid ${BORDER_SUBTLE}`
              : '1px solid rgba(0,0,0,0.08)',
          },
          head: {
            fontSize: '0.7rem',
            fontWeight: 700,
            textTransform: 'uppercase' as const,
            letterSpacing: '0.08em',
            ...(isDark
              ? {
                  color: D_TEXT_MUTED,
                  backgroundColor: D_SURFACE,
                }
              : {
                  color: '#5A6577',
                  backgroundColor: '#F0F2F5',
                }
            ),
          },
        },
      },

      // ── Interactive ──
      MuiChip: {
        styleOverrides: {
          root: {
            transition: 'background-color 0.2s ease, color 0.2s ease',
            fontWeight: 600,
          },
        },
      },
      MuiButton: {
        styleOverrides: {
          root: {
            textTransform: 'none' as const,
            fontWeight: 600,
            borderRadius: 6,
            transition: 'background-color 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease, transform 0.15s ease',
            '&:active': { transform: 'scale(0.97)' },
          },
          containedPrimary: {
            ...(isDark && {
              backgroundColor: ACCENT,
              color: '#FFFFFF',
              '&:hover': { backgroundColor: ACCENT_HOVER },
            }),
          },
          containedSuccess: {
            ...(isDark && {
              backgroundColor: '#00E676',
              color: D_BG,
              '&:hover': { backgroundColor: '#33EB91' },
            }),
          },
          // Destructive buttons: red text + border, NOT solid fill
          containedError: {
            ...(isDark && {
              backgroundColor: 'transparent',
              color: '#FF5252',
              border: '1px solid rgba(255,82,82,0.4)',
              boxShadow: 'none',
              '&:hover': {
                backgroundColor: 'rgba(255,82,82,0.08)',
                borderColor: 'rgba(255,82,82,0.6)',
                boxShadow: 'none',
              },
            }),
          },
          containedWarning: {
            ...(isDark && {
              backgroundColor: 'transparent',
              color: '#FFD600',
              border: '1px solid rgba(255,214,0,0.4)',
              boxShadow: 'none',
              '&:hover': {
                backgroundColor: 'rgba(255,214,0,0.08)',
                borderColor: 'rgba(255,214,0,0.6)',
                boxShadow: 'none',
              },
            }),
          },
          outlinedPrimary: {
            ...(isDark && {
              borderColor: 'rgba(255,255,255,0.15)',
              color: D_TEXT,
              '&:hover': {
                borderColor: 'rgba(255,255,255,0.3)',
                backgroundColor: 'rgba(255,255,255,0.04)',
              },
            }),
          },
        },
      },
      MuiIconButton: {
        styleOverrides: {
          root: {
            transition: 'background-color 0.2s ease, color 0.2s ease, transform 0.15s ease',
            '&:active': { transform: 'scale(0.92)' },
          },
        },
      },

      // ── Forms ──
      MuiTextField: {
        styleOverrides: {
          root: {
            '& .MuiOutlinedInput-root': {
              borderRadius: 6,
              ...(isDark
                ? {
                    backgroundColor: D_SURFACE,
                    '& fieldset': { borderColor: 'rgba(255,255,255,0.1)' },
                    '&:hover fieldset': { borderColor: 'rgba(255,255,255,0.2)' },
                    '&.Mui-focused fieldset': { borderColor: ACCENT },
                  }
                : {
                    '& fieldset': { borderColor: 'rgba(0,0,0,0.15)' },
                    '&:hover fieldset': { borderColor: 'rgba(0,0,0,0.25)' },
                    '&.Mui-focused fieldset': { borderColor: ACCENT_DARK },
                  }
              ),
            },
          },
        },
      },
      MuiInputLabel: {
        styleOverrides: {
          root: {
            fontSize: '0.75rem',
            textTransform: 'uppercase' as const,
            letterSpacing: '0.06em',
          },
        },
      },
      MuiSelect: {
        styleOverrides: {
          root: {
            ...(isDark && {
              borderRadius: 6,
            }),
          },
        },
      },

      // ── Navigation ──
      MuiTabs: {
        styleOverrides: {
          indicator: {
            ...(isDark && { backgroundColor: ACCENT }),
          },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: {
            textTransform: 'none' as const,
            fontWeight: 600,
            ...(isDark && {
              '&.Mui-selected': { color: ACCENT },
            }),
          },
        },
      },
      MuiLink: {
        styleOverrides: {
          root: {
            ...(isDark && { color: ACCENT }),
          },
        },
      },

      // ── Feedback ──
      MuiLinearProgress: {
        styleOverrides: {
          root: {
            borderRadius: 4,
            ...(isDark && {
              backgroundColor: 'rgba(255,255,255,0.06)',
            }),
          },
          bar: {
            borderRadius: 4,
          },
        },
      },
      MuiAlert: {
        styleOverrides: {
          root: {
            borderRadius: 8,
          },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            ...(isDark && {
              backgroundColor: D_ELEVATED,
              border: `1px solid ${BORDER_MEDIUM}`,
              color: D_TEXT,
            }),
          },
        },
      },
      MuiSwitch: {
        styleOverrides: {
          switchBase: {
            ...(isDark && {
              '&.Mui-checked': {
                color: ACCENT,
                '& + .MuiSwitch-track': {
                  backgroundColor: ACCENT,
                  opacity: 0.5,
                },
              },
            }),
          },
        },
      },
      MuiSlider: {
        styleOverrides: {
          root: {
            ...(isDark && {
              color: ACCENT,
            }),
          },
        },
      },
      MuiStepper: {
        styleOverrides: {
          root: {
            ...(isDark && {
              '& .MuiStepIcon-root.Mui-active': { color: ACCENT },
              '& .MuiStepIcon-root.Mui-completed': { color: '#00E676' },
            }),
          },
        },
      },
    },
  })
}

// default export for backwards compatibility
export default buildTheme('dark')
