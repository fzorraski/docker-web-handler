export interface DockerContainer {
  containerId: string
  image: string
  command: string
  created: string
  status: string
  ports: string
  names: string
  expiresAt?: string
  portPaths?: Record<string, string>
  databaseName?: string
  deleteDatabaseOnExpiration?: boolean
  repository?: string
  ipAddress?: string
  createdBy?: string
  tenantId?: string
  sharedWithTenants?: string[]
  upgradeEnabled?: boolean
  protectedFlag?: boolean
}

export interface DockerImage {
  repository: string
  tag: string
  imageId: string
  created: string
  size: string
  inUse: boolean
  containerCount: number
  parentId: string
  childIds: string[]
  lastUsedAt: string | null
}

export interface DatabaseConflict {
  scheduledForDeletionBy?: string | null
  inUseByContainers: string[]
  expiresAt?: string | null
  protectedFlag?: boolean
}

export interface ApiResponse {
  state: number
  message?: string
  tags?: string[]
  databases?: string[]
  dbEnvVar?: string
}

export interface DatabaseDump {
  id: string
  originalFilename: string
  storedFilename: string
  databaseName?: string
  version?: string
  md5Hash?: string
  uploadedAt: string
  expiresAt?: string
  fileSize: number
  format: 'SQL' | 'CUSTOM' | 'COMPRESSED'
  description?: string
  lastUsedAt?: string
  createdBy?: string
  tenantId?: string
  sharedWithTenants?: string[]
}

export interface ContainerSchedule {
  id: string
  name: string
  action: 'START' | 'STOP' | 'CREATE' | 'REMOVE'
  scheduleType: 'ONE_TIME' | 'RECURRING'
  enabled: boolean
  createdAt: string
  cronExpression?: string
  scheduledAt?: string
  containerId?: string
  containerName?: string
  createConfig?: RunContainerConfig
  nextExecutionAt?: string
  lastExecutedAt?: string
  lastExecutionStatus?: string
  lastExecutionMessage?: string
  createdBy?: string
  tenantId?: string
  sharedWithTenants?: string[]
}

export interface RunContainerConfig {
  repository: string
  tag: string
  containerName?: string
  envVars?: string[]
  expiresAt?: string
  memoryMb?: number
  databaseName?: string
  deleteDatabaseOnExpiration?: boolean
  dumpId?: string
  snapshotId?: string
  createDatabase?: boolean
  selectedOptionalScripts?: string[]
  operationsPassword?: string
  migrationMode?: string
  migrationSql?: string
  migrationSourceVersion?: string
  migrationTargetVersion?: string
}

export interface DatabaseSnapshot {
  id: string
  storedFilename: string
  repository: string
  sourceDatabaseName: string
  format: 'CUSTOM' | 'SQL'
  md5Hash?: string
  createdAt: string
  expiresAt?: string
  fileSize: number
  label?: string
  containerName?: string
  description?: string
  lastUsedAt?: string
  createdBy?: string
  tenantId?: string
  sharedWithTenants?: string[]
}

export interface ActiveSession {
  user: string
  state: string
  query: string
  clientAddr: string | null
  durationSeconds: number
  waitEventType: string | null
}

export interface UserConnectionCount {
  user: string
  connections: number
  active: number
  idle: number
}

export interface TopQuery {
  queryText: string
  calls: number
  totalTimeMs: number
  meanTimeMs: number
  rows: number
  tempBlksRead: number
  tempBlksWritten: number
}

export interface BlockedProcess {
  blockedPid: number
  blockedUser: string
  blockedQuery: string
  blockedMode: string
  relName: string | null
  blockingPid: number
  blockingUser: string
  blockingQuery: string
  blockingMode: string
  waitingSeconds: number
}

export interface DatabaseActivity {
  sessions: ActiveSession[]
  topUsers: UserConnectionCount[]
  topQueries: TopQuery[]
  blockedProcesses: BlockedProcess[]
  pgStatStatementsAvailable: boolean
}

export interface TableStats {
  tableName: string
  schemaName: string
  totalSizeBytes: number
  tableSizeBytes: number
  indexSizeBytes: number
  liveTuples: number
  deadTuples: number
  seqScan: number
  idxScan: number
  lastVacuum: string | null
  lastAutoVacuum: string | null
  lastAnalyze: string | null
  lastAutoAnalyze: string | null
}

export interface IndexInfo {
  indexName: string
  tableName: string
  schemaName: string
  sizeBytes: number
  idxScan: number
  isPrimary: boolean
  isUnique: boolean
}

export interface IndexImpact {
  tableName: string
  unusedIndexes: number
  wastedBytes: number
  totalWrites: number
  seqScans: number
  idxScans: number
}

export interface DatabaseTableStats {
  tables: TableStats[]
  unusedIndexes: IndexInfo[]
  usedIndexes: IndexInfo[]
  indexImpact: IndexImpact[]
  statsResetAt: string | null
}

export interface DatabaseHealthInfo {
  sizeBytes: number
  activeConnections: number
  waitingConnections: number
  longRunningQueries: number
  cacheHitRatio: number
  xactCommit: number
  xactRollback: number
  tempBytes: number
  tempFiles: number
  deadTuples: number
  txIdAge: number
}

export interface ServerHealth {
  pgVersion: string
  serverStartedAt?: string
  maxConnections: number
  totalConnections: number
  totalDiskSize: number
}

export interface QueryResult {
  columns: string[]
  rows: (string | number | boolean | null)[][]
  page: number
  pageSize: number
  totalRows: number
  executionTimeMs: number
  queryType: string
}

export interface ManagedDatabaseInfo {
  name: string
  repository: string
  sizeBytes: number
  activeConnections: number
  pgLastActivity?: string
  appLastUsedAt?: string
  effectiveLastUsedAt?: string
  protectedFlag: boolean
  createdAt?: string
  description?: string
  containerCount: number
  earliestExpiration?: string
  scheduledForDeletion: boolean
  lastRestoredFrom?: string
  lastRestoredAt?: string
  lastRestoredBy?: string
  createdBy?: string
  tenantId?: string
}
