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
}

export interface DockerImage {
  repository: string
  tag: string
  imageId: string
  created: string
  size: string
}

export interface ApiResponse {
  state: number
  message?: string
  tags?: string[]
}
