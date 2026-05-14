// Orchestrates container lifecycle actions: start, stop, remove (SSE), extend/cancel expiration, cancel DB deletion, bulk cleanup.
import { useState, useCallback } from 'react'
import {
  stopContainer,
  startContainer,
  removeContainer,
  cancelDatabaseDeletion,
  cancelExpiration,
  extendExpiration,
  updateContainerExpiration,
  lockContainers,
  unlockContainers,
  MemoryGuardError,
  type StartResult,
  type UpdateExpirationRequest,
} from '../services/containerService'
import { streamRemoveContainer, prepareRemoveContainer, streamRemoveContainerWithTicket } from '../services/sseService'
import { useSseOperation } from './useSseOperation'
import type { DockerContainer } from '../types'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error' | 'warning') => void
  confirm: (msg: string) => Promise<boolean>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads
  t: (...args: any[]) => string
  loadContainers: () => void
}

export interface BulkProgress {
  current: number
  total: number
  action: string
}

export function useContainerActions({ notify, confirm, t, loadContainers }: Deps) {
  const [stoppingId, setStoppingId] = useState<string | null>(null)
  const [bulkProgress, setBulkProgress] = useState<BulkProgress | null>(null)
  const [bulkOperatingIds, setBulkOperatingIds] = useState<Set<string>>(new Set())
  const removeSse = useSseOperation()

  const executeBulk = useCallback(async (
    items: DockerContainer[],
    action: string,
    fn: (item: DockerContainer) => Promise<boolean | StartResult>,
    opts?: { onMemoryGuard?: (e: MemoryGuardError, remaining: number) => void },
  ): Promise<{ succeeded: number; failed: number; errors: string[] }> => {
    let succeeded = 0
    let failed = 0
    const errors: string[] = []
    const ids = items.map(c => c.containerId)
    const pending = new Set(ids)
    setBulkOperatingIds(new Set(pending))
    setBulkProgress({ current: 0, total: items.length, action })
    await lockContainers(ids).catch(() => {})
    for (const item of items) {
      try {
        const result = await fn(item)
        const ok = typeof result === 'boolean' ? result : result.success
        if (ok) {
          succeeded++
        } else {
          failed++
          if (typeof result === 'object' && result.error) errors.push(result.error)
        }
      } catch (e) {
        if (e instanceof MemoryGuardError && opts?.onMemoryGuard) {
          opts.onMemoryGuard(e, items.length - succeeded - failed)
          failed += items.length - succeeded - failed
          setBulkOperatingIds(new Set())
          break
        }
        failed++
      }
      pending.delete(item.containerId)
      setBulkOperatingIds(new Set(pending))
      setBulkProgress({ current: succeeded + failed, total: items.length, action })
      await unlockContainers([item.containerId]).catch(() => {})
    }
    setBulkProgress(null)
    setBulkOperatingIds(new Set())
    await unlockContainers(ids).catch(() => {}) // safety net: clear any remaining locks
    return { succeeded, failed, errors }
  }, [])

  const handleStop = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.confirmStop', { name })))) return
    setStoppingId(id)
    await lockContainers([id]).catch(() => {})
    try {
      const ok = await stopContainer(id)
      notify(ok ? t('containers.containerStopped', { name }) : t('containers.failedToStop', { name }), ok ? 'success' : 'error')
    } catch {
      notify(t('containers.stopError', { name }), 'error')
    } finally {
      setStoppingId(null)
      await unlockContainers([id]).catch(() => {})
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers])

  const formatStartError = useCallback((error: string | undefined, detail: string | undefined, name: string) => {
    if (error === 'PORT_ALREADY_ALLOCATED' && detail) return t('containers.errors.portAlreadyAllocated', { port: detail })
    if (error === 'ALREADY_RUNNING') return t('containers.errors.alreadyRunning', { name })
    return t('containers.failedToStart', { name })
  }, [t])

  const handleStart = useCallback(async (id: string, name: string) => {
    if (!(await confirm(t('containers.confirmStart', { name })))) return
    await lockContainers([id]).catch(() => {})
    try {
      const result = await startContainer(id)
      if (result.success) {
        notify(t('containers.containerStarted', { name }), 'success')
      } else {
        notify(formatStartError(result.error, result.detail, name), 'error')
      }
    } catch (e) {
      if (e instanceof MemoryGuardError) {
        notify(t('containers.memoryGuardBlocked', { available: e.availableMb, threshold: e.thresholdMb }), 'error')
      } else {
        notify(t('containers.startError', { name }), 'error')
      }
    } finally {
      await unlockContainers([id]).catch(() => {})
    }
    loadContainers()
  }, [confirm, t, notify, loadContainers, formatStartError])

  const handleRemove = useCallback(async (id: string, name: string) => {
    await lockContainers([id]).catch(() => {})
    removeSse.start(
      (onEvent, onDone, onError) => streamRemoveContainer(id, onEvent, onDone, onError),
      () => {
        setTimeout(async () => {
          removeSse.reset()
          notify(t('containers.containerRemoved', { name }), 'success')
          await unlockContainers([id]).catch(() => {})
          loadContainers()
        }, 1500)
      },
      async () => { await unlockContainers([id]).catch(() => {}); loadContainers() },
    )
  }, [t, notify, loadContainers, removeSse])

  const handleRemoveWithDatabase = useCallback(async (
    id: string, name: string, repository: string, databaseName: string, password?: string
  ) => {
    try {
      const ticket = await prepareRemoveContainer({
        containerId: id,
        deleteDatabase: true,
        repository,
        databaseName,
        operationsPassword: password || null,
      })
      await lockContainers([id]).catch(() => {})
      removeSse.start(
        (onEvent, onDone, onError) => streamRemoveContainerWithTicket(ticket, onEvent, onDone, onError),
        () => {
          setTimeout(async () => {
            removeSse.reset()
            notify(t('containers.containerRemoved', { name }), 'success')
            await unlockContainers([id]).catch(() => {})
            loadContainers()
          }, 1500)
        },
        async () => { await unlockContainers([id]).catch(() => {}); loadContainers() },
      )
    } catch (e) {
      notify(e instanceof Error ? e.message : String(e), 'error')
    }
  }, [t, notify, loadContainers, removeSse])

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

  const handleUpdateExpiration = useCallback(async (
    request: UpdateExpirationRequest,
  ): Promise<{ success: boolean; error?: string }> => {
    try {
      const result = await updateContainerExpiration(request)
      if (result.success) {
        notify(t('containers.expirationUpdated'), 'success')
        loadContainers()
      }
      return result
    } catch {
      return { success: false, error: t('containers.failedToUpdateExpiration') }
    }
  }, [t, notify, loadContainers])

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
    const { succeeded: removed, failed } = await executeBulk(
      candidates, t('containers.bulk.progressRemoving'), c => removeContainer(c.containerId),
    )
    notify(t('containers.cleanup.result', { removed, failed }), failed > 0 ? 'warning' : 'success')
    loadContainers()
  }, [executeBulk, t, notify, loadContainers])

  const handleBulkStart = useCallback(async (containers: DockerContainer[]) => {
    const startable = containers.filter(c => !c.status.includes('Up'))
    if (startable.length === 0) return
    if (!(await confirm(t('containers.bulk.confirmStart', { count: startable.length })))) return
    const { succeeded: started, failed, errors } = await executeBulk(
      startable, t('containers.bulk.progressStarting'), c => startContainer(c.containerId),
      { onMemoryGuard: (e) => notify(t('containers.memoryGuardBlocked', { available: e.availableMb, threshold: e.thresholdMb }), 'error') },
    )
    if (started > 0 || failed > 0) {
      let msg = t('containers.bulk.startResult', { started, failed })
      const portErrors = errors.filter(e => e === 'PORT_ALREADY_ALLOCATED')
      if (portErrors.length > 0) {
        msg += ' ' + t('containers.errors.portConflictCount', { count: portErrors.length })
      }
      notify(msg, failed > 0 ? 'warning' : 'success')
    }
    loadContainers()
  }, [executeBulk, confirm, t, notify, loadContainers])

  const handleBulkStop = useCallback(async (containers: DockerContainer[]) => {
    const stoppable = containers.filter(c => c.status.includes('Up'))
    if (stoppable.length === 0) return
    if (!(await confirm(t('containers.bulk.confirmStop', { count: stoppable.length })))) return
    const { succeeded: stopped, failed } = await executeBulk(
      stoppable, t('containers.bulk.progressStopping'), c => stopContainer(c.containerId),
    )
    notify(t('containers.bulk.stopResult', { stopped, failed }), failed > 0 ? 'warning' : 'success')
    loadContainers()
  }, [executeBulk, confirm, t, notify, loadContainers])

  const handleBulkRemove = useCallback(async (containers: DockerContainer[]) => {
    if (containers.length === 0) return
    if (!(await confirm(t('containers.bulk.confirmRemove', { count: containers.length })))) return
    const { succeeded: removed, failed } = await executeBulk(
      containers, t('containers.bulk.progressRemoving'), c => removeContainer(c.containerId),
    )
    notify(t('containers.bulk.removeResult', { removed, failed }), failed > 0 ? 'warning' : 'success')
    loadContainers()
  }, [executeBulk, confirm, t, notify, loadContainers])

  return {
    stoppingId,
    bulkProgress,
    bulkOperatingIds,
    removeSse,
    handleStop,
    handleStart,
    handleRemove,
    handleRemoveWithDatabase,
    handleRemoveDialogClose,
    handleExtendExpiration,
    handleUpdateExpiration,
    handleCancelExpiration,
    handleCancelDbDeletion,
    handleCleanup,
    handleBulkStart,
    handleBulkStop,
    handleBulkRemove,
  }
}
