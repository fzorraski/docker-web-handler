import { useState, useEffect, useCallback, useMemo } from 'react'
import {
  Box, Paper, Typography, Switch, TextField, IconButton, Tooltip, Chip,
  CircularProgress, Divider,
} from '@mui/material'
import { Check, RestartAlt, InfoOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { listSettings, updateSettings, resetSetting, type RuntimeSetting } from '../../services/settingsService'
import { useNotification } from '../NotificationProvider'
import { buildSettingGroups } from '../../utils/settingsTree'

/** String settings that must be an absolute directory inside a container; other strings only need to be non-empty. */
const CONTAINER_PATH_KEYS = new Set(['terminalImageUploadPath'])
/** String settings that must keep the {path} placeholder. */
const PATH_TEMPLATE_KEYS = new Set(['terminalImagePathTemplate'])

/** Mirrors InputValidator.validateContainerPath: absolute, not the root, no '..', safe characters, at most 4096 chars. */
export function isContainerPath(value: string): boolean {
  const v = value.trim()
  return v !== '/' && v.length <= 4096 && !v.includes('..') && /^\/[A-Za-z0-9/_.-]+$/.test(v)
}

export default function SettingsTab() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const [settings, setSettings] = useState<RuntimeSetting[]>([])
  const [loading, setLoading] = useState(true)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  // local drafts for integer fields, keyed by setting key
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const groups = useMemo(() => buildSettingGroups(settings), [settings])

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

  async function saveValue(key: string, value: boolean | number | string) {
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
      {groups.map((group) => {
        const headerId = `settings-group-${group.category}`
        return (
      <Box key={group.category} component="section" aria-labelledby={headerId} sx={{ display: 'flex', flexDirection: 'column', mb: 3, '&:last-of-type': { mb: 0 } }}>
        <Typography id={headerId} variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
          {t(`settings.categories.${group.category}` as never, { defaultValue: group.category })}
        </Typography>
        {group.rows.map(({ setting: s, depth, inactive: reason }, i) => {
          const noteId = reason ? `settings-note-${s.key}` : undefined
          const draft = drafts[s.key]
          const draftValue = draft !== undefined ? draft : String(s.value)
          const changed = s.type !== 'boolean' && draft !== undefined && draft !== String(s.value)
          // 0 is meaningful for some settings (e.g. audit retention = keep forever); the backend enforces per-key minimums
          const draftValid = s.type === 'string'
            ? (CONTAINER_PATH_KEYS.has(s.key)
                ? isContainerPath(draftValue)
                : PATH_TEMPLATE_KEYS.has(s.key)
                  ? (draftValue.trim() === '' || draftValue.includes('{path}')) && draftValue.length <= 200
                  : draftValue.trim().length > 0)
            : /^\d+$/.test(draftValue) && Number(draftValue) >= 0
          // templates keep their spaces on purpose (e.g. "{path}" followed by a word)
          const parsedDraft = s.type === 'string'
            ? (PATH_TEMPLATE_KEYS.has(s.key) ? (draftValue.trim() === '' ? '' : draftValue) : draftValue.trim())
            : Number(draftValue)
          const busy = savingKey === s.key
          return (
            <Box key={s.key}>
              {i > 0 && <Divider />}
              <Box
                sx={{
                  display: 'flex', alignItems: 'center', gap: 2, py: 1.5, flexWrap: 'wrap',
                  pl: depth * 3,
                  // Dimmed, never disabled: an admin can prepare values before enabling the parent.
                  ...(reason && { opacity: 0.6, '&:focus-within': { opacity: 1 } }),
                }}
              >
                <Box sx={{ flex: 1, minWidth: 220 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {t(`settings.keys.${s.key}` as never)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" display="block">
                    {t('settings.default', { value: String(s.defaultValue) })}
                  </Typography>
                  {reason && (
                    <Typography id={noteId} variant="caption" color="text.secondary" sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.25 }}>
                      <InfoOutlined sx={{ fontSize: 14 }} />
                      {reason.kind === 'setting'
                        ? t('settings.inactiveUntil', { parent: t(`settings.keys.${reason.parentKey}` as never) })
                        : t('settings.inactiveProperty', { property: reason.property })}
                    </Typography>
                  )}
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
                    inputProps={{ 'aria-describedby': noteId }}
                  />
                ) : (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    <TextField
                      value={draftValue}
                      onChange={(e) => setDrafts(prev => ({ ...prev, [s.key]: e.target.value }))}
                      size="small"
                      sx={{ width: s.type === 'string' ? 260 : 100 }}
                      disabled={busy}
                      error={changed && !draftValid}
                      slotProps={{ htmlInput: { 'aria-describedby': noteId } }}
                      onKeyDown={(e) => { if (e.key === 'Enter' && changed && draftValid && !busy) saveValue(s.key, parsedDraft) }}
                    />
                    <Tooltip title={t('common.save')}>
                      <span>
                        <IconButton
                          size="small"
                          color="success"
                          disabled={!changed || !draftValid || busy}
                          onClick={() => saveValue(s.key, parsedDraft)}
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
        )
      })}
    </Paper>
  )
}
