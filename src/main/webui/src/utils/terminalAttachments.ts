/** True on macOS/iOS, where the native paste shortcut is Cmd+V instead of Ctrl+V. */
export function isMacPlatform(nav: Pick<Navigator, 'platform' | 'userAgent'> = navigator): boolean {
  const platform = (nav as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform ?? nav.platform ?? ''
  return /mac|iphone|ipad|ipod/i.test(platform) || (!platform && /Mac OS X/i.test(nav.userAgent ?? ''))
}

/** Human-readable paste shortcut for the current platform, for hints. */
export function pasteShortcutLabel(isMac: boolean): string {
  return isMac ? '⌘V' : 'Ctrl+V'
}

/**
 * True for the keyboard chord that triggers the browser's native paste: Cmd+V on macOS,
 * Ctrl+V (optionally with Shift) elsewhere. xterm.js would otherwise swallow Ctrl+V on
 * Linux/Windows and send ^V to the shell, so the paste event never fires.
 */
export function isNativePasteChord(
  ev: Pick<KeyboardEvent, 'type' | 'key' | 'code' | 'ctrlKey' | 'metaKey' | 'altKey'> & { repeat?: boolean },
  isMac: boolean,
): boolean {
  // Key auto-repeat would fire one paste (and one upload) per repeat while the chord is held.
  if (ev.type !== 'keydown' || ev.altKey || ev.repeat) return false
  const isV = ev.code === 'KeyV' || (ev.key ?? '').toLowerCase() === 'v'
  if (!isV) return false
  return isMac ? ev.metaKey && !ev.ctrlKey : ev.ctrlKey && !ev.metaKey
}

/** True when a paste payload carries real text, which should win over an embedded image preview. */
export function hasPlainText(transfer: DataTransfer | null | undefined): boolean {
  try {
    return (transfer?.getData('text/plain') ?? '').trim().length > 0
  } catch {
    return false
  }
}

/** Image MIME types accepted by the terminal paste/drop attachment flow. */
export const SUPPORTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] as const

export function isSupportedImage(file: Pick<File, 'type'>): boolean {
  return (SUPPORTED_IMAGE_TYPES as readonly string[]).includes(file.type)
}

/**
 * Collects image files from a paste or drop payload. Returns an empty array when the payload
 * has no files (plain text paste), so the caller can let the terminal handle it normally.
 */
export function extractImageFiles(transfer: DataTransfer | null | undefined): File[] {
  if (!transfer) return []
  const files: File[] = []
  const items = transfer.items
  if (items && items.length > 0) {
    for (let i = 0; i < items.length; i++) {
      const item = items[i]
      if (item.kind !== 'file' || !item.type.startsWith('image/')) continue
      const file = item.getAsFile()
      if (file) files.push(file)
    }
    return files
  }
  const list = transfer.files
  if (list) {
    for (let i = 0; i < list.length; i++) {
      if (list[i].type.startsWith('image/')) files.push(list[i])
    }
  }
  return files
}
