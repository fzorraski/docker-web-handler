import { useState, useEffect, useCallback, lazy, Suspense } from 'react'
import {
  Box, TextField, MenuItem, IconButton, Tooltip, Button, Typography,
  CircularProgress, Alert, ToggleButton, ToggleButtonGroup, Tabs, Tab,
} from '@mui/material'
import { Refresh, Download } from '@mui/icons-material'
import { MobileDatePicker } from '@mui/x-date-pickers/MobileDatePicker'
import dayjs, { type Dayjs } from 'dayjs'
import { useTranslation } from 'react-i18next'
import { useNotification } from '../NotificationProvider'
import { useTenants } from '../../hooks/useTenants'
import { toCsv, downloadCsv } from '../../utils/csv'
import { ACTIVITY_CATEGORIES } from '../../utils/activityCategory'
import {
  activityOverview, activitySummaryActive, type ActivityOverview,
} from '../../services/activityService'

const ActivityOverviewView = lazy(() => import('./activity/ActivityOverviewView'))
const ActivityPeopleView = lazy(() => import('./activity/ActivityPeopleView'))
const ActivityActionsView = lazy(() => import('./activity/ActivityActionsView'))
const ActivityDetailsView = lazy(() => import('./activity/ActivityDetailsView'))

type View = 'overview' | 'people' | 'actions' | 'details'

/** Preset windows, in days, ending today. */
const PRESETS = [7, 30, 90] as const

/** Picker value -> ISO date (empty/invalid -> undefined). */
function toIsoDate(value: Dayjs | null): string | undefined {
  return value && value.isValid() ? value.format('YYYY-MM-DD') : undefined
}

