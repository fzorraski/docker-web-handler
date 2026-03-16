import { useState, useEffect, useRef } from 'react'
import {
  Autocomplete,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  MenuItem,
  Grid,
  IconButton,
  FormControlLabel,
  Switch,
  Checkbox,
  CircularProgress,
  Typography,
  Box,
  Chip,
} from '@mui/material'
import { Close, Restore } from '@mui/icons-material'
import type { DatabaseDump } from '../types'
import { buildTargetDbName } from '../utils/format'
import { getDumpRepositories, cancelRestore, getPostRestoreScripts, type PostRestoreScriptsResponse } from '../services/dumpService'
import { getRepositoryDatabases } from '../services/containerService'
import { prepareRestoreDump, streamRestoreDump, type ContainerEvent } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import OperationProgress, { RESTORE_STEPS, RESTORE_WITH_SCRIPTS_STEPS } from './OperationProgress'

function formatScriptSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

interface Props {
  open: boolean
  dump: DatabaseDump | null
  onClose: () => void
  onRestored: () => void
}

export default function RestoreDumpModal({ open, dump, onClose, onRestored }: Props) {
  const { notify } = useNotification()
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [databases, setDatabases] = useState<string[]>([])
  const [dbLoading, setDbLoading] = useState(false)
  const [targetDb, setTargetDb] = useState<string>('')
  const [createDb, setCreateDb] = useState(false)
  const [password, setPassword] = useState('')
  const [running, setRunning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [sseEvents, setSseEvents] = useState<ContainerEvent[]>([])
  const [sseError, setSseError] = useState(false)
  const [scriptsResponse, setScriptsResponse] = useState<PostRestoreScriptsResponse | null>(null)
  const [selectedOptionalScripts, setSelectedOptionalScripts] = useState<string[]>([])
  const cleanupSse = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (open) {
      getDumpRepositories().then(setRepositories).catch(() => setRepositories([]))
    }
  }, [open])

  useEffect(() => {
    if (!selectedRepo) {
      setDatabases([])
      setScriptsResponse(null)
      setSelectedOptionalScripts([])
      return
    }
    setDbLoading(true)
    getRepositoryDatabases(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.databases) setDatabases(res.databases)
        else setDatabases([])
      })
      .catch(() => setDatabases([]))
      .finally(() => setDbLoading(false))
    getPostRestoreScripts(selectedRepo)
      .then((res) => {
        setScriptsResponse(res)
        if (res.enabled) {
          setSelectedOptionalScripts(res.optional.map((s) => s.filename))
        }
      })
      .catch(() => setScriptsResponse(null))
  }, [selectedRepo])

  useEffect(() => {
    if (open && dump) {
      setTargetDb(buildTargetDbName(dump))
    }
  }, [open, dump])

  function resetForm() {
    setSelectedRepo('')
    setDatabases([])
    setTargetDb('')
    setCreateDb(false)
    setPassword('')
    setSseEvents([])
    setSseError(false)
    setRunning(false)
    setCancelling(false)
    setScriptsResponse(null)
    setSelectedOptionalScripts([])
  }

  function handleClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    onClose()
    // Reset after close so the dialog content doesn't flash empty during closing animation
    setTimeout(resetForm, 300)
  }

  async function handleCancel() {
    if (!selectedRepo || !targetDb.trim()) return
    setCancelling(true)
    await cancelRestore(selectedRepo, targetDb.trim())
    // The backend will send an ERROR event through SSE which triggers the onError handler
  }

  async function handleRestore() {
    if (!dump) return
    if (!selectedRepo) return notify('Please select a repository.', 'warning')
    if (!targetDb.trim()) return notify('Please enter a target database.', 'warning')
    if (!password) return notify('Please enter the operations password.', 'warning')

    setRunning(true)
    setSseEvents([])
    setSseError(false)

    try {
      const isNew = !databases.includes(targetDb.trim())

      const ticket = await prepareRestoreDump({
        dumpId: dump.id,
        repository: selectedRepo,
        targetDatabase: targetDb.trim(),
        createDatabase: createDb || isNew,
        password,
        selectedOptionalScripts: scriptsResponse?.enabled ? selectedOptionalScripts : undefined,
      })

      cleanupSse.current = streamRestoreDump(
        ticket,
        (event) => setSseEvents((prev) => [...prev, event]),
        () => {
          setTimeout(() => {
            onClose()
            onRestored()
            notify('Dump restored successfully.', 'success')
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
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Restore sx={{ mr: 1 }} /> Restore Dump {dump ? `- ${dump.originalFilename}` : ''}
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {running || sseEvents.length > 0 ? (
          <OperationProgress events={sseEvents} steps={scriptsResponse?.enabled ? RESTORE_WITH_SCRIPTS_STEPS : RESTORE_STEPS} />
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
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <Autocomplete
                  freeSolo
                  options={databases}
                  value={targetDb}
                  onInputChange={(_e, value) => {
                    setTargetDb(value)
                    if (value && !databases.includes(value)) {
                      setCreateDb(true)
                    }
                  }}
                  loading={dbLoading}
                  disabled={!selectedRepo}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Target Database"
                      placeholder="Select or type a new database name"
                      size="small"
                      slotProps={{
                        input: {
                          ...params.InputProps,
                          endAdornment: (
                            <>
                              {dbLoading ? <CircularProgress size={20} /> : null}
                              {params.InputProps.endAdornment}
                            </>
                          ),
                        },
                      }}
                    />
                  )}
                />
              </Grid>
            </Grid>

            <FormControlLabel
              control={
                <Switch
                  checked={createDb}
                  onChange={(e) => setCreateDb(e.target.checked)}
                />
              }
              label="Create database if it doesn't exist"
            />

            {scriptsResponse?.enabled && (scriptsResponse.mandatory.length > 0 || scriptsResponse.optional.length > 0) && (
              <Box sx={{ mt: 3 }}>
                <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
                  Post-Restore Scripts
                </Typography>

                {scriptsResponse.mandatory.length > 0 && (
                  <Box sx={{ mb: 1 }}>
                    <Typography variant="caption" color="text.secondary">Mandatory (always run)</Typography>
                    {scriptsResponse.mandatory.map((s) => (
                      <FormControlLabel
                        key={s.filename}
                        control={<Checkbox checked disabled size="small" />}
                        label={
                          <Typography variant="body2">
                            {s.filename}
                            <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                          </Typography>
                        }
                        sx={{ display: 'flex', ml: 0 }}
                      />
                    ))}
                  </Box>
                )}

                {scriptsResponse.optional.length > 0 && (
                  <Box sx={{ mb: 1 }}>
                    <Typography variant="caption" color="text.secondary">Optional</Typography>
                    {scriptsResponse.optional.map((s) => (
                      <FormControlLabel
                        key={s.filename}
                        control={
                          <Checkbox
                            checked={selectedOptionalScripts.includes(s.filename)}
                            onChange={(e) => {
                              if (e.target.checked) {
                                setSelectedOptionalScripts((prev) => [...prev, s.filename])
                              } else {
                                setSelectedOptionalScripts((prev) => prev.filter((f) => f !== s.filename))
                              }
                            }}
                            size="small"
                          />
                        }
                        label={
                          <Typography variant="body2">
                            {s.filename}
                            <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                          </Typography>
                        }
                        sx={{ display: 'flex', ml: 0 }}
                      />
                    ))}
                  </Box>
                )}

                <Typography variant="caption" color="text.secondary">
                  On failure: {scriptsResponse.onFailure === 'stop' ? 'stop execution' : 'continue with remaining scripts'}
                </Typography>
              </Box>
            )}
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
            {cancelling ? 'Cancelling...' : 'Cancel Restore'}
          </Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">Cancel</Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleRestore}
              disabled={running || !selectedRepo || !targetDb.trim() || !password}
              startIcon={<Restore />}
            >
              Restore
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
