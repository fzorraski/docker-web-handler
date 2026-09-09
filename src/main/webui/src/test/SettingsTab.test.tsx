import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'

// `t` must be referentially stable: the tab's load effect depends on it, exactly like the real i18next hook.
const stableT = (key: string) => key
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: stableT }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

const { mockList, mockUpdate, mockReset, mockNotify } = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockUpdate: vi.fn(),
  mockReset: vi.fn(),
  mockNotify: vi.fn(),
}))

vi.mock('../services/settingsService', () => ({
  listSettings: mockList,
  updateSettings: mockUpdate,
  resetSetting: mockReset,
}))

vi.mock('../components/NotificationProvider', () => ({
  useNotification: () => ({ notify: mockNotify }),
}))

import SettingsTab, { isContainerPath } from '../components/admin/SettingsTab'

const pathSetting = {
  key: 'terminalImageUploadPath', type: 'string' as const, value: '/tmp', defaultValue: '/tmp', overridden: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  mockList.mockResolvedValue([pathSetting])
  mockUpdate.mockImplementation(async (changes: Record<string, unknown>) => [
    { ...pathSetting, value: changes.terminalImageUploadPath, overridden: true },
  ])
})

describe('isContainerPath', () => {
  it('accepts absolute safe directories and rejects the rest', () => {
    expect(isContainerPath('/tmp')).toBe(true)
    expect(isContainerPath(' /workspace/attachments ')).toBe(true)
    expect(isContainerPath('tmp')).toBe(false)
    expect(isContainerPath('/tmp/../etc')).toBe(false)
    expect(isContainerPath('/tmp; rm -rf /')).toBe(false)
    expect(isContainerPath('')).toBe(false)
  })
})

describe('SettingsTab string settings', () => {
  it('renders a text field and saves the trimmed string value', async () => {
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('/tmp')

    fireEvent.change(input, { target: { value: ' /workspace/attachments ' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith({ terminalImageUploadPath: '/workspace/attachments' }))
    expect(await screen.findByDisplayValue('/workspace/attachments')).toBeInTheDocument()
    expect(mockNotify).toHaveBeenCalledWith('settings.saved', 'success')
  })

  it('accepts any non-empty text for string settings that are not container paths', async () => {
    const label = { key: 'someLabel', type: 'string' as const, value: 'old', defaultValue: 'old', overridden: false }
    mockList.mockResolvedValue([label])
    mockUpdate.mockResolvedValue([{ ...label, value: 'hello world', overridden: true }])
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('old')

    fireEvent.change(input, { target: { value: 'hello world' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith({ someLabel: 'hello world' }))
  })

  it('does not save an invalid path', async () => {
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('/tmp')

    fireEvent.change(input, { target: { value: 'relative/dir' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(mockUpdate).not.toHaveBeenCalled()
    expect(input).toHaveAttribute('aria-invalid', 'true')
  })
})
