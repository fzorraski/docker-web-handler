import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
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

import PostRestoreScriptsSection from '../components/PostRestoreScriptsSection'
import type { PostRestoreScriptsResponse } from '../services/dumpService'

const theme = createTheme({ palette: { mode: 'dark' } })

function buildScripts(overrides: Partial<PostRestoreScriptsResponse> = {}): PostRestoreScriptsResponse {
  return {
    enabled: true,
    mandatory: [
      { filename: '01-clean.sql', sortOrder: 1, fileSize: 1100 },
      { filename: '02-fix-ports.sql', sortOrder: 2, fileSize: 250 },
    ],
    optional: [
      { filename: 'create-user.sql', sortOrder: 1, fileSize: 817 },
      { filename: 'stop-jobs.sql', sortOrder: 2, fileSize: 111 },
      { filename: 'update-password.sql', sortOrder: 3, fileSize: 188 },
    ],
    onFailure: 'stop',
    ...overrides,
  }
}

let onChange: ReturnType<typeof vi.fn<(scripts: string[]) => void>>

function renderSection(
  scriptsResponse = buildScripts(),
  selectedOptionalScripts: string[] = [],
) {
  onChange = vi.fn<(scripts: string[]) => void>()
  return render(
    <ThemeProvider theme={theme}>
      <PostRestoreScriptsSection
        scriptsResponse={scriptsResponse}
        selectedOptionalScripts={selectedOptionalScripts}
        onSelectedOptionalScriptsChange={onChange}
        tPrefix="newContainer"
      />
    </ThemeProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('PostRestoreScriptsSection', () => {

  // ---- Default collapse state ----

  it('renders mandatory section collapsed by default', () => {
    renderSection()
    const list = screen.getByTestId('mandatory-scripts-list')
    expect(list).not.toBeVisible()
  })

  it('renders optional section expanded by default', () => {
    renderSection()
    const list = screen.getByTestId('optional-scripts-list')
    expect(list).toBeVisible()
  })

  // ---- Toggle collapse ----

  it('expands mandatory section on header click', async () => {
    renderSection()
    const header = screen.getByTestId('mandatory-scripts-header')
    await userEvent.click(header)
    expect(screen.getByTestId('mandatory-scripts-list')).toBeVisible()
  })

  it('collapses optional section on header click', async () => {
    renderSection()
    const header = screen.getByTestId('optional-scripts-header')
    await userEvent.click(header)
    await waitFor(() => {
      expect(screen.getByTestId('optional-scripts-list')).not.toBeVisible()
    })
  })

  // ---- Script count chips ----

  it('shows mandatory script count in header', () => {
    renderSection()
    const header = screen.getByTestId('mandatory-scripts-header')
    expect(within(header).getByText('newContainer.scriptsCount')).toBeInTheDocument()
  })

  it('shows optional script count when none selected', () => {
    renderSection()
    const header = screen.getByTestId('optional-scripts-header')
    expect(within(header).getByText('newContainer.scriptsCount')).toBeInTheDocument()
  })

  it('shows selected count when some optional scripts are selected', () => {
    renderSection(buildScripts(), ['create-user.sql'])
    const header = screen.getByTestId('optional-scripts-header')
    expect(within(header).getByText('newContainer.scriptsSelectedCount')).toBeInTheDocument()
  })

  // ---- Script items ----

  it('renders all mandatory scripts when expanded', async () => {
    renderSection()
    await userEvent.click(screen.getByTestId('mandatory-scripts-header'))
    expect(screen.getByText('01-clean.sql')).toBeVisible()
    expect(screen.getByText('02-fix-ports.sql')).toBeVisible()
  })

  it('renders all optional scripts (expanded by default)', () => {
    renderSection()
    expect(screen.getByText('create-user.sql')).toBeVisible()
    expect(screen.getByText('stop-jobs.sql')).toBeVisible()
    expect(screen.getByText('update-password.sql')).toBeVisible()
  })

  // ---- Select all / Deselect all ----

  it('shows Select all link when optional is expanded', () => {
    renderSection()
    expect(screen.getByTestId('toggle-select-all')).toHaveTextContent('newContainer.selectAll')
  })

  it('hides Select all link when optional is collapsed', async () => {
    renderSection()
    await userEvent.click(screen.getByTestId('optional-scripts-header'))
    expect(screen.queryByTestId('toggle-select-all')).not.toBeInTheDocument()
  })

  it('calls onChange with all filenames on Select all click', async () => {
    renderSection()
    await userEvent.click(screen.getByTestId('toggle-select-all'))
    expect(onChange).toHaveBeenCalledWith(['create-user.sql', 'stop-jobs.sql', 'update-password.sql'])
  })

  it('shows Deselect all when all scripts are selected', () => {
    const scripts = buildScripts()
    renderSection(scripts, scripts.optional.map((s) => s.filename))
    expect(screen.getByTestId('toggle-select-all')).toHaveTextContent('newContainer.deselectAll')
  })

  it('calls onChange with empty array on Deselect all click', async () => {
    const scripts = buildScripts()
    renderSection(scripts, scripts.optional.map((s) => s.filename))
    await userEvent.click(screen.getByTestId('toggle-select-all'))
    expect(onChange).toHaveBeenCalledWith([])
  })

  // ---- Individual checkbox toggle ----

  it('calls onChange with added filename when checking an optional script', async () => {
    renderSection()
    const checkboxes = screen.getByTestId('optional-scripts-list').querySelectorAll('input[type="checkbox"]')
    await userEvent.click(checkboxes[0])
    expect(onChange).toHaveBeenCalledWith(['create-user.sql'])
  })

  it('calls onChange without removed filename when unchecking an optional script', async () => {
    renderSection(buildScripts(), ['create-user.sql', 'stop-jobs.sql'])
    const checkboxes = screen.getByTestId('optional-scripts-list').querySelectorAll('input[type="checkbox"]')
    await userEvent.click(checkboxes[0]) // uncheck create-user.sql
    expect(onChange).toHaveBeenCalledWith(['stop-jobs.sql'])
  })

  // ---- Scrollable container ----

  it('mandatory list has max-height for scrolling', async () => {
    renderSection()
    await userEvent.click(screen.getByTestId('mandatory-scripts-header'))
    const list = screen.getByTestId('mandatory-scripts-list')
    expect(list).toHaveStyle({ maxHeight: '200px' })
  })

  it('optional list has max-height for scrolling', () => {
    renderSection()
    const list = screen.getByTestId('optional-scripts-list')
    expect(list).toHaveStyle({ maxHeight: '200px' })
  })

  // ---- On failure text ----

  it('shows on-failure stop text', () => {
    renderSection()
    expect(screen.getByText('newContainer.onFailureStop')).toBeInTheDocument()
  })

  it('shows on-failure continue text when configured', () => {
    renderSection(buildScripts({ onFailure: 'continue' }))
    expect(screen.getByText('newContainer.onFailureContinue')).toBeInTheDocument()
  })

  // ---- Edge cases: empty sections ----

  it('does not render mandatory section when empty', () => {
    renderSection(buildScripts({ mandatory: [] }))
    expect(screen.queryByTestId('mandatory-scripts-header')).not.toBeInTheDocument()
  })

  it('does not render optional section when empty', () => {
    renderSection(buildScripts({ optional: [] }))
    expect(screen.queryByTestId('optional-scripts-header')).not.toBeInTheDocument()
  })
})
