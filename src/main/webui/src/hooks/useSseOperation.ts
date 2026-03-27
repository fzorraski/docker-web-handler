import { useState, useRef, useCallback, useEffect } from 'react'
import type { ContainerEvent } from '../services/sseService'

type StreamFn = (
  onEvent: (event: ContainerEvent) => void,
  onDone: (event: ContainerEvent) => void,
  onError: (message: string) => void,
) => () => void

interface UseSseOperationResult {
  events: ContainerEvent[]
  isRunning: boolean
  hasError: boolean
  isDone: boolean
  start: (streamFn: StreamFn, onSuccess?: (event: ContainerEvent) => void, onError?: () => void) => void
  reset: () => void
  cleanup: () => void
}

export function useSseOperation(): UseSseOperationResult {
  const [events, setEvents] = useState<ContainerEvent[]>([])
  const [isRunning, setIsRunning] = useState(false)
  const [hasError, setHasError] = useState(false)
  const [isDone, setIsDone] = useState(false)
  const cleanupRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    return () => {
      cleanupRef.current?.()
    }
  }, [])

  const cleanup = useCallback(() => {
    if (cleanupRef.current) {
      cleanupRef.current()
      cleanupRef.current = null
    }
  }, [])

  const reset = useCallback(() => {
    setEvents([])
    setIsRunning(false)
    setHasError(false)
    setIsDone(false)
  }, [])

  const start = useCallback((streamFn: StreamFn, onSuccess?: (event: ContainerEvent) => void, onError?: () => void) => {
    setEvents([])
    setIsRunning(true)
    setHasError(false)
    setIsDone(false)

    cleanupRef.current = streamFn(
      (event) => setEvents((prev) => [...prev, event]),
      (event) => {
        setIsDone(true)
        try {
          onSuccess?.(event)
        } catch (e) {
          console.error('SSE onSuccess callback failed:', e)
        }
      },
      () => {
        setIsRunning(false)
        setHasError(true)
        onError?.()
      },
    )
  }, [])

  return { events, isRunning, hasError, isDone, start, reset, cleanup }
}
