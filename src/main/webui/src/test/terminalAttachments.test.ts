import { describe, it, expect } from 'vitest'
import {
  extractImageFiles, hasPlainText, isMacPlatform, isNativePasteChord, isSupportedImage, pasteShortcutLabel,
} from '../utils/terminalAttachments'

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

  it('rejects other image types', () => {
    expect(isSupportedImage({ type: 'image/svg+xml' })).toBe(false)
    expect(isSupportedImage({ type: 'image/bmp' })).toBe(false)
    expect(isSupportedImage({ type: '' })).toBe(false)
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

describe('hasPlainText', () => {
  it('is true only for non-blank text/plain payloads', () => {
    expect(hasPlainText({ getData: () => 'A1\tB1' } as unknown as DataTransfer)).toBe(true)
    expect(hasPlainText({ getData: () => '   ' } as unknown as DataTransfer)).toBe(false)
    expect(hasPlainText({ getData: () => '' } as unknown as DataTransfer)).toBe(false)
    expect(hasPlainText(null)).toBe(false)
    expect(hasPlainText({ getData: () => { throw new Error('denied') } } as unknown as DataTransfer)).toBe(false)
  })
})
