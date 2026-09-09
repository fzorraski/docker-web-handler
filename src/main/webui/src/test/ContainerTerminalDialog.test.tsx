import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react'
import { ThemeProvider, createTheme } from '@mui/material'

/* ------------------------------------------------------------------ */
/*  Mocks                                                              */
/* ------------------------------------------------------------------ */

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && Object.keys(opts).length > 0 ? `${key} ${JSON.stringify(opts)}` : key,
  }),
  initReactI18next: { type: '3rdParty', init: () => {} },
}))

const { terminalInstances, mockSendInput, mockSendResize, mockClose, mockUploadImage, mockUploadFile, connectCallbacks } = vi.hoisted(() => ({
  connectCallbacks: { current: null as null | { onDisconnect: () => void } },
  terminalInstances: [] as Array<{
    element: HTMLElement | null
    focus: ReturnType<typeof vi.fn>
    keyHandler?: (ev: KeyboardEvent) => boolean
  }>,
  mockSendInput: vi.fn(),
  mockSendResize: vi.fn(),
  mockClose: vi.fn(),
  mockUploadImage: vi.fn(),
  mockUploadFile: vi.fn(),
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 80
    rows = 24
    focus = vi.fn()
    write = vi.fn()
    dispose = vi.fn()
    loadAddon = vi.fn()
    onData = vi.fn()
    open(element: HTMLElement) {
      terminalInstances.push({ element, focus: this.focus })
    }
    attachCustomKeyEventHandler(handler: (ev: KeyboardEvent) => boolean) {
      terminalInstances[terminalInstances.length - 1].keyHandler = handler
    }
  },
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit = vi.fn() } }))
vi.mock('@xterm/addon-web-links', () => ({ WebLinksAddon: class {} }))
vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

vi.mock('../services/terminalService', () => ({
  connectTerminal: (
    _ticket: string,
    onConnected: () => void,
    _onOutput: unknown,
    _onExit: unknown,
    _onError: unknown,
    onDisconnect: () => void,
  ) => {
    connectCallbacks.current = { onDisconnect }
    onConnected()
    return { sendInput: mockSendInput, sendResize: mockSendResize, close: mockClose }
  },
  uploadFileToContainer: mockUploadFile,
  uploadImageToContainer: mockUploadImage,
}))

import ContainerTerminalDialog from '../components/ContainerTerminalDialog'

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

const theme = createTheme({ palette: { mode: 'dark' } })

function renderDialog(props: Partial<React.ComponentProps<typeof ContainerTerminalDialog>> = {}) {
  return render(
    <ThemeProvider theme={theme}>
      <ContainerTerminalDialog
        open
        ticket="ticket-1"
        containerName="mywms-ama"
        containerId="abcdef1234567890"
        onClose={() => {}}
        uploadEnabled={false}
        imageUploadEnabled
        imageMaxSizeMb={1}
        attachmentsPath="/tmp/attachments"
        terminalPassword="secret"
        {...props}
      />
    </ThemeProvider>,
  )
}

function terminalContainer(): HTMLElement {
  const last = terminalInstances[terminalInstances.length - 1]
  if (!last?.element) throw new Error('xterm was not opened')
  return last.element
}

function imageFile(name = 'shot.png', type = 'image/png', bytes = 16): File {
  return new File([new Uint8Array(bytes)], name, { type })
}

function pendingUpload(): { resolve: (value: unknown) => void } {
  const handle = { resolve: (_v: unknown) => {} }
  mockUploadImage.mockImplementationOnce(() => new Promise((resolve) => { handle.resolve = resolve }))
  return handle
}

function transferFor(files: File[], textOnly = false, text = '', html = ''): DataTransfer {
  const items = textOnly
    ? [{ kind: 'string', type: 'text/plain', getAsFile: () => null }]
    : files.map((f) => ({ kind: 'file', type: f.type, getAsFile: () => f }))
  const plain = textOnly ? 'pasted text' : text
  return {
    items,
    files,
    types: textOnly ? ['text/plain'] : ['Files'],
    getData: (type: string) => (type === 'text/plain' ? plain : type === 'text/html' ? html : ''),
  } as unknown as DataTransfer
}

function setPlatform(platform: string) {
  Object.defineProperty(window.navigator, 'platform', { value: platform, configurable: true })
}

function dispatchPaste(target: HTMLElement, transfer: DataTransfer): Event {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: transfer })
  act(() => { target.dispatchEvent(event) })
  return event
}

