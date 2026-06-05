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

  // Migration / Upgrade
  const [migrationRepo, setMigrationRepo] = useState('')
  const [migrationDb, setMigrationDb] = useState('')
  const [migrationOpen, setMigrationOpen] = useState(false)
  const [migrationContainerId, setMigrationContainerId] = useState('')
  const [migrationContainerName, setMigrationContainerName] = useState('')
  const [migrationContainerImage, setMigrationContainerImage] = useState('')
  const [migrationUpgradeEnabled, setMigrationUpgradeEnabled] = useState(false)

  // Schedule
  const [scheduleContainerId, setScheduleContainerId] = useState('')
  const [scheduleContainerName, setScheduleContainerName] = useState('')
  const [scheduleExpiresAt, setScheduleExpiresAt] = useState<string | undefined>(undefined)
  const [scheduleProtected, setScheduleProtected] = useState(false)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [scheduleInitialTab, setScheduleInitialTab] = useState(0)

  // Edit Expiration
  const [editExpirationOpen, setEditExpirationOpen] = useState(false)
  const [editExpirationContainer, setEditExpirationContainer] = useState<DockerContainer | null>(null)

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
    setMigrationContainerId(c.containerId)
    setMigrationContainerName(c.names)
    setMigrationContainerImage(c.image)
    setMigrationUpgradeEnabled(c.upgradeEnabled ?? false)
    setMigrationOpen(true)
  }, [])

  const closeMigration = useCallback(() => setMigrationOpen(false), [])

  const openSchedule = useCallback((c: DockerContainer, initialTab = 0) => {
    setScheduleContainerId(c.containerId)
    setScheduleContainerName(c.names)
    setScheduleExpiresAt(c.expiresAt)
    setScheduleProtected(!!c.protectedFlag)
    setScheduleInitialTab(initialTab)
    setScheduleOpen(true)
  }, [])

  const closeSchedule = useCallback(() => setScheduleOpen(false), [])

  const openEditExpiration = useCallback((c: DockerContainer) => {
    setEditExpirationContainer(c)
    setEditExpirationOpen(true)
  }, [])

  const closeEditExpiration = useCallback(() => {
    setEditExpirationOpen(false)
    setEditExpirationContainer(null)
  }, [])

  return {
    snapshot: { open: snapshotOpen, repo: snapshotRepo, db: snapshotDb, containerName: snapshotContainerName },
    openSnapshot, closeSnapshot,

    logs: { containerId: logsContainerId, containerName: logsContainerName },
    openLogs, closeLogs,

    stats: { containerId: statsContainerId, containerName: statsContainerName },
    openStats, closeStats,

    migration: { open: migrationOpen, repo: migrationRepo, db: migrationDb, containerId: migrationContainerId, containerName: migrationContainerName, image: migrationContainerImage, upgradeEnabled: migrationUpgradeEnabled },
    openMigration, closeMigration,

    schedule: { open: scheduleOpen, containerId: scheduleContainerId, containerName: scheduleContainerName, expiresAt: scheduleExpiresAt, protectedFlag: scheduleProtected, initialTab: scheduleInitialTab },
    openSchedule, closeSchedule,

    editExpiration: { open: editExpirationOpen, container: editExpirationContainer },
    openEditExpiration, closeEditExpiration,

    cleanup: { open: cleanupDialogOpen, minDays: cleanupMinDays, running: cleanupRunning },
    openCleanup: useCallback(() => setCleanupDialogOpen(true), []),
    closeCleanup: useCallback(() => setCleanupDialogOpen(false), []),
    setCleanupMinDays,
    setCleanupRunning,
  }
}
