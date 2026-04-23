/**
 * Copy text to clipboard with fallback for non-secure contexts (HTTP).
 * navigator.clipboard.writeText requires HTTPS or localhost;
 * when unavailable, falls back to a temporary textarea + execCommand('copy').
 */
export async function copyToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch {
      // Clipboard API failed (e.g. non-secure context), try fallback
    }
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'
  // Append inside the active dialog/modal to stay within its focus trap
  const container = document.activeElement?.closest('[role="presentation"]') ?? document.body
  container.appendChild(textarea)
  textarea.focus()
  textarea.select()
  try {
    document.execCommand('copy')
  } finally {
    container.removeChild(textarea)
  }
}
