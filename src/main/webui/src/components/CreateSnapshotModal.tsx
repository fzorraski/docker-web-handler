import { useState, useEffect, useRef } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  MenuItem,
  Grid,
  IconButton,
  CircularProgress,
  ToggleButtonGroup,
  ToggleButton,
  Typography,
  FormControlLabel,
  Switch,
} from '@mui/material'
import { Close, CameraAlt, Download } from '@mui/icons-material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { getSnapshotRepositories, downloadSnapshotDirect, cancelSnapshot } from '../services/snapshotService'
import { getRepositoryDatabases } from '../services/containerService'
import { prepareSnapshot, streamSnapshot, type ContainerEvent } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import OperationProgress, { SNAPSHOT_STEPS } from './OperationProgress'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
  initialRepository?: string
  initialDatabase?: string
  containerName?: string
}

export default function CreateSnapshotModal({ open, onClose, onCreated, initialRepository, initialDatabase, containerName }: Props) {
  const { notify } = useNotification()
  const locked = !!(initialRepository && initialDatabase)
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [databases, setDatabases] = useState<string[]>([])
  const [dbLoading, setDbLoading] = useState(false)
  const [selectedDb, setSelectedDb] = useState('')
  const [format, setFormat] = useState<'CUSTOM' | 'SQL'>('CUSTOM')
  const [label, setLabel] = useState('')
  const [description, setDescription] = useState('')
  const [expirationEnabled, setExpirationEnabled] = useState(false)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(7, 'day'))
  const [password, setPassword] = useState('')
  const [running, setRunning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [sseEvents, setSseEvents] = useState<ContainerEvent[]>([])
  const [sseError, setSseError] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)
  const pendingInitialDb = useRef<string | undefined>(undefined)

  useEffect(() => {
    if (open) {
      if (locked) {
        setSelectedRepo(initialRepository)
        setSelectedDb(initialDatabase)
        setDatabases([initialDatabase])
      } else {
        getSnapshotRepositories().then(setRepositories).catch(() => setRepositories([]))
        if (initialRepository) {
          setSelectedRepo(initialRepository)
          pendingInitialDb.current = initialDatabase
        }
      }
    }
  }, [open, initialRepository, initialDatabase, locked])

  useEffect(() => {
    if (!selectedRepo || locked) return
    setDbLoading(true)
    setSelectedDb('')
    getRepositoryDatabases(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.databases) {
          setDatabases(res.databases)
          if (pendingInitialDb.current && res.databases.includes(pendingInitialDb.current)) {
            setSelectedDb(pendingInitialDb.current)
            pendingInitialDb.current = undefined
          }
        } else {
          setDatabases([])
        }
      })
      .catch(() => setDatabases([]))
      .finally(() => setDbLoading(false))
  }, [selectedRepo, locked])

  function resetForm() {
    setSelectedRepo('')
    setDatabases([])
    setSelectedDb('')
    setFormat('CUSTOM')
    setLabel('')
    setDescription('')
    setExpirationEnabled(false)
    setExpiresAt(dayjs().add(7, 'day'))
    setPassword('')
    setSseEvents([])
    setSseError(false)
    setRunning(false)
    setCancelling(false)
    setDownloading(false)
  }

  function handleClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    onClose()
    setTimeout(resetForm, 300)
  }

  async function handleCancel() {
    if (!selectedRepo || !selectedDb) return
    setCancelling(true)
    await cancelSnapshot(selectedRepo, selectedDb)
  }

  async function handleSaveToServer() {
    if (!validate()) return

    setRunning(true)
    setSseEvents([])
    setSseError(false)

    try {
      const ticket = await prepareSnapshot({
        repository: selectedRepo,
        sourceDatabaseName: selectedDb,
        format,
        label: label.trim() || undefined,
        description: description.trim() || undefined,
        expiresAt: expirationEnabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : undefined,
        password,
        containerName,
      })

      cleanupSse.current = streamSnapshot(
        ticket,
        (event) => setSseEvents((prev) => [...prev, event]),
        () => {
          setTimeout(() => {
            onClose()
            onCreated()
            notify('Snapshot created successfully.', 'success')
            setTimeout(resetForm, 300)
          }, 1500)
        },
        () => {
          setRunning(false)
          setSseError(true)
        },
      )
    } catch (e) {
      setRunning(false)
      notify(e instanceof Error ? e.message : 'An unexpected error occurred.', 'error')
    }
  }

  function handleDownload() {
    if (!validate()) return
    setDownloading(true)
    downloadSnapshotDirect({
      repository: selectedRepo,
      sourceDatabaseName: selectedDb,
      format,
      label: label.trim() || undefined,
      password,
      containerName,
    })
    setTimeout(() => setDownloading(false), 3000)
  }

  function validate(): boolean {
    if (!selectedRepo) { notify('Please select a repository.', 'warning'); return false }
    if (!selectedDb) { notify('Please select a database.', 'warning'); return false }
    if (!password) { notify('Please enter the operations password.', 'warning'); return false }
    return true
  }

  const formReady = selectedRepo && selectedDb && password

  return (
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (running && (reason === 'escapeKeyDown' || reason === 'backdropClick')) return
        handleClose()
      }}
      maxWidth="md"
      fullWidth
    >
      <DialogTitle sx={{ bgcolor: 'primary.dark', color: 'white', display: 'flex', alignItems: 'center' }}>
        <CameraAlt sx={{ mr: 1 }} /> Create Database Snapshot
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {running || sseEvents.length > 0 ? (
          <OperationProgress events={sseEvents} steps={SNAPSHOT_STEPS} />
        ) : (
          <>
            <TextField
              fullWidth
              type="password"
              label="Operations Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              size="small"
              sx={{ mb: 3 }}
              autoComplete="off"
            />

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                {locked ? (
                  <TextField
                    fullWidth
                    label="Repository"
                    value={selectedRepo}
                    size="small"
                    disabled
                  />
                ) : (
                  <TextField
                    select
                    fullWidth
                    label="Repository"
                    value={selectedRepo}
                    onChange={(e) => setSelectedRepo(e.target.value)}
                    size="small"
                  >
                    <MenuItem value="">Select a repository...</MenuItem>
                    {repositories.map((r) => (
                      <MenuItem key={r} value={r}>{r}</MenuItem>
                    ))}
                  </TextField>
                )}
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                {locked ? (
                  <TextField
                    fullWidth
                    label="Source Database"
                    value={selectedDb}
                    size="small"
                    disabled
                  />
                ) : (
                  <TextField
                    select
                    fullWidth
                    label="Source Database"
                    value={selectedDb}
                    onChange={(e) => setSelectedDb(e.target.value)}
                    size="small"
                    disabled={!selectedRepo || dbLoading}
                    slotProps={{
                      input: {
                        endAdornment: dbLoading ? <CircularProgress size={20} /> : null,
                      },
                    }}
                  >
                    <MenuItem value="">Select a database...</MenuItem>
                    {databases.map((db) => (
                      <MenuItem key={db} value={db}>{db}</MenuItem>
                    ))}
                  </TextField>
                )}
              </Grid>
            </Grid>

            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  Output Format
                </Typography>
                <ToggleButtonGroup
                  value={format}
                  exclusive
                  onChange={(_e, val) => { if (val) setFormat(val) }}
                  size="small"
                  fullWidth
                >
                  <ToggleButton value="CUSTOM">Custom (.dump)</ToggleButton>
                  <ToggleButton value="SQL">SQL (.sql)</ToggleButton>
                </ToggleButtonGroup>
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label="Label (optional)"
                  value={label}
                  onChange={(e) => setLabel(e.target.value)}
                  size="small"
                  placeholder="e.g. before-migration-v2"
                  slotProps={{ htmlInput: { maxLength: 100 } }}
                  sx={{ mt: 3 }}
                />
              </Grid>
            </Grid>

            <TextField
              fullWidth
              label="Description (optional)"
              placeholder="Brief description of this snapshot"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              size="small"
              multiline
              minRows={2}
              maxRows={4}
              slotProps={{ htmlInput: { maxLength: 500 } }}
              sx={{ mb: 3 }}
            />

            <Grid container spacing={2} sx={{ mb: 2 }} alignItems="center">
              <Grid size={{ xs: 12, md: 4 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={expirationEnabled}
                      onChange={(e) => setExpirationEnabled(e.target.checked)}
                    />
                  }
                  label="Auto-delete snapshot"
                />
              </Grid>
              {expirationEnabled && (
                <Grid size={{ xs: 12, md: 8 }}>
                  <MobileDateTimePicker
                    label="Expires at"
                    value={expiresAt}
                    onChange={(v) => setExpiresAt(v)}
                    minDateTime={dayjs()}
                    slotProps={{
                      textField: {
                        fullWidth: true,
                        size: 'small',
                        helperText: 'Snapshot will be automatically deleted at this time',
                      },
                    }}
                  />
                </Grid>
              )}
            </Grid>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        {sseError ? (
          <>
            <Button onClick={handleClose} color="inherit">Close</Button>
            <Button
              variant="contained"
              color="primary"
              onClick={() => {
                setSseEvents([])
                setSseError(false)
              }}
            >
              Back to Form
            </Button>
          </>
        ) : running ? (
          <Button
            onClick={handleCancel}
            color="error"
            variant="contained"
            disabled={cancelling}
            startIcon={cancelling ? <CircularProgress size={20} /> : undefined}
          >
            {cancelling ? 'Cancelling...' : 'Cancel'}
          </Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">Cancel</Button>
            <Button
              variant="contained"
              color="info"
              onClick={handleDownload}
              disabled={!formReady || downloading}
              startIcon={downloading ? <CircularProgress size={20} /> : <Download />}
            >
              {downloading ? 'Preparing...' : 'Download'}
            </Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleSaveToServer}
              disabled={!formReady}
              startIcon={<CameraAlt />}
            >
              Save to Server
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
