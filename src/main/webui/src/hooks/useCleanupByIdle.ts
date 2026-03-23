// Manages the cleanup-by-idle-time dialog state and execution for dumps and snapshots.
import { useState, useCallback } from 'react'
import { cleanupIdleDumps } from '../services/dumpService'
import { cleanupIdleSnapshots } from '../services/snapshotService'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  t: (key: string, options?: Record<string, unknown>) => string
  loadDumps: () => void
  loadSnapshots: () => void
}

export function useCleanupByIdle({ notify, t, loadDumps, loadSnapshots }: Deps) {
  const [target, setTarget] = useState<'dump' | 'snapshot' | null>(null)
  const [password, setPassword] = useState('')
  const [minDays, setMinDays] = useState(30)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const open = useCallback((cleanupTarget: 'dump' | 'snapshot') => {
    setTarget(cleanupTarget)
    setPassword('')
    setMinDays(30)
    setError('')
  }, [])

  const close = useCallback(() => {
    if (!loading) {
      setTarget(null)
      setPassword('')
      setError('')
    }
  }, [loading])

  const confirm = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const result = target === 'dump'
        ? await cleanupIdleDumps(password, minDays)
        : await cleanupIdleSnapshots(password, minDays)

      if (result.success) {
        setTarget(null)
        setPassword('')
        notify(t('database.cleanupComplete', { count: result.deleted ?? 0 }), 'success')
        if (target === 'dump') loadDumps()
        else loadSnapshots()
      } else {
        setError(result.error || 'Unknown error')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [target, password, minDays, notify, t, loadDumps, loadSnapshots])

  return { target, password, setPassword, minDays, setMinDays, loading, error, open, close, confirm }
}
