// Generic context menu / action menu state for table row interactions. Reusable across pages.
import { useState, useCallback } from 'react'

export function useActionMenu<T>() {
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null)
  const [contextMenuPos, setContextMenuPos] = useState<{ top: number; left: number } | null>(null)
  const [target, setTarget] = useState<T | null>(null)

  const openByAnchor = useCallback((el: HTMLElement, item: T) => {
    setContextMenuPos(null)
    setAnchorEl(el)
    setTarget(item)
  }, [])

  const openByPosition = useCallback((pos: { top: number; left: number }, item: T) => {
    setAnchorEl(null)
    setContextMenuPos(pos)
    setTarget(item)
  }, [])

  const close = useCallback(() => {
    setAnchorEl(null)
    setContextMenuPos(null)
    setTarget(null)
  }, [])

  const menuOpen = Boolean(anchorEl) || Boolean(contextMenuPos)

  return {
    anchorEl,
    contextMenuPos,
    target,
    menuOpen,
    openByAnchor,
    openByPosition,
    close,
  }
}
