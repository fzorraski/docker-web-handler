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
  Typography,
  Box,
  CircularProgress,
  Switch,
  FormControlLabel,
  Chip,
} from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { Add, Close, Delete, Memory, PlayArrow, Timer } from '@mui/icons-material'
import {
  getAllowedRepositories,
  getDefaultExpirationMinutes,
  getRepositoryEnvKeys,
  getRepositoryTags,
  isMemoryLimitEnabled,
} from '../services/containerService'
import { prepareRunContainer, streamRunContainer, type ContainerEvent } from '../services/sseService'
import { useNotification } from './NotificationProvider'
import OperationProgress from './OperationProgress'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

interface EnvVar {
  key: string
  value: string
}

function compareTagsDesc(a: string, b: string): number {
  const partsA = a.split(/[.\-]/)
  const partsB = b.split(/[.\-]/)
  const len = Math.max(partsA.length, partsB.length)
  for (let i = 0; i < len; i++) {
    const na = Number(partsA[i] ?? '')
    const nb = Number(partsB[i] ?? '')
    if (!isNaN(na) && !isNaN(nb)) {
      if (nb !== na) return nb - na
    } else {
      const cmp = (partsA[i] ?? '').localeCompare(partsB[i] ?? '')
      if (cmp !== 0) return cmp
    }
  }
  return 0
}

