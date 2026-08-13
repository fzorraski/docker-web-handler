import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ThemeProvider, createTheme } from '@mui/material'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string, opts?: Record<string, unknown>) => {
    if (opts) {
      return Object.entries(opts).reduce((s, [k, v]) => s.replace(`{{${k}}}`, String(v)), key)
    }
    return key
  }}),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

import RestoreAttribution from '../components/RestoreAttribution'
import type { ActiveRestore } from '../services/dumpService'

const theme = createTheme({ palette: { mode: 'dark' } })

function buildRestore(overrides: Partial<ActiveRestore> = {}): ActiveRestore {
  return {
    repository: 'mywms-spk',
    targetDatabase: 'ekkoPROD_20_51_0',
    dumpFilename: 'ekko-PROD.sql',
    startedBy: null,
    tenantId: null,
    ...overrides,
  }
}

function renderAttribution(restore: ActiveRestore, names: Array<[string, string]> = []) {
  const tenants = new Map(names.map(([id, name]) => [id, { id, name, color: '#2196F3' }]))
  return render(
    <ThemeProvider theme={theme}>
      <RestoreAttribution restore={restore} tenants={tenants} />
    </ThemeProvider>,
  )
}

describe('RestoreAttribution', () => {
  it('shows the user and the tenant display name', () => {
    renderAttribution(
      buildRestore({ startedBy: 'alice', tenantId: 'tenant-1' }),
      [['tenant-1', 'Squad Alpha']],
    )

    expect(screen.getByText('common.startedBy')).toBeTruthy()
    expect(screen.getByText('Squad Alpha')).toBeTruthy()
  })

  it('falls back to the tenant id when the name is unknown', () => {
    renderAttribution(buildRestore({ startedBy: 'alice', tenantId: 'tenant-9' }))

    expect(screen.getByText('tenant-9')).toBeTruthy()
  })

  it('shows the user alone when the restore has no tenant', () => {
    const { container } = renderAttribution(buildRestore({ startedBy: 'system' }))

    expect(container.textContent).toContain('common.startedBy')
    expect(container.querySelector('.MuiChip-root')).toBeNull()
  })

  // RBAC off: the backend knows neither, so the row must not leave an empty gap
  it('renders nothing without user or tenant', () => {
    const { container } = renderAttribution(buildRestore())

    expect(container.innerHTML).toBe('')
  })
})
