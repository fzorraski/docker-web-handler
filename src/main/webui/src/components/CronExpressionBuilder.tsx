import { useState, useEffect, useMemo } from 'react'
import {
  Box, TextField, Typography, Chip, Collapse, Button,
} from '@mui/material'
import { ExpandMore, ExpandLess } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { cronToHuman, getNextOccurrences } from '../utils/cronFormat'

interface Props {
  value: string
  onChange: (cron: string) => void
}

const PRESETS = [
  { labelKey: 'schedules.cron.everyHour', cron: '0 * * * *' },
  { labelKey: 'schedules.cron.dailyAt9', cron: '0 9 * * *' },
  { labelKey: 'schedules.cron.weekdaysAt8', cron: '0 8 * * 1-5' },
  { labelKey: 'schedules.cron.weeklyMonday', cron: '0 9 * * 1' },
  { labelKey: 'schedules.cron.everyMinute', cron: '* * * * *' },
]

export default function CronExpressionBuilder({ value, onChange }: Props) {
  const { t } = useTranslation()
  const [advanced, setAdvanced] = useState(false)
  const [rawInput, setRawInput] = useState(value)

  useEffect(() => {
    setRawInput(value)
  }, [value])

  const humanReadable = useMemo(() => cronToHuman(value), [value])
  const nextOccurrences = useMemo(() => getNextOccurrences(value, 5), [value])

  function handlePreset(cron: string) {
    setRawInput(cron)
    onChange(cron)
  }

  function handleRawChange(val: string) {
    setRawInput(val)
    const parts = val.trim().split(/\s+/)
    if (parts.length === 5) {
      onChange(val.trim())
    }
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
        {PRESETS.map((p) => (
          <Chip
            key={p.cron}
            label={t(p.labelKey as never)}
            onClick={() => handlePreset(p.cron)}
            color={value === p.cron ? 'primary' : 'default'}
            variant={value === p.cron ? 'filled' : 'outlined'}
            clickable
          />
        ))}
      </Box>

      <Button
        size="small"
        onClick={() => setAdvanced(!advanced)}
        endIcon={advanced ? <ExpandLess /> : <ExpandMore />}
        sx={{ mb: 1 }}
      >
        {t('schedules.cron.custom')}
      </Button>

      <Collapse in={advanced}>
        <TextField
          fullWidth
          size="small"
          label={t('schedules.cron.expression')}
          placeholder="* * * * *"
          value={rawInput}
          onChange={(e) => handleRawChange(e.target.value)}
          helperText={t('schedules.cron.help')}
          sx={{ mb: 1 }}
        />
      </Collapse>

      {value && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="body2" color="primary" fontWeight={600}>
            {humanReadable}
          </Typography>
          {nextOccurrences.length > 0 && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="caption" color="text.secondary">
                {t('schedules.cron.nextOccurrences')}:
              </Typography>
              {nextOccurrences.map((d, i) => (
                <Typography key={i} variant="caption" display="block" sx={{ ml: 1, fontFamily: "'JetBrains Mono', monospace" }}>
                  {d.toLocaleString()}
                </Typography>
              ))}
            </Box>
          )}
        </Box>
      )}
    </Box>
  )
}
