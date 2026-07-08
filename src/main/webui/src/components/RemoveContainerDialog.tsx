import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  TextField,
  Typography,
} from '@mui/material'
import { Delete, Warning } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useAuth } from './AuthProvider'
import { P } from '../utils/permissions'
import { getDatabaseConflicts } from '../services/containerService'
import type { DockerContainer, DatabaseConflict } from '../types'

function sanitizeHtml(html: string): string {
  return html.replace(/<(?!\/?(?:strong|b|em|br)\b)[^>]*>/gi, '')
}

interface Props {
  open: boolean
  container: DockerContainer | null
  dbDeletionEnabled: boolean
  opsPwRequired: boolean
  onClose: () => void
  onConfirm: (deleteDatabase: boolean, password?: string) => void
}

export default function RemoveContainerDialog({ open, container, dbDeletionEnabled, opsPwRequired, onClose, onConfirm }: Props) {
  const { t } = useTranslation()
  const { hasPermission } = useAuth()
  const canDeleteDb = hasPermission(P.DATABASE_DELETE)
  const [deleteDb, setDeleteDb] = useState(false)
  const [password, setPassword] = useState('')
  const [dbConflict, setDbConflict] = useState<DatabaseConflict | null>(null)
  const [error, setError] = useState('')

  const hasDb = !!container?.databaseName && dbDeletionEnabled && !!container?.deleteDatabaseOnExpiration && canDeleteDb
  const isProtected = dbConflict?.protectedFlag ?? false
  const otherContainers = (dbConflict?.inUseByContainers ?? []).filter(n => n !== container?.names)

  useEffect(() => {
    if (open && container?.databaseName) {
      setDeleteDb(false)
      setPassword('')
      setError('')
      getDatabaseConflicts(container.databaseName)
        .then(setDbConflict)
        .catch(() => setDbConflict(null))
    } else {
      setDeleteDb(false)
      setPassword('')
      setError('')
      setDbConflict(null)
    }
  }, [open, container?.databaseName, container?.names])

  const handleConfirm = () => {
    if (deleteDb && opsPwRequired && !password.trim()) {
      setError(t('common.invalidOperationsPassword'))
      return
    }
    onConfirm(deleteDb, deleteDb ? password : undefined)
  }

  if (!container) return null

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
        <Delete sx={{ mr: 1, verticalAlign: 'middle' }} />
        {t('containers.removeContainer.title')}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <Typography gutterBottom>
          <span dangerouslySetInnerHTML={{
            __html: sanitizeHtml(t('containers.removeContainer.confirmText', { name: container.names }))
          }} />
        </Typography>

        {hasDb && (
          <>
            <FormControlLabel
              control={
                <Checkbox
                  checked={deleteDb}
                  onChange={(e) => { setDeleteDb(e.target.checked); setError('') }}
                  disabled={isProtected}
                  color="error"
                />
              }
              label={t('containers.removeContainer.deleteDatabaseCheckbox', { database: container.databaseName })}
              sx={{ mt: 2 }}
            />

            {isProtected && (
              <Alert severity="info" sx={{ mt: 1 }}>
                {t('containers.removeContainer.dbProtected', { database: container.databaseName })}
              </Alert>
            )}

            {otherContainers.length > 0 && deleteDb && (
              <Alert severity="warning" icon={<Warning />} sx={{ mt: 1 }}>
                {t('containers.removeContainer.otherContainersWarning', {
                  containers: otherContainers.join(', ')
                })}
              </Alert>
            )}

            {deleteDb && !isProtected && (
              <Alert severity="error" sx={{ mt: 1 }}>
                <span dangerouslySetInnerHTML={{
                  __html: sanitizeHtml(t('containers.removeContainer.dbWillBeDeleted', { database: container.databaseName }))
                }} />
              </Alert>
            )}

            {deleteDb && opsPwRequired && (
              <TextField
                fullWidth
                type="password"
                label={t('common.operationsPassword')}
                value={password}
                onChange={(e) => { setPassword(e.target.value); setError('') }}
                error={!!error}
                helperText={error}
                size="small"
                sx={{ mt: 2 }}
                autoComplete="off"
              />
            )}
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit">{t('common.cancel')}</Button>
        <Button onClick={handleConfirm} color="error" variant="contained">
          {t('common.remove')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
