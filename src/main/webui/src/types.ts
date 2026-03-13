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
}

export interface DockerImage {
  repository: string
  tag: string
  imageId: string
  created: string
  size: string
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
}
