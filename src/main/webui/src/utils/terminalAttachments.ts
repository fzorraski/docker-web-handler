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
  ev: Pick<KeyboardEvent, 'type' | 'key' | 'code' | 'ctrlKey' | 'metaKey' | 'altKey'>,
  isMac: boolean,
): boolean {
  if (ev.type !== 'keydown' || ev.altKey) return false
  const isV = ev.code === 'KeyV' || (ev.key ?? '').toLowerCase() === 'v'
  if (!isV) return false
  return isMac ? ev.metaKey && !ev.ctrlKey : ev.ctrlKey && !ev.metaKey
}

/**
 * Decides whether a paste that carries both text and image files meant the text.
 * Spreadsheet and browser copies ship text/html plus a rendered preview image: the text
 * wins. File-manager copies of an image ship the file plus its name or file:// URI as
 * text: the file wins. Any other non-blank text wins.
 */
export function textShouldWin(transfer: DataTransfer | null | undefined, files: File[]): boolean {
  let text = ''
  let html = ''
  try {
    text = (transfer?.getData('text/plain') ?? '').trim()
    html = (transfer?.getData('text/html') ?? '').trim()
  } catch {
    return false
  }
  if (!text) return false
  if (html) return true
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const decode = (s: string) => { try { return decodeURIComponent(s) } catch { return s } }
  // file:// URIs are percent-encoded ("my%20pic.png"), so compare the decoded form as well
  const refersToFile = (line: string) => [line, decode(line)]
    .some((v) => files.some((f) => v === f.name || v.endsWith('/' + f.name)))
  return !lines.every(refersToFile)
}

/**
 * Text to type into the prompt for an uploaded image: the admin template with {path}
 * replaced, or the bare path when no template is configured. An explicitly empty template
 * means "type nothing" and yields ''. A trailing space separates the text from whatever
 * the user types next.
 */
export function formatImagePathForPrompt(template: string | undefined, path: string): string {
  if (template !== undefined && template.trim() === '') return ''
  const tpl = template && template.includes('{path}') ? template : '{path}'
  return tpl.split('{path}').join(path) + ' '
}

/** Image MIME types accepted by the terminal paste/drop attachment flow. */
export const SUPPORTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] as const

/** Browsers derive File.type from the extension; an empty type is left to the server's magic-byte check. */
export function isSupportedImage(file: Pick<File, 'type'>): boolean {
  return file.type === '' || (SUPPORTED_IMAGE_TYPES as readonly string[]).includes(file.type)
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
      if (item.kind !== 'file' || !(item.type === '' || item.type.startsWith('image/'))) continue
      const file = item.getAsFile()
      if (file) files.push(file)
    }
    return files
  }
  const list = transfer.files
  if (list) {
    for (let i = 0; i < list.length; i++) {
      if (list[i].type === '' || list[i].type.startsWith('image/')) files.push(list[i])
    }
  }
  return files
}
