import { useState, useCallback } from 'react'
import { cleanupIdleDatabases } from '../services/managedDatabaseService'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads
  t: (...args: any[]) => string
  reload: () => void
}

export function useDatabaseCleanup({ notify, t, reload }: Deps) {
  const [target, setTarget] = useState<string | null>(null) // repository name
  const [password, setPassword] = useState('')
  const [minDays, setMinDays] = useState(30)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const open = useCallback((repository: string) => {
    setTarget(repository)
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
    if (!target) return
    setLoading(true)
    setError('')
    try {
      const result = await cleanupIdleDatabases(target, password, minDays)
      if (result.success) {
        setTarget(null)
        setPassword('')
        notify(t('database.dbCleanupComplete', { count: result.deleted ?? 0 }), 'success')
        reload()
      } else {
        setError(result.error || 'Unknown error')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [target, password, minDays, notify, t, reload])

  return { target, password, setPassword, minDays, setMinDays, loading, error, open, close, confirm }
}
