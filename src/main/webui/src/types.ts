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
}
