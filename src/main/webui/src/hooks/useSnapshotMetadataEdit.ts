// Manages inline snapshot metadata editing with password-gated save.
import { useState, useCallback } from 'react'
import { updateSnapshotMetadata } from '../services/snapshotService'
import type { DatabaseSnapshot } from '../types'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  setSnapshots: React.Dispatch<React.SetStateAction<DatabaseSnapshot[]>>
}

export function useSnapshotMetadataEdit({ notify, setSnapshots }: Deps) {
  const [editingId, setEditingId] = useState<string | null>(null)
  const [label, setLabel] = useState('')
  const [description, setDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordOpen, setPasswordOpen] = useState(false)

  const startEdit = useCallback((snap: DatabaseSnapshot) => {
    setEditingId(snap.id)
    setLabel(snap.label || '')
    setDescription(snap.description || '')
  }, [])

  const cancelEdit = useCallback(() => {
    setEditingId(null)
    setLabel('')
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
    const result = await updateSnapshotMetadata(editingId, label, usePw, description)
    setSaving(false)
    if (result.success) {
      setSnapshots(prev => prev.map(s => s.id === editingId
        ? { ...s, label, description: description || undefined } : s))
      setEditingId(null)
      setPassword('')
    } else {
      if (result.error?.includes('password') || result.error?.includes('Password')) {
        setPassword('')
        setPasswordOpen(true)
      }
      notify(result.error || 'Failed to update.', 'error')
    }
  }, [editingId, label, description, password, notify, setSnapshots])

  return {
    editingId, label, setLabel, description, setDescription,
    saving, passwordOpen, setPasswordOpen,
    startEdit, cancelEdit, saveEdit,
  }
}
