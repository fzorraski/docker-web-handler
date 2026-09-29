import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider, createTheme } from '@mui/material'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

import OperationProgress from '../components/OperationProgress'
import type { ContainerEvent } from '../services/sseService'

const LONG_ERROR =
  "Restore process exited with code 1. PostgreSQL reported 1 error(s): pg_restore: error: could not execute query: " +
  'ERROR:  unrecognized configuration parameter "transaction_timeout"'
const ERROR_LINE = 'pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter "transaction_timeout"'
const CONTEXT_LINE = 'Command was: SET transaction_timeout = 0;'

const events: ContainerEvent[] = [
  { type: 'INFO', step: 'Validating', message: 'Validation passed.' },
  { type: 'INFO', step: 'Restoring', message: 'Creating ephemeral restore container...' },
  { type: 'PROGRESS', step: 'Restoring', message: 'SET  (1 lines processed)', progress: -1 },
  { type: 'PROGRESS', step: 'Restoring', message: ERROR_LINE, progress: -1 },
  { type: 'PROGRESS', step: 'Restoring', message: CONTEXT_LINE, progress: -1 },
  { type: 'ERROR', step: 'Restoring', message: LONG_ERROR },
]

/** testing-library collapses whitespace in the DOM but not in a string matcher; psql prints double spaces after "ERROR:". */
const byText = (text: string) => screen.getByText(text.replace(/\s+/g, ' '))

async function renderProgress() {
  render(
    <ThemeProvider theme={createTheme()}>
      <OperationProgress events={events} steps={['Validating', 'Restoring', 'Running Scripts', 'Starting']} />
    </ThemeProvider>,
  )
  // the event log is collapsed until the user asks for details
  await userEvent.click(screen.getByText('logAnalyzer.upload.showDetails'))
}

/**
 * A failed restore once showed only "exited with code 1, check logs above"
 * with the real PostgreSQL error cut off or never displayed. The dialog must
 * show the whole final error and the streamed error lines that explain it.
 */
describe('OperationProgress error readability', () => {
  it('renders the full final error message and lets it wrap', async () => {
    await renderProgress()

    const line = byText(LONG_ERROR)
    expect(line).toHaveStyle({ whiteSpace: 'pre-wrap' })
  })

  it('shows the streamed PostgreSQL error line and its context line', async () => {
    await renderProgress()

    expect(byText(ERROR_LINE)).toHaveStyle({ whiteSpace: 'pre-wrap' })
    expect(byText(CONTEXT_LINE)).toHaveStyle({ whiteSpace: 'pre-wrap' })
  })

  it('keeps plain progress lines on one line', async () => {
    await renderProgress()

    expect(byText('SET  (1 lines processed)')).toHaveStyle({ whiteSpace: 'nowrap' })
  })
})