export default function ActivityTab() {
  const { t } = useTranslation()
  const { notify } = useNotification()
  const tenants = useTenants()

  const [view, setView] = useState<View>('overview')
  const [overview, setOverview] = useState<ActivityOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [active, setActive] = useState(true)

  // ending today, not yesterday: the report falls through to the audit trail
  // past the roll-up's watermark, so today is real data rather than a gap
  const [from, setFrom] = useState<Dayjs | null>(dayjs().subtract(29, 'day'))
  const [to, setTo] = useState<Dayjs | null>(dayjs())
  const [tenant, setTenant] = useState('')

  const days = from && to && from.isValid() && to.isValid() ? to.diff(from, 'day') + 1 : 0
  const preset = PRESETS.find(p => p === days && to?.isSame(dayjs(), 'day')) ?? null

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setOverview(await activityOverview({
        from: toIsoDate(from),
        to: toIsoDate(to),
        tenant: tenant || undefined,
      }))
    } catch (e) {
      setOverview(null)
      notify(e instanceof Error ? e.message : t('common.unexpectedError'), 'error')
    } finally {
      setLoading(false)
    }
  }, [from, to, tenant, notify, t])

  useEffect(() => { load() }, [load])
  useEffect(() => { activitySummaryActive().then(setActive).catch(() => setActive(true)) }, [])

  function applyPreset(count: number) {
    setFrom(dayjs().subtract(count - 1, 'day'))
    setTo(dayjs())
  }

  function exportCsv() {
    if (!overview) return
    const stamp = `${overview.from}_${overview.to}`
    if (view === 'people') {
      downloadCsv(`activity-ranking-${stamp}.csv`, toCsv(
        [t('activity.actor'), t('activity.leaderboard.score'), t('activity.leaderboard.total'),
          t('activity.leaderboard.auth'), t('activity.leaderboard.failures'),
          t('activity.leaderboard.topAction'), t('activity.leaderboard.activeDays'),
          t('activity.leaderboard.lastActive')],
        overview.ranking.map(rank => [rank.actor, rank.operational, rank.total, rank.auth,
          rank.failures, rank.topAction ?? '', rank.activeDays, rank.lastActive ?? '']),
      ))
      return
    }
    if (view === 'actions') {
      downloadCsv(`activity-actions-${stamp}.csv`, toCsv(
        [t('activity.action'), t('activity.mix.title'), t('activity.count'),
          t('activity.actionsView.users')],
        overview.topActions.map(action => [action.action, action.category, action.count, action.users]),
      ))
      return
    }
    downloadCsv(`activity-daily-${stamp}.csv`, toCsv(
      [t('activity.day'), t('activity.count'), ...ACTIVITY_CATEGORIES],
      overview.daily.map(point => [point.day, point.total,
        ...ACTIVITY_CATEGORIES.map(category => point.byCategory[category] ?? 0)]),
    ))
  }

  const dashboard = view !== 'details'

  return (
    <Box>
      {!active && <Alert severity="info" sx={{ mb: 2 }}>{t('activity.inactive')}</Alert>}

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mb: 1.5, alignItems: 'center' }}>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={preset}
          onChange={(_, value: number | null) => value && applyPreset(value)}
        >
          <ToggleButton value={7}>{t('activity.ranges.last7')}</ToggleButton>
          <ToggleButton value={30}>{t('activity.ranges.last30')}</ToggleButton>
          <ToggleButton value={90}>{t('activity.ranges.last90')}</ToggleButton>
        </ToggleButtonGroup>
        <MobileDatePicker
          label={t('activity.from')}
          value={from}
          onChange={setFrom}
          slotProps={{
            textField: { size: 'small', sx: { width: 165 } },
            actionBar: { actions: ['cancel', 'accept'] },
          }}
        />
        <MobileDatePicker
          label={t('activity.to')}
          value={to}
          onChange={setTo}
          slotProps={{
            textField: { size: 'small', sx: { width: 165 } },
            actionBar: { actions: ['cancel', 'accept'] },
          }}
        />
        {tenants.size > 0 && (
          <TextField
            size="small"
            select
            label={t('activity.tenant')}
            value={tenant}
            onChange={(e) => setTenant(e.target.value)}
            sx={{ minWidth: 170 }}
          >
            <MenuItem value="">{t('activity.allTenants')}</MenuItem>
            {[...tenants.values()].map(tn => (
              <MenuItem key={tn.id} value={tn.id}>{tn.name}</MenuItem>
            ))}
          </TextField>
        )}
        <Tooltip title={t('activity.refresh')}>
          <IconButton onClick={load} size="small"><Refresh /></IconButton>
        </Tooltip>
        {dashboard && (
          <Button
            size="small"
            startIcon={<Download />}
            onClick={exportCsv}
            disabled={!overview}
          >
            {t('activity.export')}
          </Button>
        )}
        {loading && dashboard && <CircularProgress size={20} />}
      </Box>

      {overview && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          {overview.summarisedThrough
            ? t('activity.liveFrom', { date: dayjs(overview.summarisedThrough).format('DD/MM/YYYY') })
            : t('activity.liveAll')}
        </Typography>
      )}

      <Tabs value={view} onChange={(_, value: View) => setView(value)} sx={{ mb: 2 }}>
        <Tab value="overview" label={t('activity.views.overview')} />
        <Tab value="people" label={t('activity.views.people')} />
        <Tab value="actions" label={t('activity.views.actions')} />
        <Tab value="details" label={t('activity.views.details')} />
      </Tabs>

      <Suspense fallback={<CircularProgress size={28} sx={{ display: 'block', mx: 'auto', my: 4 }} />}>
        {view === 'details' && <ActivityDetailsView from={toIsoDate(from)} to={toIsoDate(to)} />}
        {dashboard && !overview && !loading && (
          <Typography variant="body2" color="text.secondary" sx={{ py: 6, textAlign: 'center' }}>
            {t('activity.empty')}
          </Typography>
        )}
        {dashboard && overview && (
          <>
            {view === 'overview' && <ActivityOverviewView overview={overview} days={days} />}
            {view === 'people' && <ActivityPeopleView overview={overview} days={days} />}
            {view === 'actions' && <ActivityActionsView overview={overview} tenants={tenants} />}
          </>
        )}
      </Suspense>
    </Box>
  )
}
