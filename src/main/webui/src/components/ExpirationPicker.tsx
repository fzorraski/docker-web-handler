import { Box, Chip, FormControlLabel, Grid, Switch } from '@mui/material'
import { MobileDateTimePicker } from '@mui/x-date-pickers/MobileDateTimePicker'
import { Timer } from '@mui/icons-material'
import dayjs, { type Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'

interface Preset {
  labelKey: string
  amount: number
  unit: 'hour' | 'day'
}

interface Props {
  enabled: boolean
  onEnabledChange: (enabled: boolean) => void
  value: Dayjs | null
  onChange: (value: Dayjs | null) => void
  switchLabel?: string
  helperText?: string
  presets?: Preset[]
  disabled?: boolean
  maxDateTime?: Dayjs
}

const DEFAULT_PRESETS: Preset[] = [
  { labelKey: 'newContainer.presets.2hours', amount: 2, unit: 'hour' },
  { labelKey: 'newContainer.presets.1day', amount: 1, unit: 'day' },
  { labelKey: 'newContainer.presets.3days', amount: 3, unit: 'day' },
  { labelKey: 'newContainer.presets.1week', amount: 7, unit: 'day' },
]

export default function ExpirationPicker({
  enabled,
  onEnabledChange,
  value,
  onChange,
  switchLabel,
  helperText,
  presets = DEFAULT_PRESETS,
  disabled,
  maxDateTime,
}: Props) {
  const { t } = useTranslation()

  return (
    <Grid container spacing={2} sx={{ alignItems: 'center' }}>
      <Grid size={{ xs: 12, md: 3 }}>
        <FormControlLabel
          control={
            <Switch
              checked={enabled}
              onChange={(e) => onEnabledChange(e.target.checked)}
              disabled={disabled}
            />
          }
          label={
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              <Timer fontSize="small" /> {switchLabel ?? t('newContainer.autoExpire')}
            </Box>
          }
        />
      </Grid>
      {enabled && (
        <>
          <Grid size={{ xs: 12, md: 4 }}>
            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
              {presets.map((opt) => {
                const target = dayjs().add(opt.amount, opt.unit)
                const exceedsMax = maxDateTime && target.isAfter(maxDateTime)
                return (
                  <Chip
                    key={opt.labelKey}
                    label={t(opt.labelKey as never)}
                    onClick={exceedsMax ? undefined : () => onChange(target)}
                    color={value && value.isSame(target, 'minute') ? 'primary' : 'default'}
                    variant={value && value.isSame(target, 'minute') ? 'filled' : 'outlined'}
                    clickable={!exceedsMax}
                    disabled={!!exceedsMax}
                  />
                )
              })}
            </Box>
          </Grid>
          <Grid size={{ xs: 12, md: 5 }}>
            <MobileDateTimePicker
              label={t('newContainer.expiresAt')}
              value={value}
              onChange={onChange}
              minDateTime={dayjs()}
              maxDateTime={maxDateTime}
              slotProps={{
                textField: {
                  fullWidth: true,
                  size: 'small',
                  helperText: helperText ?? t('newContainer.expiresHelperText'),
                },
              }}
            />
          </Grid>
        </>
      )}
    </Grid>
  )
}
