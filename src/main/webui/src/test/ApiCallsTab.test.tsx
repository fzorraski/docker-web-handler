import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ThemeProvider, createTheme } from '@mui/material'
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider'
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs'

/* ------------------------------------------------------------------ */
/*  Mocks                                                              */
/* ------------------------------------------------------------------ */

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

vi.mock('../hooks/useTableHeaderTheme', () => ({
  useTableHeaderTheme: () => ({ theadBg: '#000', theadColor: '#fff', theadSortSx: {} }),
}))

const { mockGetEndpoints, mockGetThreads, mockGetApiCalls } = vi.hoisted(() => ({
  mockGetEndpoints: vi.fn(),
  mockGetThreads: vi.fn(),
  mockGetApiCalls: vi.fn(),
}))

vi.mock('../services/logAnalyzerService', () => ({
  getEndpoints: mockGetEndpoints,
  getThreads: mockGetThreads,
  getApiCalls: mockGetApiCalls,
}))

import { ApiCallsTab } from '../components/log-analyzer/ApiCallsTab'

const theme = createTheme({ palette: { mode: 'dark' } })

function renderTab(props: Partial<React.ComponentProps<typeof ApiCallsTab>> = {}) {
  return render(
    <ThemeProvider theme={theme}>
      <LocalizationProvider dateAdapter={AdapterDayjs}>
        <ApiCallsTab
          analysisId="test-123"
          sensitiveFields={[]}
          timeRangeStart="2026-04-17T02:00:00"
          timeRangeEnd="2026-04-17T12:00:00"
          {...props}
        />
      </LocalizationProvider>
    </ThemeProvider>,
  )
}

/* ------------------------------------------------------------------ */
/*  Tests                                                              */
/* ------------------------------------------------------------------ */

beforeEach(() => {
  vi.clearAllMocks()
  mockGetEndpoints.mockResolvedValue([])
  mockGetThreads.mockResolvedValue([])
  mockGetApiCalls.mockResolvedValue({ data: [], total: 0, page: 0, size: 25 })
})

describe('ApiCallsTab – initial time range from Performance Insights', () => {

  it('calls onTimeRangeConsumed when initialTimeFrom/initialTimeTo are provided', async () => {
    const onTimeRangeConsumed = vi.fn()

    renderTab({
      initialTimeFrom: '2026-04-17T07:30:00.000Z',
      initialTimeTo: '2026-04-17T07:45:00.000Z',
      onTimeRangeConsumed,
    })

    await vi.waitFor(() => {
      expect(onTimeRangeConsumed).toHaveBeenCalledOnce()
    })
  })

  it('does not call onTimeRangeConsumed when initial values are null', () => {
    const onTimeRangeConsumed = vi.fn()

    renderTab({
      initialTimeFrom: null,
      initialTimeTo: null,
      onTimeRangeConsumed,
    })

    expect(onTimeRangeConsumed).not.toHaveBeenCalled()
  })

  it('opens the time filter panel when initial time range is provided', async () => {
    const onTimeRangeConsumed = vi.fn()

    renderTab({
      initialTimeFrom: '2026-04-17T07:30:00.000Z',
      initialTimeTo: '2026-04-17T07:45:00.000Z',
      onTimeRangeConsumed,
    })

    // Time filter section should be expanded — MUI DatePicker renders label text
    // in multiple places, so use getAllByText and check at least one is visible
    const fromLabels = await screen.findAllByText('logAnalyzer.apiCalls.timeFrom')
    expect(fromLabels.length).toBeGreaterThan(0)
    expect(fromLabels[0]).toBeVisible()

    const toLabels = screen.getAllByText('logAnalyzer.apiCalls.timeTo')
    expect(toLabels.length).toBeGreaterThan(0)
    expect(toLabels[0]).toBeVisible()
  })

  it('passes time range to API call after initial values are applied', async () => {
    const onTimeRangeConsumed = vi.fn()

    renderTab({
      initialTimeFrom: '2026-04-17T07:30:00.000Z',
      initialTimeTo: '2026-04-17T07:45:00.000Z',
      onTimeRangeConsumed,
    })

    // Wait for the debounced fetch to fire with time params
    await vi.waitFor(() => {
      const calls = mockGetApiCalls.mock.calls
      const hasTimeFilter = calls.some(([, params]) =>
        params?.timeFrom && params?.timeTo,
      )
      expect(hasTimeFilter).toBe(true)
    }, { timeout: 2000 })
  })
})
