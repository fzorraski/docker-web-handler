import { useState } from 'react'
import { previewMigration, type MigrationPreview } from '../services/containerService'
import type { MigrationConfig } from '../components/MigrationConfigModal'

export function useMigrationPreview() {
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewData, setPreviewData] = useState<MigrationPreview | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState('')

  async function showPreview(config: MigrationConfig, repository: string): Promise<boolean> {
    if (!config.validateBeforeExecute) return false

    setPreviewLoading(true)
    setPreviewError('')
    setPreviewData(null)
    setPreviewOpen(true)

    if (config.mode === 'API' && config.sourceVersion && config.targetVersion) {
      try {
        const data = await previewMigration(repository, config.sourceVersion, config.targetVersion)
        setPreviewData(data)
      } catch (e) {
        setPreviewError(e instanceof Error ? e.message : 'Failed to fetch migration preview.')
      } finally {
        setPreviewLoading(false)
      }
    } else if (config.mode === 'MANUAL' && config.sql) {
      const statementCount = config.sql.split('\n').filter(l => l.trim() && !l.trim().startsWith('--')).length
      setPreviewData({
        sql: config.sql,
        sourceVersion: config.sourceVersion,
        targetVersion: config.targetVersion,
        totalStatements: statementCount,
      })
      setPreviewLoading(false)
    }

    return true
  }

  function closePreview() {
    setPreviewOpen(false)
    setPreviewData(null)
  }

  function reset() {
    setPreviewOpen(false)
    setPreviewData(null)
    setPreviewLoading(false)
    setPreviewError('')
  }

  return { previewOpen, previewData, previewLoading, previewError, showPreview, closePreview, reset }
}
