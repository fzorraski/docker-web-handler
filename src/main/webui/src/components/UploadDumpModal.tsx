import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  IconButton,
  Typography,
  Box,
  Grid,
  LinearProgress,
  Alert,
  FormControlLabel,
  Switch,
  Chip,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { formatDate } from '../utils/format'
import { CheckCircle, Close, CloudUpload, Timer } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { uploadDump } from '../services/dumpService'
import { getDefaultExpirationMinutes } from '../services/containerService'
import { useNotification } from './NotificationProvider'
import type { DatabaseDump } from '../types'

interface Props {
  open: boolean
  onClose: () => void
  onUploaded: () => void
  existingFilenames: string[]
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

const ACCEPTED_EXTENSIONS = '.sql,.dump,.gz'

export default function UploadDumpModal({ open, onClose, onUploaded, existingFilenames }: Props) {
  const { notify } = useNotification()
  const { t } = useTranslation()
  const [password, setPassword] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [databaseName, setDatabaseName] = useState('')
  const [version, setVersion] = useState('')
  const [description, setDescription] = useState('')
  const [defaultExpMinutes, setDefaultExpMinutes] = useState(480)
  const [expirationEnabled, setExpirationEnabled] = useState(false)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [uploadResult, setUploadResult] = useState<DatabaseDump | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)

  const EXPIRATION_PRESETS = [
    { label: t('uploadDump.presets.1day'), amount: 1, unit: 'day' as const },
    { label: t('uploadDump.presets.3days'), amount: 3, unit: 'day' as const },
    { label: t('uploadDump.presets.1week'), amount: 7, unit: 'day' as const },
    { label: t('uploadDump.presets.1month'), amount: 30, unit: 'day' as const },
  ]

  useEffect(() => {
    getDefaultExpirationMinutes()
      .then(setDefaultExpMinutes)
      .catch(() => {})
  }, [])

  function resetForm() {
    setPassword('')
    setFile(null)
    setDatabaseName('')
    setVersion('')
    setDescription('')
    setExpirationEnabled(false)
    setExpiresAt(null)
    setUploading(false)
    setUploadProgress(0)
    setUploadResult(null)
    setUploadError(null)
  }

  function handleClose() {
    if (!uploading) {
      const hadResult = !!uploadResult
      resetForm()
      onClose()
      if (hadResult) onUploaded()
    }
  }

  async function handleUpload() {
    if (!file) return notify(t('uploadDump.selectFileWarning'), 'warning')
    if (!password) return notify(t('uploadDump.enterPasswordWarning'), 'warning')

    setUploading(true)
    setUploadProgress(0)
    setUploadResult(null)
    setUploadError(null)

    const result = await uploadDump(
      file,
      password,
      {
        databaseName: databaseName || undefined,
        version: version || undefined,
        expiresAt: expirationEnabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : undefined,
        description: description || undefined,
      },
      (percent) => setUploadProgress(percent),
    )

    setUploading(false)
    if (result.success && result.dump) {
      setUploadResult(result.dump)
    } else {
      setUploadError(result.error || t('uploadDump.uploadFailed'))
    }
  }

  function handleExpirationToggle(enabled: boolean) {
    setExpirationEnabled(enabled)
    if (enabled && !expiresAt) {
      setExpiresAt(dayjs().add(defaultExpMinutes, 'minute'))
    }
  }

