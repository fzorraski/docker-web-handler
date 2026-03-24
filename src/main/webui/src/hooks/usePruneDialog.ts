// Manages image prune dialog: mode selection, password input, and SSE execution flow.
import { useState, useCallback } from 'react'
import { preparePruneImages, streamPruneImages } from '../services/sseService'
import { useSseOperation } from './useSseOperation'

type PruneMode = 'byDate' | 'all'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads
  t: (...args: any[]) => string
  loadImages: () => void
}

export function usePruneDialog({ notify, t, loadImages }: Deps) {
  const pruneSse = useSseOperation()
  const [mode, setMode] = useState<PruneMode | null>(null)
  const [password, setPassword] = useState('')
  const [minDays, setMinDays] = useState(5)
  const [preparing, setPreparing] = useState(false)
  const [error, setError] = useState('')

  const open = useCallback((pruneMode: PruneMode) => {
    setMode(pruneMode)
    setPassword('')
    setMinDays(5)
    setError('')
  }, [])

  const close = useCallback(() => {
    if (!preparing) {
      setMode(null)
      setPassword('')
      setError('')
    }
  }, [preparing])

  const confirm = useCallback(async () => {
    setPreparing(true)
    setError('')
    try {
      const days = mode === 'all' ? 0 : minDays
      const ticket = await preparePruneImages({ password, minDays: days })
      setMode(null)
      setPassword('')

      pruneSse.start(
        (onEvent, onDone, onError) => streamPruneImages(ticket, onEvent, onDone, onError),
        () => {
          setTimeout(() => {
            pruneSse.reset()
            notify(t('images.pruneComplete'), 'success')
            loadImages()
          }, 1500)
        },
        () => loadImages(),
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPreparing(false)
    }
  }, [mode, password, minDays, pruneSse, notify, t, loadImages])

  const closeProgress = useCallback(() => {
    pruneSse.cleanup()
    pruneSse.reset()
    loadImages()
  }, [pruneSse, loadImages])

  return {
    pruneSse,
    mode, password, setPassword, minDays, setMinDays, preparing, error,
    open, close, confirm, closeProgress,
  }
}