function dispatchDrop(target: HTMLElement, transfer: DataTransfer): Event {
  const event = new Event('drop', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', { value: transfer })
  act(() => { target.dispatchEvent(event) })
  return event
}

/* ------------------------------------------------------------------ */
/*  Tests                                                              */
/* ------------------------------------------------------------------ */

beforeEach(() => {
  vi.clearAllMocks()
  terminalInstances.length = 0
  setPlatform('Linux x86_64')
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
  mockUploadImage.mockResolvedValue({ success: true, path: '/tmp/attachments/clip-1-abcd1234.png' })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ContainerTerminalDialog image attachments', () => {
  it('uploads a pasted image and types its path into the prompt', async () => {
    renderDialog()
    const file = imageFile()

    const event = dispatchPaste(terminalContainer(), transferFor([file]))

    expect(event.defaultPrevented).toBe(true)
    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))
    expect(mockUploadImage.mock.calls[0][0]).toBe('abcdef1234567890')
    expect(mockUploadImage.mock.calls[0][1]).toBe(file)
    expect(mockUploadImage.mock.calls[0][2]).toBe('secret')

    await waitFor(() => expect(mockSendInput).toHaveBeenCalledWith('/tmp/attachments/clip-1-abcd1234.png '))
    expect(terminalInstances[0].focus).toHaveBeenCalled()
    expect(screen.getByText(/containers\.terminal\.imageAttached/)).toBeInTheDocument()
  })

  it('wraps the typed path with the configured template', async () => {
    renderDialog({ imagePathTemplate: '"{path}"' })

    dispatchPaste(terminalContainer(), transferFor([imageFile()]))

    await waitFor(() => expect(mockSendInput).toHaveBeenCalledWith('"/tmp/attachments/clip-1-abcd1234.png" '))
  })

  it('uploads without typing anything when the template is empty, but still shows the path', async () => {
    renderDialog({ imagePathTemplate: '' })

    dispatchPaste(terminalContainer(), transferFor([imageFile()]))

    expect(await screen.findByText(/containers\.terminal\.imageAttached/)).toBeInTheDocument()
    expect(mockSendInput).not.toHaveBeenCalled()
  })

  it('lets plain-text pastes through to xterm', () => {
    renderDialog()

    const event = dispatchPaste(terminalContainer(), transferFor([], true))

    expect(event.defaultPrevented).toBe(false)
    expect(mockUploadImage).not.toHaveBeenCalled()
  })

  it('uploads a dropped image', async () => {
    renderDialog()

    const event = dispatchDrop(terminalContainer(), transferFor([imageFile('drag.jpg', 'image/jpeg')]))

    expect(event.defaultPrevented).toBe(true)
    await waitFor(() => expect(mockSendInput).toHaveBeenCalledWith('/tmp/attachments/clip-1-abcd1234.png '))
  })

  it('rejects unsupported image types without uploading', async () => {
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile('icon.svg', 'image/svg+xml')]))

    expect(await screen.findByText('containers.terminal.imageUnsupported')).toBeInTheDocument()
    expect(mockUploadImage).not.toHaveBeenCalled()
    expect(mockSendInput).not.toHaveBeenCalled()
  })

  it('rejects images above the configured size limit', async () => {
    renderDialog({ imageMaxSizeMb: 1 })

    dispatchPaste(terminalContainer(), transferFor([imageFile('big.png', 'image/png', 1024 * 1024 + 1)]))

    expect(await screen.findByText(/containers\.terminal\.imageTooLarge.*"max":1/)).toBeInTheDocument()
    expect(mockUploadImage).not.toHaveBeenCalled()
  })

  it('shows the server error and keeps the prompt untouched when the upload fails', async () => {
    mockUploadImage.mockResolvedValue({ success: false, error: 'Container is not running.' })
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile()]))

    expect(await screen.findByText('Container is not running.')).toBeInTheDocument()
    expect(mockSendInput).not.toHaveBeenCalled()
  })

  it('uploads several pasted images one at a time, in order', async () => {
    let resolveFirst: (value: unknown) => void = () => {}
    mockUploadImage
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce({ success: true, path: '/tmp/attachments/second.png' })
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile('a.png'), imageFile('b.png')]))

    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))
    expect(mockUploadImage).toHaveBeenCalledTimes(1)

    await act(async () => { resolveFirst({ success: true, path: '/tmp/attachments/first.png' }) })

    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(mockSendInput).toHaveBeenCalledTimes(2))
    expect(mockSendInput.mock.calls.map((c) => c[0])).toEqual([
      '/tmp/attachments/first.png ',
      '/tmp/attachments/second.png ',
    ])
  })

  it('ignores image pastes when image attachments are disabled, even with file upload on', () => {
    renderDialog({ imageUploadEnabled: false, uploadEnabled: true })

    const event = dispatchPaste(terminalContainer(), transferFor([imageFile()]))

    expect(event.defaultPrevented).toBe(false)
    expect(mockUploadImage).not.toHaveBeenCalled()
    expect(screen.queryByText('containers.terminal.pasteImageHint')).not.toBeInTheDocument()
  })

  it('shows the paste hint with the platform shortcut', () => {
    renderDialog()

    expect(screen.getByText(/containers\.terminal\.pasteImageHint.*Ctrl\+V/)).toBeInTheDocument()
  })

  it('shows the Cmd shortcut on macOS', () => {
    setPlatform('MacIntel')
    renderDialog()

    expect(screen.getByText(/containers\.terminal\.pasteImageHint.*⌘V/)).toBeInTheDocument()
  })

  it('prefers text over an embedded preview image when both are on the clipboard', () => {
    renderDialog()
    const transfer = transferFor([imageFile('preview.png')], false, 'A1\tB1\nA2\tB2', '<table></table>')

    const event = dispatchPaste(terminalContainer(), transfer)

    expect(event.defaultPrevented).toBe(false)
    expect(mockUploadImage).not.toHaveBeenCalled()
  })
})