  const isDuplicateFilename = !!(file && existingFilenames.includes(file.name))
  const showForm = !uploading && !uploadResult && !uploadError

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        <CloudUpload sx={{ mr: 1 }} /> {t('uploadDump.title')}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }} disabled={uploading}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {/* Upload in progress */}
        {uploading && (
          <Box sx={{ py: 2 }}>
            <Typography variant="body1" sx={{ mb: 1, fontWeight: 600 }}>
              {t('uploadDump.uploading', { filename: file?.name })}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {formatBytes(file?.size ?? 0)} &mdash; {uploadProgress}%
            </Typography>
            <LinearProgress
              variant="determinate"
              value={uploadProgress}
              sx={{ height: 8, borderRadius: 4 }}
            />
          </Box>
        )}

        {/* Upload success */}
        {uploadResult && (
          <Alert severity="success" icon={<CheckCircle />} sx={{ py: 2 }}>
            <Typography variant="body1" fontWeight={600} sx={{ mb: 1 }}>
              {t('uploadDump.uploadComplete')}
            </Typography>
            <Typography variant="body2"><strong>{t('uploadDump.file')}</strong> {uploadResult.originalFilename}</Typography>
            <Typography variant="body2"><strong>{t('uploadDump.size')}</strong> {formatBytes(uploadResult.fileSize)}</Typography>
            <Typography variant="body2"><strong>{t('uploadDump.format')}</strong> {uploadResult.format}</Typography>
            {uploadResult.md5Hash && (
              <Typography variant="body2" fontFamily="monospace"><strong>{t('uploadDump.md5')}</strong> {uploadResult.md5Hash}</Typography>
            )}
            {uploadResult.version && (
              <Typography variant="body2"><strong>{t('uploadDump.version')}</strong> {uploadResult.version}</Typography>
            )}
            {uploadResult.databaseName && (
              <Typography variant="body2"><strong>{t('uploadDump.database')}</strong> {uploadResult.databaseName}</Typography>
            )}
            {uploadResult.expiresAt && (
              <Typography variant="body2">
                <strong>{t('uploadDump.expires')}</strong> {formatDate(uploadResult.expiresAt)}
              </Typography>
            )}
          </Alert>
        )}

        {/* Upload error */}
        {uploadError && (
          <Alert severity="error" sx={{ py: 2 }}>
            <Typography variant="body1" fontWeight={600} sx={{ mb: 1 }}>{t('uploadDump.uploadFailed')}</Typography>
            <Typography variant="body2">{uploadError}</Typography>
          </Alert>
        )}

        {/* Form */}
        {showForm && (
          <>
            <TextField
              fullWidth
              type="password"
              label={t('common.uploadPassword')}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              autoComplete="off"
            />

            <Box sx={{ mb: 3 }}>
              <Button variant="outlined" component="label" startIcon={<CloudUpload />} fullWidth>
                {file ? file.name : t('uploadDump.selectFile')}
                <input
                  type="file"
                  hidden
                  accept={ACCEPTED_EXTENSIONS}
                  onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                />
              </Button>
              {file && !isDuplicateFilename && (
                <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
                  {formatBytes(file.size)}
                </Typography>
              )}
              {isDuplicateFilename && (
                <Alert severity="warning" variant="outlined" sx={{ mt: 1 }}>
                  <span dangerouslySetInnerHTML={{ __html: t('uploadDump.duplicateFilename', { filename: file!.name }) }} />
                </Alert>
              )}
            </Box>

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label={t('uploadDump.versionLabel')}
                  placeholder={t('uploadDump.versionPlaceholder')}
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  size="small"
                />
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label={t('uploadDump.databaseLabel')}
                  placeholder={t('uploadDump.databasePlaceholder')}
                  value={databaseName}
                  onChange={(e) => setDatabaseName(e.target.value)}
                  size="small"
                />
              </Grid>
            </Grid>

            <TextField
              fullWidth
              label={t('uploadDump.descriptionLabel')}
              placeholder={t('uploadDump.descriptionPlaceholder')}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              size="small"
              multiline
              minRows={2}
              maxRows={4}
              slotProps={{ htmlInput: { maxLength: 500 } }}
              sx={{ mb: 3 }}
            />

            {/* Expiration */}
            <Grid container spacing={2} sx={{ alignItems: 'center' }}>
              <Grid size={{ xs: 12, md: 4 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={expirationEnabled}
                      onChange={(e) => handleExpirationToggle(e.target.checked)}
                    />
                  }
                  label={
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Timer fontSize="small" /> {t('uploadDump.autoExpire')}
                    </Box>
                  }
                />
              </Grid>
              {expirationEnabled && (
                <>
                  <Grid size={{ xs: 12 }}>
                    <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
                      {EXPIRATION_PRESETS.map((opt) => {
                        const target = dayjs().add(opt.amount, opt.unit)
                        return (
                          <Chip
                            key={opt.label}
                            label={opt.label}
                            onClick={() => setExpiresAt(target)}
                            color={expiresAt && expiresAt.isSame(target, 'minute') ? 'primary' : 'default'}
                            variant={expiresAt && expiresAt.isSame(target, 'minute') ? 'filled' : 'outlined'}
                            clickable
                          />
                        )
                      })}
                    </Box>
                  </Grid>
                  <Grid size={{ xs: 12 }}>
                    <MobileDateTimePicker
                      label={t('uploadDump.expiresAt')}
                      value={expiresAt}
                      onChange={(v) => setExpiresAt(v)}
                      minDateTime={dayjs()}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          size: 'small',
                          helperText: t('uploadDump.expiresHelperText'),
                        },
                      }}
                    />
                  </Grid>
                </>
              )}
            </Grid>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {uploadResult ? (
          <Button variant="contained" color="primary" onClick={handleClose}>{t('common.done')}</Button>
        ) : uploadError ? (
          <>
            <Button onClick={handleClose} color="inherit">{t('common.close')}</Button>
            <Button variant="contained" color="primary" onClick={() => {
              setUploadError(null)
              setUploadProgress(0)
            }}>
              {t('common.tryAgain')}
            </Button>
          </>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit" disabled={uploading}>{t('common.cancel')}</Button>
            <Button
              variant="contained"
              color="primary"
              onClick={handleUpload}
              disabled={uploading || !file || !password || isDuplicateFilename}
              startIcon={<CloudUpload />}
            >
              {t('common.upload')}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
