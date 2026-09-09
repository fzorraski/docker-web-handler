import { describe, it, expect } from 'vitest'
import {
  extractImageFiles, formatImagePathForPrompt, isMacPlatform, isNativePasteChord, isSupportedImage, pasteShortcutLabel, textShouldWin,
} from '../utils/terminalAttachments'

describe('formatImagePathForPrompt', () => {
  it('replaces the placeholder and appends a separating space', () => {
    expect(formatImagePathForPrompt('"{path}"', '/tmp/a.png')).toBe('"/tmp/a.png" ')
    expect(formatImagePathForPrompt('[image: {path}] {path}', '/tmp/a.png')).toBe('[image: /tmp/a.png] /tmp/a.png ')
  })

  it('types nothing when the template is explicitly empty', () => {
    expect(formatImagePathForPrompt('', '/tmp/a.png')).toBe('')
    expect(formatImagePathForPrompt('   ', '/tmp/a.png')).toBe('')
  })

  it('falls back to the bare path when the template is missing or has no placeholder', () => {
    expect(formatImagePathForPrompt(undefined, '/tmp/a.png')).toBe('/tmp/a.png ')
    expect(formatImagePathForPrompt('oops', '/tmp/a.png')).toBe('/tmp/a.png ')
  })
})

function fakeTransfer(items: Array<{ kind: string; type: string; file?: File | null }>, files: File[] = []): DataTransfer {
  return {
    items: items.map((i) => ({ kind: i.kind, type: i.type, getAsFile: () => i.file ?? null })),
    files,
  } as unknown as DataTransfer
}

describe('extractImageFiles', () => {
  it('returns empty for null or text-only payloads', () => {
    expect(extractImageFiles(null)).toEqual([])
    expect(extractImageFiles(undefined)).toEqual([])
    expect(extractImageFiles(fakeTransfer([{ kind: 'string', type: 'text/plain' }]))).toEqual([])
  })

  it('collects image files from clipboard items and skips other kinds', () => {
    const png = new File([new Uint8Array([1])], 'image.png', { type: 'image/png' })
    const txt = new File(['x'], 'notes.txt', { type: 'text/plain' })
    const result = extractImageFiles(fakeTransfer([
      { kind: 'string', type: 'text/html' },
      { kind: 'file', type: 'image/png', file: png },
      { kind: 'file', type: 'text/plain', file: txt },
    ]))
    expect(result).toEqual([png])
  })

  it('falls back to the files list when items are unavailable', () => {
    const jpg = new File([new Uint8Array([1])], 'photo.jpg', { type: 'image/jpeg' })
    const pdf = new File([new Uint8Array([1])], 'doc.pdf', { type: 'application/pdf' })
    const transfer = { items: undefined, files: [jpg, pdf] } as unknown as DataTransfer
    expect(extractImageFiles(transfer)).toEqual([jpg])
  })
})

describe('isSupportedImage', () => {
  it('accepts png, jpeg, gif and webp', () => {
    for (const type of ['image/png', 'image/jpeg', 'image/gif', 'image/webp']) {
      expect(isSupportedImage({ type })).toBe(true)
    }
  })

  it('rejects other image types but leaves an unknown (empty) type to the server', () => {
    expect(isSupportedImage({ type: 'image/svg+xml' })).toBe(false)
    expect(isSupportedImage({ type: 'image/bmp' })).toBe(false)
    expect(isSupportedImage({ type: '' })).toBe(true)
  })

  it('extracts files without a MIME type so the server can sniff them', () => {
    const noExt = new File([new Uint8Array([1])], 'screenshot', { type: '' })
    const transfer = { items: undefined, files: [noExt] } as unknown as DataTransfer
    expect(extractImageFiles(transfer)).toEqual([noExt])
  })
})

function key(overrides: Partial<KeyboardEvent> & { key: string }): Pick<KeyboardEvent, 'type' | 'key' | 'code' | 'ctrlKey' | 'metaKey' | 'altKey'> {
  return { type: 'keydown', code: '', ctrlKey: false, metaKey: false, altKey: false, ...overrides }
}