export default function NewContainerModal({ open, onClose, onCreated }: Props) {
  const { notify, confirm } = useNotification()
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [allTags, setAllTags] = useState<string[]>([])
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const [tagsLoading, setTagsLoading] = useState(false)
  const [containerName, setContainerName] = useState('')
  const [envVars, setEnvVars] = useState<EnvVar[]>([])
  const [memoryMb, setMemoryMb] = useState<string>('')
  const [memoryEnabled, setMemoryEnabled] = useState(false)
  const [defaultExpMinutes, setDefaultExpMinutes] = useState(480)
  const [expirationEnabled, setExpirationEnabled] = useState(true)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(480, 'minute'))
  const [running, setRunning] = useState(false)
  const [sseEvents, setSseEvents] = useState<ContainerEvent[]>([])
  const [sseError, setSseError] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)

  useEffect(() => {
    getAllowedRepositories()
      .then((repos) => {
        setRepositories(repos)
        if (repos.length > 0) setSelectedRepo(repos[0])
      })
      .catch(() => {})
    getDefaultExpirationMinutes()
      .then((m) => {
        setDefaultExpMinutes(m)
        setExpiresAt(dayjs().add(m, 'minute'))
      })
      .catch(() => {})
    isMemoryLimitEnabled()
      .then(setMemoryEnabled)
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!selectedRepo) {
      setAllTags([])
      setSelectedTag(null)
      setEnvVars([])
      return
    }
    setTagsLoading(true)
    setSelectedTag(null)
    getRepositoryTags(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.tags) setAllTags([...res.tags].sort(compareTagsDesc))
        else {
          setAllTags([])
          if (res.message) notify(res.message, 'error')
        }
      })
      .catch(() => setAllTags([]))
      .finally(() => setTagsLoading(false))
    getRepositoryEnvKeys(selectedRepo)
      .then((keys) => setEnvVars(keys.map((k) => ({ key: k.key, value: k.value }))))
      .catch(() => setEnvVars([]))
  }, [selectedRepo])

  function addEnvVar() {
    setEnvVars([...envVars, { key: '', value: '' }])
  }

  function updateEnvVar(index: number, field: 'key' | 'value', val: string) {
    const updated = [...envVars]
    updated[index][field] = val
    setEnvVars(updated)
  }

  function removeEnvVar(index: number) {
    setEnvVars(envVars.filter((_, i) => i !== index))
  }

  function resetForm() {
    setSelectedRepo('')
    setSelectedTag(null)
    setAllTags([])
    setContainerName('')
    setEnvVars([])
    setMemoryMb('')
    setExpirationEnabled(true)
    setExpiresAt(dayjs().add(defaultExpMinutes, 'minute'))
    setSseEvents([])
    setSseError(false)
  }

  async function handleRun() {
    if (!selectedRepo) return notify('Please select a repository.', 'warning')
    if (!selectedTag) return notify('Please select a tag.', 'warning')

    const name = containerName ? ` as "${containerName}"` : ''
    const accepted = await confirm(`Run container from ${selectedRepo}:${selectedTag}${name}?`)
    if (!accepted) return

    setRunning(true)
    setSseEvents([])
    setSseError(false)

    try {
      const envList = envVars
        .filter((e) => e.key.trim())
        .map((e) => `${e.key.trim()}=${e.value.trim()}`)

      const parsedMemory = memoryMb ? parseInt(memoryMb, 10) : null

      const ticket = await prepareRunContainer({
        repository: selectedRepo,
        tag: selectedTag,
        containerName,
        envVars: envList,
        expiresAt: expirationEnabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null,
        memoryMb: parsedMemory && !isNaN(parsedMemory) ? parsedMemory : null,
      })

      cleanupSse.current = streamRunContainer(
        ticket,
        (event) => setSseEvents((prev) => [...prev, event]),
        () => {
          setTimeout(() => {
            setRunning(false)
            resetForm()
            onClose()
            onCreated()
            notify('Container started successfully.', 'success')
          }, 1500)
        },
        () => {
          setRunning(false)
          setSseError(true)
        },
      )
    } catch {
      setRunning(false)
      notify('An unexpected error occurred.', 'error')
    }
  }

  function handleClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    setRunning(false)
    resetForm()
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ bgcolor: 'primary.main', color: 'white', display: 'flex', alignItems: 'center' }}>
        <Add sx={{ mr: 1 }} /> New Container
        <IconButton onClick={handleClose} sx={{ ml: 'auto', color: 'white' }}>
          <Close />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        {running || sseEvents.length > 0 ? (
          <OperationProgress events={sseEvents} />
        ) : (
          <>
            {/* Repository + Tag */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 4 }}>
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
              <Grid size={{ xs: 12, md: 8 }}>
                <Autocomplete
                  options={allTags}
                  value={selectedTag}
                  onChange={(_e, value) => setSelectedTag(value)}
                  disabled={!selectedRepo}
                  loading={tagsLoading}
                  noOptionsText={!selectedRepo ? 'Select a repository first...' : 'No tags found'}
                  slotProps={{ listbox: { style: { maxHeight: 7 * 36 } } }}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Tag"
                      placeholder="Type to filter tags..."
                      size="small"
                      slotProps={{
                        input: {
                          ...params.InputProps,
                          endAdornment: (
                            <>
                              {tagsLoading ? <CircularProgress size={20} /> : null}
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

            {/* Container name + Memory */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField
                  fullWidth
                  label="Container Name"
                  placeholder="e.g. my-app-container (optional)"
                  value={containerName}
                  onChange={(e) => setContainerName(e.target.value)}
                  size="small"
                />
              </Grid>
              {memoryEnabled && (
                <Grid size={{ xs: 12, md: 6 }}>
                  <TextField
                    fullWidth
                    label="Memory Limit (MB)"
                    placeholder="e.g. 512 (optional)"
                    value={memoryMb}
                    onChange={(e) => setMemoryMb(e.target.value.replace(/\D/g, ''))}
                    size="small"
                    type="text"
                    helperText="Leave empty for no limit"
                    slotProps={{
                      input: {
                        startAdornment: <Memory fontSize="small" sx={{ mr: 1, color: 'text.secondary' }} />,
                      },
                    }}
                  />
                </Grid>
              )}
            </Grid>

            {/* Expiration */}
            <Grid container spacing={2} sx={{ mb: 3, alignItems: 'center' }}>
              <Grid size={{ xs: 12, md: 3 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={expirationEnabled}
                      onChange={(e) => setExpirationEnabled(e.target.checked)}
                    />
                  }
                  label={
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Timer fontSize="small" /> Auto-expire
                    </Box>
                  }
                />
              </Grid>
              {expirationEnabled && (
                <>
                  <Grid size={{ xs: 12, md: 4 }}>
                    <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                      {[
                        { label: '2 Hours', amount: 2, unit: 'hour' as const },
                        { label: '1 Day', amount: 1, unit: 'day' as const },
                        { label: '3 Days', amount: 3, unit: 'day' as const },
                        { label: '1 Week', amount: 7, unit: 'day' as const },
                      ].map((opt) => (
                        <Chip
                          key={opt.label}
                          label={opt.label}
                          onClick={() => setExpiresAt(dayjs().add(opt.amount, opt.unit))}
                          color={expiresAt && expiresAt.isSame(dayjs().add(opt.amount, opt.unit), 'minute') ? 'primary' : 'default'}
                          variant={expiresAt && expiresAt.isSame(dayjs().add(opt.amount, opt.unit), 'minute') ? 'filled' : 'outlined'}
                          clickable
                        />
                      ))}
                    </Box>
                  </Grid>
                  <Grid size={{ xs: 12, md: 5 }}>
                    <MobileDateTimePicker
                      label="Expires at"
                      value={expiresAt}
                      onChange={(v) => setExpiresAt(v)}
                      minDateTime={dayjs()}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          size: 'small',
                          helperText: 'Container will be stopped and removed at this time',
                        },
                      }}
                    />
                  </Grid>
                </>
              )}
            </Grid>

            {/* Environment Variables */}
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
              Environment Variables
            </Typography>
            {envVars.map((env, i) => (
              <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                <TextField
                  size="small"
                  placeholder="KEY"
                  value={env.key}
                  onChange={(e) => updateEnvVar(i, 'key', e.target.value)}
                  sx={{ flex: 1 }}
                />
                <Typography variant="body1">=</Typography>
                <TextField
                  size="small"
                  placeholder="VALUE"
                  value={env.value}
                  onChange={(e) => updateEnvVar(i, 'value', e.target.value)}
                  sx={{ flex: 1 }}
                />
                <IconButton size="small" color="error" onClick={() => removeEnvVar(i)}>
                  <Delete fontSize="small" />
                </IconButton>
              </Box>
            ))}
            <Button size="small" startIcon={<Add />} onClick={addEnvVar}>
              Add Variable
            </Button>
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
          <Button onClick={handleClose} color="inherit">Cancel</Button>
        ) : (
          <>
            <Button onClick={handleClose} color="inherit">Cancel</Button>
            <Button
              variant="contained"
              color="success"
              onClick={handleRun}
              disabled={running}
              startIcon={<PlayArrow />}
            >
              Run Container
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}