describe('ContainerTerminalDialog session safety', () => {
  it('drops an upload that finishes after the dialog was closed, and never starts queued ones', async () => {
    const first = pendingUpload()
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile('a.png'), imageFile('b.png')]))
    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByText('common.close'))
    await act(async () => { first.resolve({ success: true, path: '/tmp/a.png' }) })
    await act(async () => { await Promise.resolve() })

    expect(mockSendInput).not.toHaveBeenCalled()
    expect(mockUploadImage).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/containers\.terminal\.imageAttached/)).not.toBeInTheDocument()
  })

  it('uploads an image copied from a file manager even though its name rides along as text', async () => {
    renderDialog()
    const file = imageFile('shot.png')

    dispatchPaste(terminalContainer(), transferFor([file], false, 'file:///home/u/shot.png'))

    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))
    expect(mockUploadImage.mock.calls[0][1]).toBe(file)
  })

  it('aborts the in-flight request when the dialog closes', async () => {
    pendingUpload()
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile()]))
    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))
    const signal = mockUploadImage.mock.calls[0][4] as AbortSignal
    expect(signal.aborted).toBe(false)

    fireEvent.click(screen.getByText('common.close'))

    expect(signal.aborted).toBe(true)
  })

  it('swallows non-image drops instead of letting the browser navigate to the file', async () => {
    renderDialog()
    const pdf = new File([new Uint8Array(4)], 'report.pdf', { type: 'application/pdf' })

    const event = dispatchDrop(terminalContainer(), transferFor([pdf]))

    expect(event.defaultPrevented).toBe(true)
    expect(mockUploadImage).not.toHaveBeenCalled()
    expect(await screen.findByText('containers.terminal.imageUnsupported')).toBeInTheDocument()
  })

  it('still shows the attached path when the terminal disconnects during the upload', async () => {
    const upload = pendingUpload()
    renderDialog()

    dispatchPaste(terminalContainer(), transferFor([imageFile()]))
    await waitFor(() => expect(mockUploadImage).toHaveBeenCalledTimes(1))
    act(() => { connectCallbacks.current!.onDisconnect() })
    await act(async () => { upload.resolve({ success: true, path: '/tmp/late.png' }) })

    expect(await screen.findByText(/containers\.terminal\.imageAttached.*late\.png/)).toBeInTheDocument()
  })
})

describe('ContainerTerminalDialog paste shortcut', () => {
  function keyEvent(init: KeyboardEventInit & { type?: string }): KeyboardEvent {
    return new KeyboardEvent(init.type ?? 'keydown', init)
  }

  it('hands Ctrl+V to the browser on Linux/Windows so the paste event fires', () => {
    renderDialog()
    const handler = terminalInstances[0].keyHandler
    expect(handler).toBeDefined()

    expect(handler!(keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true }))).toBe(false)
    expect(handler!(keyEvent({ key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true }))).toBe(false)
    expect(handler!(keyEvent({ key: 'c', code: 'KeyC', ctrlKey: true }))).toBe(true)
    expect(handler!(keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true, type: 'keyup' }))).toBe(true)
  })

  it('swallows auto-repeated Ctrl+V so it neither pastes again nor sends ^V to the shell', () => {
    renderDialog()
    const handler = terminalInstances[0].keyHandler!
    const repeat = keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true, repeat: true, cancelable: true })

    expect(handler(repeat)).toBe(false)
    expect(repeat.defaultPrevented).toBe(true)

    const first = keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true, cancelable: true })
    expect(handler(first)).toBe(false)
    expect(first.defaultPrevented).toBe(false)
  })

  it('hands Cmd+V to the browser on macOS and keeps Ctrl+V for the shell', () => {
    setPlatform('MacIntel')
    renderDialog()
    const handler = terminalInstances[0].keyHandler!

    expect(handler(keyEvent({ key: 'v', code: 'KeyV', metaKey: true }))).toBe(false)
    expect(handler(keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true }))).toBe(true)
  })

  it('leaves Ctrl+V to xterm (^V for vim/readline) when image attachments are off', () => {
    renderDialog({ imageUploadEnabled: false })

    expect(terminalInstances[0].keyHandler!(keyEvent({ key: 'v', code: 'KeyV', ctrlKey: true }))).toBe(true)
  })
})
