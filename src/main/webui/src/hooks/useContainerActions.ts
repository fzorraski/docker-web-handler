// Orchestrates container lifecycle actions: start, stop, remove (SSE), extend/cancel expiration, cancel DB deletion, bulk cleanup.
import { useState, useCallback } from 'react'
import {
  stopContainer,
  startContainer,
  removeContainer,
  cancelDatabaseDeletion,
  cancelExpiration,
  extendExpiration,
  MemoryGuardError,
} from '../services/containerService'
import { streamRemoveContainer } from '../services/sseService'
import { useSseOperation } from './useSseOperation'
import type { DockerContainer } from '../types'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error' | 'warning') => void
  confirm: (msg: string) => Promise<boolean>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads
  t: (...args: any[]) => string
  loadContainers: () => void
}

export function useContainerActions({ notify, confirm, t, loadContainers }: Deps) {
  const [stoppingId, setStoppingId] = useState<string | null>(null)
  const removeSse = useSseOperation()

  const handleStop = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.confirmStop', { name })))) return
    setStoppingId(id)
    try {
      const ok = await stopContainer(id)
      notify(ok ? t('containers.containerStopped') : t('containers.failedToStop'), ok ? 'success' : 'error')
    } catch {
      notify(t('containers.stopError'), 'error')
    } finally {
      setStoppingId(null)
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers])

  const handleStart = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.confirmStart', { name })))) return
    try {
      const ok = await startContainer(id)
      notify(ok ? t('containers.containerStarted') : t('containers.failedToStart'), ok ? 'success' : 'error')
    } catch (e) {
      if (e instanceof MemoryGuardError) {
        notify(t('containers.memoryGuardBlocked', { available: e.availableMb, threshold: e.thresholdMb }), 'error')
      } else {
        notify(t('containers.startError'), 'error')
      }
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers])

  const handleRemove = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.confirmRemove', { name })))) return
    removeSse.start(
      (onEvent, onDone, onError) => streamRemoveContainer(id, onEvent, onDone, onError),
      () => {
        setTimeout(() => {
          removeSse.reset()
          notify(t('containers.containerRemoved'), 'success')
          loadContainers()
        }, 1500)
      },
      () => loadContainers(),
    )
  }, [confirm, t, notify, loadContainers, removeSse])

  const handleRemoveDialogClose = useCallback(() => {
    removeSse.cleanup()
    removeSse.reset()
    loadContainers()
  }, [removeSse, loadContainers])

  const handleExtendExpiration = useCallback(async (id: string) => {
    try {
      const ok = await extendExpiration(id, 10)
      notify(ok ? t('containers.expirationExtended') : t('containers.failedToExtend'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }, [t, notify, loadContainers])

  const handleCancelExpiration = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.cancelExpiration', { name })))) return
    try {
      const ok = await cancelExpiration(id)
      notify(ok ? t('containers.expirationCancelled') : t('containers.failedToCancelExpiration'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers])

  const handleCancelDbDeletion = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.cancelDbDeletion', { name })))) return
    try {
      const ok = await cancelDatabaseDeletion(id)
      notify(ok ? t('containers.dbDeletionCancelled') : t('containers.failedToCancelDbDeletion'), ok ? 'success' : 'error')
    } catch {
      notify(t('common.unexpectedError'), 'error')
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers])

  const handleCleanup = useCallback(async (candidates: DockerContainer[]) => {
    if (candidates.length === 0) return
    let removed = 0
    let failed = 0
    for (const c of candidates) {
      try {
        const ok = await removeContainer(c.containerId)
        if (ok) removed++; else failed++
      } catch {
        failed++
      }
    }
    notify(
      t('containers.cleanup.result', { removed, failed }),
      failed > 0 ? 'warning' : 'success',
    )
    loadContainers()
  }, [t, notify, loadContainers])

  return {
    stoppingId,
    removeSse,
    handleStop,
    handleStart,
    handleRemove,
    handleRemoveDialogClose,
    handleExtendExpiration,
    handleCancelExpiration,
    handleCancelDbDeletion,
    handleCleanup,
  }
}
