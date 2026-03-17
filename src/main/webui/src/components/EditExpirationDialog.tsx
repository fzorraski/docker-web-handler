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
} from '@mui/material'
import { Timer } from '@mui/icons-material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import dayjs, { type Dayjs } from 'dayjs'

interface Props {
  open: boolean
  title: string
  currentExpiresAt?: string | null
  onClose: () => void
  onSave: (expiresAt: string | null, password: string) => Promise<{ success: boolean; error?: string }>
}

export default function EditExpirationDialog({ open, title, currentExpiresAt, onClose, onSave }: Props) {
  const [enabled, setEnabled] = useState(false)
  const [expiresAt, setExpiresAt] = useState<Dayjs | null>(dayjs().add(7, 'day'))
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (open) {
      if (currentExpiresAt) {
        setEnabled(true)
        setExpiresAt(dayjs(currentExpiresAt))
      } else {
        setEnabled(false)
        setExpiresAt(dayjs().add(7, 'day'))
      }
      setPassword('')
      setError('')
      setSaving(false)
    }
  }, [open, currentExpiresAt])

  async function handleSave() {
    if (!password) { setError('Password is required.'); return }
    setSaving(true)
    setError('')
    const value = enabled && expiresAt ? expiresAt.format('YYYY-MM-DDTHH:mm:ss') : null
    const result = await onSave(value, password)
    setSaving(false)
    if (result.success) {
      onClose()
    } else {
      setError(result.error || 'Failed to update.')
    }
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Timer /> Edit Expiration - {title}
      </DialogTitle>
      <DialogContent dividers sx={{ pt: 3 }}>
        <TextField
          fullWidth
          type="password"
          label="Operations Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          size="small"
          sx={{ mb: 3 }}
          autoComplete="off"
          error={!!error}
          helperText={error}
        />

        <FormControlLabel
          control={
            <Switch
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />
          }
          label="Enable expiration"
          sx={{ mb: 2, display: 'block' }}
        />

        {enabled && (
          <MobileDateTimePicker
            label="Expires at"
            value={expiresAt}
            onChange={(v) => setExpiresAt(v)}
            minDateTime={dayjs()}
            slotProps={{
              textField: {
                fullWidth: true,
                size: 'small',
                helperText: 'File will be automatically deleted at this time',
              },
            }}
          />
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>Cancel</Button>
        <Button
          variant="contained"
          onClick={handleSave}
          disabled={saving || !password}
          startIcon={saving ? <CircularProgress size={20} /> : <Timer />}
        >
          {saving ? 'Saving...' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
