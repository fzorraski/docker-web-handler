import { useState, useEffect } from 'react'
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
} from '@mui/material'
import { Add, Close, Delete, PlayArrow } from '@mui/icons-material'
import {
  getAllowedRepositories,
  getRepositoryTags,
  runContainer,
} from '../services/containerService'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: () => void
}

interface EnvVar {
  key: string
  value: string
}

export default function NewContainerModal({ open, onClose, onCreated }: Props) {
  const [repositories, setRepositories] = useState<string[]>([])
  const [selectedRepo, setSelectedRepo] = useState('')
  const [allTags, setAllTags] = useState<string[]>([])
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const [tagsLoading, setTagsLoading] = useState(false)
  const [containerName, setContainerName] = useState('')
  const [envVars, setEnvVars] = useState<EnvVar[]>([])
  const [running, setRunning] = useState(false)

  useEffect(() => {
    getAllowedRepositories()
      .then((repos) => {
        setRepositories(repos)
        if (repos.length > 0) setSelectedRepo(repos[0])
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!selectedRepo) {
      setAllTags([])
      setSelectedTag(null)
      return
    }
    setTagsLoading(true)
    setSelectedTag(null)
    getRepositoryTags(selectedRepo)
      .then((res) => {
        if (res.state === 1 && res.tags) setAllTags(res.tags)
        else {
          setAllTags([])
          if (res.message) alert('Error: ' + res.message)
        }
      })
      .catch(() => setAllTags([]))
      .finally(() => setTagsLoading(false))
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
  }

  async function handleRun() {
    if (!selectedRepo) return alert('Please select a repository.')
    if (!selectedTag) return alert('Please select a tag.')

    const name = containerName ? ` as "${containerName}"` : ''
    if (!confirm(`Run container from ${selectedRepo}:${selectedTag}${name} ?`)) return

    setRunning(true)
    try {
      const envList = envVars
        .filter((e) => e.key.trim())
        .map((e) => `${e.key.trim()}=${e.value.trim()}`)

      const res = await runContainer(selectedRepo, selectedTag!, containerName, envList)
      if (res.state === 1) {
        alert(res.message)
        resetForm()
        onClose()
        onCreated()
      } else {
        alert('Error: ' + res.message)
      }
    } catch (err) {
      alert('Error: ' + err)
    } finally {
      setRunning(false)
    }
  }

  function handleClose() {
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

        {/* Container name */}
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
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={handleClose} color="inherit">Cancel</Button>
        <Button
          variant="contained"
          color="success"
          onClick={handleRun}
          disabled={running}
          startIcon={running ? <CircularProgress size={18} color="inherit" /> : <PlayArrow />}
        >
          {running ? 'Running...' : 'Run Container'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
