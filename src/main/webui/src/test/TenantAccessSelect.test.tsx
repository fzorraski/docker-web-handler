import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

const listTenants = vi.fn()
vi.mock('../services/tenantService', () => ({ listTenants: () => listTenants() }))

const useAuth = vi.fn()
vi.mock('../components/AuthProvider', () => ({ useAuth: () => useAuth() }))

import TenantAccessSelect from '../components/TenantAccessSelect'
import { P } from '../utils/permissions'

const theme = createTheme({ palette: { mode: 'dark' } })

const ALPHA = { id: 'a', name: 'Alpha' }
const BETA = { id: 'b', name: 'Beta' }
const GAMMA = { id: 'c', name: 'Gamma' }

function asAdmin() {
  useAuth.mockReturnValue({
    rbacEnabled: true,
    currentUser: { tenants: [ALPHA] },
    hasPermission: (p: string) => p === P.TENANTS_VIEW_ALL,
  })
  listTenants.mockResolvedValue([ALPHA, BETA, GAMMA])
}

function asMember(tenants = [ALPHA, BETA]) {
  useAuth.mockReturnValue({
    rbacEnabled: true,
    currentUser: { tenants },
    hasPermission: () => false,
  })
  listTenants.mockResolvedValue([ALPHA, BETA, GAMMA])
}

let onChange: ReturnType<typeof vi.fn<(ids: string[]) => void>>

/** Controlled wrapper so clicks accumulate the way the real modal does. */
function Harness({ initial = [] as string[] }) {
  const [value, setValue] = useState<string[]>(initial)
  return (
    <ThemeProvider theme={theme}>
      <TenantAccessSelect
        value={value}
        onChange={(ids) => { onChange(ids); setValue(ids) }}
      />
    </ThemeProvider>
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  onChange = vi.fn()
})

describe('TenantAccessSelect', () => {
  it('renders nothing when the user has no tenant choice to make', () => {
    useAuth.mockReturnValue({
      rbacEnabled: false,
      currentUser: null,
      hasPermission: () => true,
    })
    const { container } = render(<Harness />)
    expect(container).toBeEmptyDOMElement()
  })

  it('offers every tenant plus both special rows to an admin', async () => {
    asAdmin()
    const user = userEvent.setup()
    render(<Harness />)

    await waitFor(() => expect(listTenants).toHaveBeenCalled())
    await user.click(screen.getByRole('combobox'))

    expect(screen.getByRole('option', { name: /tenants.noneOption/ })).toBeTruthy()
    expect(screen.getByRole('option', { name: /tenants.allTenantsOption/ })).toBeTruthy()
    for (const name of ['Alpha', 'Beta', 'Gamma']) {
      expect(screen.getByRole('option', { name })).toBeTruthy()
    }
  })

  it('shows the "none" state as a chip rather than a blank field', () => {
    asAdmin()
    render(<Harness />)
    // MUI skips renderValue for an empty array unless displayEmpty is set
    expect(screen.getByText('tenants.noneOption')).toBeTruthy()
  })

  it('limits a member to their own tenants and preselects the first', async () => {
    asMember()
    const user = userEvent.setup()
    render(<Harness />)

    await waitFor(() => expect(onChange).toHaveBeenCalledWith(['a']))
    expect(listTenants).not.toHaveBeenCalled()

    await user.click(screen.getByRole('combobox'))
    expect(screen.queryByRole('option', { name: /tenants.noneOption/ })).toBeNull()
    expect(screen.getByRole('option', { name: /tenants.allMyTenantsOption/ })).toBeTruthy()
    expect(screen.queryByRole('option', { name: 'Gamma' })).toBeNull()
  })

  it('makes the first tenant clicked the owner', async () => {
    asAdmin()
    const user = userEvent.setup()
    render(<Harness />)
    await waitFor(() => expect(listTenants).toHaveBeenCalled())

    await user.click(screen.getByRole('combobox'))
    await user.click(screen.getByRole('option', { name: 'Beta' }))
    await user.click(screen.getByRole('option', { name: 'Alpha' }))

    expect(onChange).toHaveBeenLastCalledWith(['b', 'a'])

    // the owner chip renders first (close the menu: while it is open MUI hides
    // the field from the accessibility tree)
    await user.keyboard('{Escape}')
    const chips = screen.getByRole('combobox').textContent ?? ''
    expect(chips.indexOf('Beta')).toBeLessThan(chips.indexOf('Alpha'))
  })

  it('keeps the current owner when "all tenants" is picked', async () => {
    asAdmin()
    const user = userEvent.setup()
    render(<Harness />)
    await waitFor(() => expect(listTenants).toHaveBeenCalled())

    await user.click(screen.getByRole('combobox'))
    await user.click(screen.getByRole('option', { name: 'Beta' }))
    await user.click(screen.getByRole('option', { name: /tenants.allTenantsOption/ }))

    expect(onChange).toHaveBeenLastCalledWith(['b', 'a', 'c'])
  })
})
