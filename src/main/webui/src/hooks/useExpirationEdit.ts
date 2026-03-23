// Manages the edit-expiration dialog open/close state and handler binding.
import { useState, useCallback } from 'react'

type ExpirationHandler = (expiresAt: string | null, password: string) => Promise<{ success: boolean; error?: string }>

export function useExpirationEdit() {
  const [open, setOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [current, setCurrent] = useState<string | null>(null)
  const [handler, setHandler] = useState<ExpirationHandler | null>(null)

  const openEdit = useCallback((editTitle: string, currentValue: string | null, editHandler: ExpirationHandler) => {
    setTitle(editTitle)
    setCurrent(currentValue)
    setHandler(() => editHandler)
    setOpen(true)
  }, [])

  const closeEdit = useCallback(() => {
    setOpen(false)
    setHandler(null)
  }, [])

  return { open, title, current, handler, openEdit, closeEdit }
}
