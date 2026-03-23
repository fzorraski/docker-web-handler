// Manages inline dump metadata editing with password-gated save.
import { useState, useCallback } from 'react'
import { updateDumpMetadata } from '../services/dumpService'
import type { DatabaseDump } from '../types'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  setDumps: React.Dispatch<React.SetStateAction<DatabaseDump[]>>
}

export function useDumpMetadataEdit({ notify, setDumps }: Deps) {
  const [editingId, setEditingId] = useState<string | null>(null)
  const [version, setVersion] = useState('')
  const [database, setDatabase] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordOpen, setPasswordOpen] = useState(false)

  const startEdit = useCallback((dump: DatabaseDump) => {
    setEditingId(dump.id)
    setVersion(dump.version || '')
    setDatabase(dump.databaseName || '')
    setDescription(dump.description || '')
  }, [])

  const cancelEdit = useCallback(() => {
    setEditingId(null)
    setVersion('')
    setDatabase('')
    setDescription('')
  }, [])

  const saveEdit = useCallback(async (pw?: string) => {
    if (!editingId) return
    const usePw = pw ?? password
    if (!usePw) {
      setPasswordOpen(true)
      return
    }
    setSaving(true)
    const result = await updateDumpMetadata(editingId, version, database, usePw, description)
    setSaving(false)
    if (result.success) {
      setDumps(prev => prev.map(d => d.id === editingId
        ? { ...d, version, databaseName: database, description: description || undefined } : d))
      setEditingId(null)
      setPassword('')
    } else {
      if (result.error?.includes('password') || result.error?.includes('Password')) {
        setPassword('')
        setPasswordOpen(true)
      }
      notify(result.error || 'Failed to update.', 'error')
    }
  }, [editingId, version, database, description, password, notify, setDumps])

  return {
    editingId, version, setVersion, database, setDatabase, description, setDescription,
    saving, passwordOpen, setPasswordOpen,
    startEdit, cancelEdit, saveEdit,
  }
}