describe('isNativePasteChord', () => {
  it('matches Ctrl+V and Ctrl+Shift+V on Linux/Windows', () => {
    expect(isNativePasteChord(key({ key: 'v', ctrlKey: true }), false)).toBe(true)
    expect(isNativePasteChord(key({ key: 'V', ctrlKey: true, shiftKey: true }), false)).toBe(true)
    expect(isNativePasteChord(key({ key: 'м', code: 'KeyV', ctrlKey: true }), false)).toBe(true)
  })

  it('matches Cmd+V on macOS but not Ctrl+V', () => {
    expect(isNativePasteChord(key({ key: 'v', metaKey: true }), true)).toBe(true)
    expect(isNativePasteChord(key({ key: 'v', ctrlKey: true }), true)).toBe(false)
  })

  it('still recognizes the chord on auto-repeat; the dialog decides what to do with repeats', () => {
    expect(isNativePasteChord(new KeyboardEvent('keydown', { key: 'v', code: 'KeyV', ctrlKey: true, repeat: true }), false)).toBe(true)
  })

  it('does not match other keys, Alt chords, Cmd+V on Linux, or keyup', () => {
    expect(isNativePasteChord(key({ key: 'c', ctrlKey: true }), false)).toBe(false)
    expect(isNativePasteChord(key({ key: 'v', ctrlKey: true, altKey: true }), false)).toBe(false)
    expect(isNativePasteChord(key({ key: 'v', metaKey: true }), false)).toBe(false)
    expect(isNativePasteChord(key({ key: 'v', ctrlKey: true, type: 'keyup' }), false)).toBe(false)
    expect(isNativePasteChord(key({ key: 'v' }), false)).toBe(false)
  })
})

describe('isMacPlatform', () => {
  it('detects mac and iOS from platform, and falls back to the user agent', () => {
    expect(isMacPlatform({ platform: 'MacIntel', userAgent: '' })).toBe(true)
    expect(isMacPlatform({ platform: 'iPhone', userAgent: '' })).toBe(true)
    expect(isMacPlatform({ platform: 'Linux x86_64', userAgent: '' })).toBe(false)
    expect(isMacPlatform({ platform: 'Win32', userAgent: '' })).toBe(false)
    expect(isMacPlatform({ platform: '', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)' })).toBe(true)
  })

  it('labels the shortcut per platform', () => {
    expect(pasteShortcutLabel(true)).toBe('⌘V')
    expect(pasteShortcutLabel(false)).toBe('Ctrl+V')
  })
})

describe('textShouldWin', () => {
  const png = new File([new Uint8Array([1])], 'shot.png', { type: 'image/png' })
  const transfer = (plain: string, html = '') =>
    ({ getData: (t: string) => (t === 'text/plain' ? plain : t === 'text/html' ? html : '') } as unknown as DataTransfer)

  it('lets the image win when there is no text', () => {
    expect(textShouldWin(transfer(''), [png])).toBe(false)
    expect(textShouldWin(transfer('   '), [png])).toBe(false)
    expect(textShouldWin(null, [png])).toBe(false)
  })

  it('lets text win for spreadsheet and browser copies that ship html plus a preview image', () => {
    expect(textShouldWin(transfer('A1\tB1', '<table><tr><td>A1</td></tr></table>'), [png])).toBe(true)
    expect(textShouldWin(transfer('some words'), [png])).toBe(true)
  })

  it('lets the image win for file-manager copies whose text is only the file name or URI', () => {
    expect(textShouldWin(transfer('shot.png'), [png])).toBe(false)
    expect(textShouldWin(transfer('file:///home/u/shot.png'), [png])).toBe(false)
    const spaced = new File([new Uint8Array([1])], 'my pic.png', { type: 'image/png' })
    expect(textShouldWin(transfer('file:///home/u/my%20pic.png'), [spaced])).toBe(false)
    const accented = new File([new Uint8Array([1])], 'imagem-ção.png', { type: 'image/png' })
    expect(textShouldWin(transfer(encodeURI('file:///home/u/imagem-ção.png')), [accented])).toBe(false)
    expect(textShouldWin(transfer('/home/u/shot.png\n/home/u/other.png'), [png])).toBe(true)
  })

  it('is false when the clipboard cannot be read', () => {
    expect(textShouldWin({ getData: () => { throw new Error('denied') } } as unknown as DataTransfer, [png])).toBe(false)
  })
})
