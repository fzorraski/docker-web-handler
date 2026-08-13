import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ThemeProvider, createTheme } from '@mui/material'

import TenantChip from '../components/TenantChip'
import type { TenantSummary } from '../services/tenantService'

const theme = createTheme({ palette: { mode: 'dark' } })

function renderChip(tenant?: TenantSummary, fallbackLabel = 'tenant-9') {
  return render(
    <ThemeProvider theme={theme}>
      <TenantChip tenant={tenant} fallbackLabel={fallbackLabel} />
    </ThemeProvider>,
  )
}

describe('TenantChip', () => {
  it('paints the chip in the tenant colour', () => {
    const { container } = renderChip({ id: 't1', name: 'Dev-Team', color: '#2196F3' })

    expect(screen.getByText('Dev-Team')).toBeTruthy()
    const style = getComputedStyle(container.querySelector('.MuiChip-root')!)
    // rgb, since jsdom resolves the hex through the computed style
    expect(style.color).toBe('rgb(33, 150, 243)')
  })

  it('gives two tenants different colours', () => {
    const first = renderChip({ id: 't1', name: 'Dev-Team', color: '#2196F3' })
    const second = renderChip({ id: 't2', name: 'Support-Team', color: '#EC407A' })

    const colorOf = (r: ReturnType<typeof renderChip>) =>
      getComputedStyle(r.container.querySelector('.MuiChip-root')!).color

    expect(colorOf(first)).not.toBe(colorOf(second))
  })

  // a chip for a tenant the lookup has not loaded (or that was deleted)
  it('falls back to the id and the theme colour when the tenant is unknown', () => {
    const { container } = renderChip(undefined, 'tenant-9')

    expect(screen.getByText('tenant-9')).toBeTruthy()
    expect(container.querySelector('.MuiChip-colorSecondary')).not.toBeNull()
  })
})
