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
    expect(isContainerPath('/')).toBe(false)
    expect(isContainerPath('/' + 'a'.repeat(4096))).toBe(false)
    expect(isContainerPath('/' + 'a'.repeat(4095))).toBe(true)
    expect(isContainerPath('/tmp; rm -rf /')).toBe(false)
    expect(isContainerPath('')).toBe(false)
  })
})

const onFlag = (key: string, category: string, value: boolean, dependsOn: string | null = null) =>
  ({ key, type: 'boolean' as const, value, defaultValue: true, overridden: false, category, dependsOn, disabledByProperty: null })
const number = (key: string, category: string, dependsOn: string | null, value = 5) =>
  ({ key, type: 'integer' as const, value, defaultValue: value, overridden: false, category, dependsOn, disabledByProperty: null })

describe('SettingsTab grouping and dependencies', () => {
  it('renders category headers in the fixed order', async () => {
    mockList.mockResolvedValue([
      number('auditRetentionDays', 'audit', null, 0),
      onFlag('terminalEnabled', 'terminal', true),
      number('terminalMaxSessions', 'terminal', 'terminalEnabled'),
    ])
    render(<SettingsTab />)
    await screen.findByDisplayValue('5')

    const headers = screen.getAllByText(/^settings\.categories\./).map((el) => el.textContent)
    expect(headers).toEqual(['settings.categories.terminal', 'settings.categories.audit'])
  })

  it('shows the inactive note for a child of an off parent, keeps it editable, and links the note to the control', async () => {
    mockList.mockResolvedValue([
      onFlag('terminalEnabled', 'terminal', false),
      number('terminalMaxSessions', 'terminal', 'terminalEnabled'),
    ])
    mockUpdate.mockResolvedValue([
      onFlag('terminalEnabled', 'terminal', false),
      { ...number('terminalMaxSessions', 'terminal', 'terminalEnabled', 9), overridden: true },
    ])
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('5')

    const note = screen.getByText('settings.inactiveUntil')
    expect(note).toBeInTheDocument()
    expect(input).not.toBeDisabled()
    expect(input).toHaveAttribute('aria-describedby', note.id)

    fireEvent.change(input, { target: { value: '9' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith({ terminalMaxSessions: 9 }))
  })

  it('shows no note when the parent is on', async () => {
    mockList.mockResolvedValue([
      onFlag('terminalEnabled', 'terminal', true),
      number('terminalMaxSessions', 'terminal', 'terminalEnabled'),
    ])
    render(<SettingsTab />)
    await screen.findByDisplayValue('5')

    expect(screen.queryByText('settings.inactiveUntil')).not.toBeInTheDocument()
  })

  it('shows the property note when a restart-only property gates the setting', async () => {
    mockList.mockResolvedValue([
      { ...number('auditRetentionDays', 'audit', null, 0), disabledByProperty: 'audit.enabled' },
    ])
    render(<SettingsTab />)
    await screen.findByDisplayValue('0')

    expect(screen.getByText('settings.inactiveProperty')).toBeInTheDocument()
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

  it('requires the {path} placeholder for the image path template and keeps its spaces', async () => {
    const tpl = { key: 'terminalImagePathTemplate', type: 'string' as const, value: '{path}', defaultValue: '{path}', overridden: false }
    mockList.mockResolvedValue([tpl])
    mockUpdate.mockResolvedValue([{ ...tpl, value: '"{path}" ', overridden: true }])
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('{path}')

    fireEvent.change(input, { target: { value: 'quotes' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(mockUpdate).not.toHaveBeenCalled()

    fireEvent.change(input, { target: { value: '"{path}" ' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith({ terminalImagePathTemplate: '"{path}" ' }))
  })

  it('accepts an empty image path template, meaning type nothing', async () => {
    const tpl = { key: 'terminalImagePathTemplate', type: 'string' as const, value: '{path}', defaultValue: '{path}', overridden: false }
    mockList.mockResolvedValue([tpl])
    mockUpdate.mockResolvedValue([{ ...tpl, value: '', overridden: true }])
    render(<SettingsTab />)
    const input = await screen.findByDisplayValue('{path}')

    fireEvent.change(input, { target: { value: '  ' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => expect(mockUpdate).toHaveBeenCalledWith({ terminalImagePathTemplate: '' }))
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
