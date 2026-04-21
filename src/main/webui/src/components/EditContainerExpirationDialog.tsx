import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  FormControlLabel,
  Switch,
  CircularProgress,
  Alert,
  Box,
  Typography,
} from '@mui/material'
import { Timer, Warning, PlayArrow } from '@mui/icons-material'
import dayjs, { type Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'
import ExpirationPicker from './ExpirationPicker'
import type { DockerContainer, DatabaseConflict } from '../types'
import { getDatabaseConflicts, validateOperationsPassword, type UpdateExpirationRequest } from '../services/containerService'

interface Props {
  open: boolean
  container: DockerContainer | null
  dbDeletionEnabled: boolean
  operationsPasswordRequired: boolean
  onClose: () => void
  onSave: (request: UpdateExpirationRequest) => Promise<{ success: boolean; error?: string }>
}

export default function EditContainerExpirationDialog({
  open,
  container,
  dbDeletionEnabled,
  operationsPasswordRequired,
  onClose,
  onSave,
}: Props) {
  const { t } = useTranslation()
  const [enabled, setEnabled] = useState(false)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(8, 'hour'))
  const [deleteDbOnExpiration, setDeleteDbOnExpiration] = useState(false)
  const [operationsPassword, setOperationsPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [dbConflict, setDbConflict] = useState<DatabaseConflict | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmNameInput, setConfirmNameInput] = useState('')

  const otherContainersUsingDb = (dbConflict?.inUseByContainers ?? []).filter(name => name !== container?.names)
  const hasDbUsageConflict = otherContainersUsingDb.length > 0
  const isAdding = !container?.expiresAt
  const showDbDeletion = dbDeletionEnabled && !!container?.databaseName && enabled
  // DB deletion is being newly enabled (wasn't on before)
  const isEnablingDbDeletion = deleteDbOnExpiration && enabled && !(container?.deleteDatabaseOnExpiration)

  useEffect(() => {
    if (!open) {
      setOperationsPassword('')
      return
    }
    if (!container) return
    if (container.expiresAt) {
      setEnabled(true)
      setExpiresAt(dayjs(container.expiresAt))
    } else {
      setEnabled(false)
      setExpiresAt(dayjs().add(8, 'hour'))
    }
    setDeleteDbOnExpiration(container.deleteDatabaseOnExpiration ?? false)
    setOperationsPassword('')
    setError('')
    setSaving(false)
    setDbConflict(null)
    setConfirmOpen(false)
    setConfirmNameInput('')

    if (container.databaseName) {
      getDatabaseConflicts(container.databaseName)
        .then(setDbConflict)
        .catch(() => setDbConflict(null))
    }
  }, [open, container])

  async function handleSaveClick() {
    if (!container) return
    if (enabled && !expiresAt) {
      setError(t('editContainerExpiration.enableExpiration'))
      return
    }
    if (deleteDbOnExpiration && operationsPasswordRequired && !operationsPassword) {
      setError(t('editContainerExpiration.passwordRequired'))
      return
    }
    if (isEnablingDbDeletion && container.databaseName) {
      if (operationsPasswordRequired) {
        try {
          const valid = await validateOperationsPassword(operationsPassword)
          if (!valid) {
            setError(t('editContainerExpiration.invalidPassword'))
            return
          }
        } catch {
          setError(t('editContainerExpiration.invalidPassword'))
          return
        }
      }
      setConfirmNameInput('')
      setConfirmOpen(true)
      return
    }
    executeSave()
  }

  async function executeSave() {
    if (!container) return
    setSaving(true)
    setError('')
    const request: UpdateExpirationRequest = {
      containerId: container.containerId,
      expiresAt: enabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null,
      deleteDatabaseOnExpiration: deleteDbOnExpiration && enabled,
      operationsPassword: deleteDbOnExpiration ? operationsPassword : undefined,
    }
    const result = await onSave(request)
    setSaving(false)
    if (result.success) {
      setConfirmOpen(false)
      onClose()
    } else {
      setConfirmOpen(false)
      setError(result.error || t('containers.failedToUpdateExpiration'))
    }
  }

  const titleKey = isAdding ? 'editContainerExpiration.addTitle' : 'editContainerExpiration.title'

  return (
    <>
      <Dialog open={open && !confirmOpen} onClose={onClose} maxWidth="md" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Timer /> {t(titleKey, { name: container?.names ?? '' })}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <ExpirationPicker
            enabled={enabled}
            onEnabledChange={setEnabled}
            value={expiresAt}
            onChange={setExpiresAt}
            switchLabel={t('editContainerExpiration.enableExpiration')}
            helperText={t('newContainer.expiresHelperText')}
          />

          {showDbDeletion && (
            <Box sx={{ mt: 3 }}>
              <FormControlLabel
                control={
                  <Switch
                    checked={deleteDbOnExpiration}
                    onChange={(e) => setDeleteDbOnExpiration(e.target.checked)}
                    disabled={hasDbUsageConflict || dbConflict?.protectedFlag}
                    color="warning"
                  />
                }
                label={
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    <Warning fontSize="small" color="warning" /> {t('editContainerExpiration.deleteDbOnExpiration')}
                  </Box>
                }
              />

              {dbConflict?.protectedFlag && (
                <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
                  <span dangerouslySetInnerHTML={{ __html: t('editContainerExpiration.dbProtectedNoDeletion', { database: container?.databaseName }) }} />
                </Alert>
              )}

              {hasDbUsageConflict && !dbConflict?.protectedFlag && (
                <Alert severity="error" variant="outlined" sx={{ mt: 1 }}>
                  <span dangerouslySetInnerHTML={{ __html: t('editContainerExpiration.cannotEnableDbDeletion', { database: container?.databaseName, containers: otherContainersUsingDb.join(', ') }) }} />
                </Alert>
              )}

              {deleteDbOnExpiration && !hasDbUsageConflict && !dbConflict?.protectedFlag && (
                <Alert severity="warning" variant="outlined" sx={{ mt: 1 }}>
                  <span dangerouslySetInnerHTML={{ __html: t('editContainerExpiration.dbWillBeDeleted', { database: container?.databaseName }) }} />
                </Alert>
              )}

              {deleteDbOnExpiration && operationsPasswordRequired && (
                <TextField
                  fullWidth
                  type="password"
                  label={t('common.operationsPassword')}
                  value={operationsPassword}
                  onChange={(e) => setOperationsPassword(e.target.value)}
                  size="small"
                  sx={{ mt: 2 }}
                  autoComplete="off"
                />
              )}
            </Box>
          )}

          {error && (
            <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={onClose} color="inherit" disabled={saving}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            onClick={handleSaveClick}
            disabled={saving}
            startIcon={saving ? <CircularProgress size={20} /> : <Timer />}
          >
            {saving ? t('common.saving') : t('common.save')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white', display: 'flex', alignItems: 'center' }}>
          <Warning sx={{ mr: 1 }} /> {t('newContainer.confirmDbDeletion')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <Alert severity="warning" sx={{ mb: 3 }}>
            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbDeletionWarning', { database: container?.databaseName }) }} />
          </Alert>
          <Typography variant="body2" sx={{ mb: 2 }}>
            <span dangerouslySetInnerHTML={{ __html: t('newContainer.dbDeletionConfirmText', { database: container?.databaseName }) }} />
          </Typography>
          <TextField
            fullWidth
            size="small"
            placeholder={container?.databaseName ?? ''}
            value={confirmNameInput}
            onChange={(e) => setConfirmNameInput(e.target.value)}
            onPaste={(e) => e.preventDefault()}
            autoFocus
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={() => setConfirmOpen(false)} color="inherit" disabled={saving}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            color="warning"
            disabled={confirmNameInput !== container?.databaseName || saving}
            onClick={executeSave}
            startIcon={saving ? <CircularProgress size={20} /> : <PlayArrow />}
          >
            {saving ? t('common.saving') : t('newContainer.confirmAndRun')}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
