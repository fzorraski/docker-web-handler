import { useState, useEffect, useCallback } from 'react'
import {
  Box, Paper, Typography, Switch, TextField, IconButton, Tooltip, Chip,
  CircularProgress, Divider,
} from '@mui/material'
import { Check, RestartAlt } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { listSettings, updateSettings, resetSetting, type RuntimeSetting } from '../../services/settingsService'
import { useNotification } from '../NotificationProvider'

export default function SettingsTab() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const [settings, setSettings] = useState<RuntimeSetting[]>([])
  const [loading, setLoading] = useState(true)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  // local drafts for integer fields, keyed by setting key
  const [drafts, setDrafts] = useState<Record<string, string>>({})

  const load = useCallback(async () => {
    try {
      setSettings(await listSettings())
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setLoading(false)
    }
  }, [notify, t])

  useEffect(() => { load() }, [load])

  function applyResult(updated: RuntimeSetting[]) {
    setSettings(updated)
    setDrafts({})
  }

  async function saveValue(key: string, value: boolean | number) {
    setSavingKey(key)
    try {
      applyResult(await updateSettings({ [key]: value }))
      notify(t('settings.saved'), 'success')
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setSavingKey(null)
    }
  }

  async function handleReset(key: string) {
    setSavingKey(key)
    try {
      applyResult(await resetSetting(key))
      notify(t('settings.resetDone'), 'success')
    } catch (e) {
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setSavingKey(null)
    }
  }

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress size={28} />
      </Box>
    )
  }

  return (
    <Paper elevation={2} sx={{ borderRadius: 2, p: 3 }}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('settings.description')}
      </Typography>
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        {settings.map((s, i) => {
          const draft = drafts[s.key]
          const draftValue = draft !== undefined ? draft : String(s.value)
          const changed = s.type === 'integer' && draft !== undefined && draft !== String(s.value)
          // 0 is meaningful for some settings (e.g. audit retention = keep forever); the backend enforces per-key minimums
          const draftValid = /^\d+$/.test(draftValue) && Number(draftValue) >= 0
          const busy = savingKey === s.key
          return (
            <Box key={s.key}>
              {i > 0 && <Divider />}
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, py: 1.5, flexWrap: 'wrap' }}>
                <Box sx={{ flex: 1, minWidth: 220 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {t(`settings.keys.${s.key}` as never)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('settings.default', { value: String(s.defaultValue) })}
                  </Typography>
                </Box>
                {s.overridden && (
                  <Chip label={t('settings.overridden')} size="small" color="warning" variant="outlined" />
                )}
                {s.type === 'boolean' ? (
                  <Switch
                    checked={s.value === true}
                    onChange={(e) => saveValue(s.key, e.target.checked)}
                    disabled={busy}
                    size="small"
                  />
                ) : (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    <TextField
                      value={draftValue}
                      onChange={(e) => setDrafts(prev => ({ ...prev, [s.key]: e.target.value }))}
                      size="small"
                      sx={{ width: 100 }}
                      disabled={busy}
                      error={changed && !draftValid}
                      onKeyDown={(e) => { if (e.key === 'Enter' && changed && draftValid && !busy) saveValue(s.key, Number(draftValue)) }}
                    />
                    <Tooltip title={t('common.save')}>
                      <span>
                        <IconButton
                          size="small"
                          color="success"
                          disabled={!changed || !draftValid || busy}
                          onClick={() => saveValue(s.key, Number(draftValue))}
                        >
                          {busy ? <CircularProgress size={16} /> : <Check fontSize="small" />}
                        </IconButton>
                      </span>
                    </Tooltip>
                  </Box>
                )}
                <Tooltip title={t('settings.resetToDefault')}>
                  <span>
                    <IconButton
                      size="small"
                      color="warning"
                      disabled={!s.overridden || busy}
                      onClick={() => handleReset(s.key)}
                    >
                      <RestartAlt fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              </Box>
            </Box>
          )
        })}
      </Box>
    </Paper>
  )
}
