// Manages open/close state and data for all container page dialogs (logs, stats, snapshot, migration, schedule, cleanup).
import { useState, useCallback } from 'react'
import type { DockerContainer } from '../types'

export function useContainerDialogs() {
  // Snapshot
  const [snapshotOpen, setSnapshotOpen] = useState(false)
  const [snapshotRepo, setSnapshotRepo] = useState<string | undefined>(undefined)
  const [snapshotDb, setSnapshotDb] = useState<string | undefined>(undefined)
  const [snapshotContainerName, setSnapshotContainerName] = useState<string | undefined>(undefined)

  // Logs
  const [logsContainerId, setLogsContainerId] = useState<string | null>(null)
  const [logsContainerName, setLogsContainerName] = useState('')

  // Stats
  const [statsContainerId, setStatsContainerId] = useState<string | null>(null)
  const [statsContainerName, setStatsContainerName] = useState('')

  // Migration
  const [migrationRepo, setMigrationRepo] = useState('')
  const [migrationDb, setMigrationDb] = useState('')
  const [migrationOpen, setMigrationOpen] = useState(false)

  // Schedule
  const [scheduleContainerId, setScheduleContainerId] = useState('')
  const [scheduleContainerName, setScheduleContainerName] = useState('')
  const [scheduleExpiresAt, setScheduleExpiresAt] = useState<string | undefined>(undefined)
  const [scheduleOpen, setScheduleOpen] = useState(false)

  // Cleanup
  const [cleanupDialogOpen, setCleanupDialogOpen] = useState(false)
  const [cleanupMinDays, setCleanupMinDays] = useState(7)
  const [cleanupRunning, setCleanupRunning] = useState(false)

  const openSnapshot = useCallback((c: DockerContainer) => {
    setSnapshotRepo(c.repository ?? undefined)
    setSnapshotDb(c.databaseName ?? undefined)
    setSnapshotContainerName(c.names)
    setSnapshotOpen(true)
  }, [])

  const closeSnapshot = useCallback(() => setSnapshotOpen(false), [])

  const openLogs = useCallback((c: DockerContainer) => {
    setLogsContainerId(c.containerId)
    setLogsContainerName(c.names)
  }, [])

  const closeLogs = useCallback(() => {
    setLogsContainerId(null)
    setLogsContainerName('')
  }, [])

  const openStats = useCallback((c: DockerContainer) => {
    setStatsContainerId(c.containerId)
    setStatsContainerName(c.names)
  }, [])

  const closeStats = useCallback(() => {
    setStatsContainerId(null)
    setStatsContainerName('')
  }, [])

  const openMigration = useCallback((c: DockerContainer) => {
    setMigrationRepo(c.repository ?? '')
    setMigrationDb(c.databaseName ?? '')
    setMigrationOpen(true)
  }, [])

  const closeMigration = useCallback(() => setMigrationOpen(false), [])

  const openSchedule = useCallback((c: DockerContainer) => {
    setScheduleContainerId(c.containerId)
    setScheduleContainerName(c.names)
    setScheduleExpiresAt(c.expiresAt)
    setScheduleOpen(true)
  }, [])

  const closeSchedule = useCallback(() => setScheduleOpen(false), [])

  return {
    snapshot: { open: snapshotOpen, repo: snapshotRepo, db: snapshotDb, containerName: snapshotContainerName },
    openSnapshot, closeSnapshot,

    logs: { containerId: logsContainerId, containerName: logsContainerName },
    openLogs, closeLogs,

    stats: { containerId: statsContainerId, containerName: statsContainerName },
    openStats, closeStats,

    migration: { open: migrationOpen, repo: migrationRepo, db: migrationDb },
    openMigration, closeMigration,

    schedule: { open: scheduleOpen, containerId: scheduleContainerId, containerName: scheduleContainerName, expiresAt: scheduleExpiresAt },
    openSchedule, closeSchedule,

    cleanup: { open: cleanupDialogOpen, minDays: cleanupMinDays, running: cleanupRunning },
    openCleanup: useCallback(() => setCleanupDialogOpen(true), []),
    closeCleanup: useCallback(() => setCleanupDialogOpen(false), []),
    setCleanupMinDays,
    setCleanupRunning,
  }
}
