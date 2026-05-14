import { useEffect, useRef, useMemo, useState } from 'react'
import {
  Box,
  Stepper,
  Step,
  Collapse,
  StepLabel,
  Typography,
  LinearProgress,
  Paper,
  CircularProgress,
  alpha,
  useTheme,
} from '@mui/material'
import {
  CheckCircle,
  RadioButtonUnchecked,
  BlockOutlined,
  ExpandMore,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { ContainerEvent } from '../services/sseService'

const RUN_STEPS = ['Validating', 'Pulling', 'Creating', 'Starting']
const REMOVE_STEPS = ['Cancelling', 'Stopping', 'Removing']
const REMOVE_WITH_DB_STEPS = ['Cancelling', 'Stopping', 'Removing', 'Dropping Database']
const REMOVE_IMAGE_STEPS = ['Removing']
const PRUNE_IMAGES_STEPS = ['Validating', 'Pruning']
const RESTORE_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring']
const RUN_WITH_RESTORE_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Starting']
const RESTORE_WITH_SCRIPTS_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts']
const RUN_WITH_RESTORE_AND_SCRIPTS_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts', 'Starting']
const RESTORE_WITH_MIGRATION_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring', 'Running Migration']
const RESTORE_WITH_SCRIPTS_AND_MIGRATION_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts', 'Running Migration']
const RUN_WITH_RESTORE_AND_MIGRATION_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Running Migration', 'Starting']
const RUN_WITH_RESTORE_SCRIPTS_AND_MIGRATION_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts', 'Running Migration', 'Starting']
const RUN_WITH_MIGRATION_STEPS = ['Validating', 'Pulling', 'Creating', 'Running Migration', 'Starting']
const MIGRATION_STEPS = ['Validating', 'Running Migration']
const UPGRADE_STEPS = ['Validating', 'Inspecting', 'Pulling', 'Stopping', 'Removing', 'Creating', 'Starting']
const UPGRADE_WITH_MIGRATION_STEPS = ['Validating', 'Inspecting', 'Pulling', 'Stopping', 'Removing', 'Creating', 'Running Migration', 'Starting']
const SNAPSHOT_STEPS = ['Validating', 'Pulling Image', 'Creating Snapshot', 'Saving']
const LOG_ANALYSIS_STEPS = ['Parsing', 'API Calls', 'Jobs', 'Failures', 'Custom Fields', 'Critical Issues', 'NPE Analysis', 'Exception Analysis']
const LOG_ANALYSIS_PARALLEL_STEPS = ['API Calls', 'Jobs', 'Failures', 'Custom Fields', 'Critical Issues', 'NPE Analysis', 'Exception Analysis']
const LOG_COMPOSE_STEPS = ['Merging', 'Critical Issues', 'NPE Analysis', 'Exception Analysis']
const LOG_COMPOSE_PARALLEL_STEPS = ['Critical Issues', 'NPE Analysis', 'Exception Analysis']

type StepState = 'pending' | 'active' | 'completed' | 'skipped'

interface Props {
  events: ContainerEvent[]
  steps?: string[]
}

export { RUN_STEPS, REMOVE_STEPS, REMOVE_WITH_DB_STEPS, REMOVE_IMAGE_STEPS, PRUNE_IMAGES_STEPS, RESTORE_STEPS, RUN_WITH_RESTORE_STEPS, RESTORE_WITH_SCRIPTS_STEPS, RUN_WITH_RESTORE_AND_SCRIPTS_STEPS, RESTORE_WITH_MIGRATION_STEPS, RESTORE_WITH_SCRIPTS_AND_MIGRATION_STEPS, RUN_WITH_RESTORE_AND_MIGRATION_STEPS, RUN_WITH_RESTORE_SCRIPTS_AND_MIGRATION_STEPS, RUN_WITH_MIGRATION_STEPS, MIGRATION_STEPS, UPGRADE_STEPS, UPGRADE_WITH_MIGRATION_STEPS, SNAPSHOT_STEPS, LOG_ANALYSIS_STEPS, LOG_COMPOSE_STEPS }

function buildStepStates(events: ContainerEvent[], steps: string[]): StepState[] {
  const states: StepState[] = steps.map(() => 'pending')
  const eventCounts = new Map<string, number>()

  for (const event of events) {
    if (event.type === 'SUCCESS') {
      for (let i = 0; i < states.length; i++) {
        if (states[i] !== 'skipped') states[i] = 'completed'
      }
      break
    }
    if (event.type === 'ERROR') {
      for (let i = 0; i < states.length; i++) {
        if (states[i] === 'active') states[i] = 'pending'
      }
      break
    }
    const idx = steps.findIndex((s) => s === event.step)
    if (idx === -1) continue

    if (event.message === 'Skipped') {
      states[idx] = 'skipped'
    } else if (event.type === 'PROGRESS') {
      if (states[idx] === 'pending') states[idx] = 'active'
    } else {
      const count = (eventCounts.get(event.step) ?? 0) + 1
      eventCounts.set(event.step, count)
      states[idx] = count >= 2 ? 'completed' : 'active'
    }
  }
  return states
}

// ── Phase tile for the parallel grid ────────────────────────────────────────

function PhaseTile({ label, state }: { label: string, state: StepState }) {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const cfg = {
    pending: {
      icon: <RadioButtonUnchecked sx={{ fontSize: 14, opacity: 0.35 }} />,
      bg: 'transparent',
      border: theme.palette.divider,
      text: 'text.disabled',
      glow: 'none',
    },
    active: {
      icon: <CircularProgress size={14} thickness={5} sx={{ color: theme.palette.primary.main }} />,
      bg: alpha(theme.palette.primary.main, isDark ? 0.08 : 0.05),
      border: alpha(theme.palette.primary.main, 0.5),
      text: 'text.primary',
      glow: `0 0 12px ${alpha(theme.palette.primary.main, 0.25)}`,
    },
    completed: {
      icon: <CheckCircle sx={{ fontSize: 14, color: theme.palette.success.main }} />,
      bg: alpha(theme.palette.success.main, isDark ? 0.07 : 0.04),
      border: alpha(theme.palette.success.main, 0.25),
      text: 'text.primary',
      glow: 'none',
    },
    skipped: {
      icon: <BlockOutlined sx={{ fontSize: 14, opacity: 0.3 }} />,
      bg: 'transparent',
      border: theme.palette.divider,
      text: 'text.disabled',
      glow: 'none',
    },
  }[state]

  return (
    <Box sx={{
      display: 'flex', alignItems: 'center', gap: 0.75,
      pl: 1.25, pr: 1.5, py: 0.625,
      borderRadius: 2,
      border: `1px solid ${cfg.border}`,
      bgcolor: cfg.bg,
      boxShadow: cfg.glow,
      opacity: state === 'pending' || state === 'skipped' ? 0.55 : 1,
      transition: 'all 0.35s cubic-bezier(.4,0,.2,1)',
    }}>
      {cfg.icon}
      <Typography
        variant="caption"
        fontWeight={state === 'active' ? 600 : 500}
        color={cfg.text}
        noWrap
        sx={{
          fontSize: '0.72rem',
          letterSpacing: 0.1,
          ...(state === 'skipped' && { textDecoration: 'line-through' }),
        }}
      >
        {label}
      </Typography>
    </Box>
  )
}

// ── Step indicator pill ─────────────────────────────────────────────────────

function StepPill({ num, label, state }: { num: number, label: string, state: 'pending' | 'active' | 'completed' }) {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  const colors = {
    pending: {
      pill: 'transparent',
      border: theme.palette.divider,
      numBg: alpha(theme.palette.text.disabled, 0.08),
      numColor: 'text.disabled',
      labelColor: 'text.disabled',
    },
    active: {
      pill: alpha(theme.palette.primary.main, isDark ? 0.1 : 0.06),
      border: alpha(theme.palette.primary.main, 0.4),
      numBg: alpha(theme.palette.primary.main, 0.2),
      numColor: 'primary.main',
      labelColor: 'primary.main',
    },
    completed: {
      pill: alpha(theme.palette.success.main, isDark ? 0.08 : 0.04),
      border: alpha(theme.palette.success.main, 0.3),
      numBg: alpha(theme.palette.success.main, 0.2),
      numColor: 'success.main',
      labelColor: 'success.main',
    },
  }[state]

  return (
    <Box sx={{
      display: 'flex', alignItems: 'center', gap: 1,
      px: 1.5, py: 0.625,
      borderRadius: 3,
      border: `1px solid ${colors.border}`,
      bgcolor: colors.pill,
      transition: 'all 0.35s cubic-bezier(.4,0,.2,1)',
    }}>
      <Box sx={{
        width: 22, height: 22, borderRadius: '50%',
        bgcolor: colors.numBg,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        {state === 'completed' ? (
          <CheckCircle sx={{ fontSize: 16, color: colors.numColor }} />
        ) : (
          <Typography sx={{ fontSize: '0.65rem', fontWeight: 700, color: colors.numColor }}>{num}</Typography>
        )}
      </Box>
      <Typography variant="caption" fontWeight={600} color={colors.labelColor} sx={{ fontSize: '0.72rem', letterSpacing: 0.2 }}>
        {label}
      </Typography>
    </Box>
  )
}

// ── Main parallel phase layout ──────────────────────────────────────────────

function ParallelPhaseGrid({ events, steps, firstStep = 'Parsing' }: { events: ContainerEvent[], steps: string[], firstStep?: string }) {
  const { t } = useTranslation()
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const stepTranslations: Record<string, string> = t('steps', { returnObjects: true })
  const allSteps = useMemo(() => [firstStep, ...steps], [firstStep, steps])
  const states = useMemo(() => buildStepStates(events, allSteps), [events, allSteps])

  const parsingState = states[0]
  const parallelStates = states.slice(1)
  const completedCount = parallelStates.filter(s => s === 'completed').length
  const skippedCount = parallelStates.filter(s => s === 'skipped').length
  const totalEnabled = steps.length - skippedCount
  const allParallelDone = completedCount === totalEnabled && totalEnabled > 0
  const isComplete = events.some(e => e.type === 'SUCCESS')
  const isError = events.length > 0 && events[events.length - 1]?.type === 'ERROR'
  const parallelStarted = parallelStates.some(s => s !== 'pending')

  const parsingPercent = useMemo(() => {
    for (let i = events.length - 1; i >= 0; i--) {
      if (events[i].step === 'Parsing' && events[i].type === 'PROGRESS') return events[i].progress ?? -1
    }
    return -1
  }, [events])
  const parsingMessage = useMemo(() => {
    for (let i = events.length - 1; i >= 0; i--) {
      if (events[i].step === 'Parsing') return events[i].message
    }
    return ''
  }, [events])

  const step1 = isError ? 'pending' as const : parsingState === 'completed' ? 'completed' as const : parsingState === 'active' ? 'active' as const : 'pending' as const
  const step2 = isError ? 'pending' as const : isComplete || allParallelDone ? 'completed' as const : parallelStarted ? 'active' as const : 'pending' as const

  const progressPct = totalEnabled > 0 ? (completedCount / totalEnabled) * 100 : 0

  return (
    <Box>
      {/* ── Step pills row ─────────────────────── */}
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2.5 }}>
        <StepPill num={1} label={stepTranslations['Parsing'] ?? 'Parsing'} state={step1} />

        <Box sx={{
          flex: 1, height: 2, mx: 1,
          borderRadius: 1,
          position: 'relative',
          bgcolor: alpha(theme.palette.divider, 0.5),
          overflow: 'hidden',
        }}>
          <Box sx={{
            position: 'absolute', inset: 0,
            bgcolor: step1 === 'completed' ? theme.palette.success.main : 'transparent',
            transition: 'background-color 0.5s ease',
            borderRadius: 1,
          }} />
        </Box>

        <StepPill num={2} label={t('logAnalyzer.upload.analysis')} state={step2} />
      </Box>

      {/* ── Step 1 detail: parsing progress ───── */}
      {parsingState === 'active' && (
        <Box sx={{
          px: 2, py: 1.5, mb: 2,
          borderRadius: 2.5,
          border: `1px solid ${alpha(theme.palette.primary.main, 0.3)}`,
          bgcolor: alpha(theme.palette.primary.main, isDark ? 0.06 : 0.03),
          display: 'flex', alignItems: 'center', gap: 1.5,
        }}>
          {parsingPercent >= 0 ? (
            <Box sx={{ position: 'relative', display: 'inline-flex', flexShrink: 0 }}>
              <CircularProgress
                variant="determinate"
                value={parsingPercent}
                size={36} thickness={3.5}
                sx={{
                  color: 'primary.main',
                  '& .MuiCircularProgress-circle': { strokeLinecap: 'round' },
                }}
              />
              <Box sx={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <Typography sx={{ fontSize: '0.55rem', fontWeight: 800, color: 'primary.main' }}>{parsingPercent}%</Typography>
              </Box>
            </Box>
          ) : (
            <CircularProgress size={28} thickness={3} sx={{ color: 'primary.main', flexShrink: 0, '& .MuiCircularProgress-circle': { strokeLinecap: 'round' } }} />
          )}
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary" noWrap sx={{ fontSize: '0.75rem' }}>
              {parsingMessage}
            </Typography>
            {parsingPercent >= 0 && (
              <LinearProgress
                variant="determinate" value={parsingPercent}
                sx={{
                  mt: 0.75, borderRadius: 2, height: 4,
                  bgcolor: alpha(theme.palette.primary.main, 0.12),
                  '& .MuiLinearProgress-bar': { borderRadius: 2 },
                }}
              />
            )}
          </Box>
        </Box>
      )}

      {/* ── Step 2 detail: parallel analysis ──── */}
      {parallelStarted && (
        <Box sx={{
          px: 2, py: 1.5,
          borderRadius: 2.5,
          border: `1px solid ${
            step2 === 'active' ? alpha(theme.palette.primary.main, 0.3)
            : step2 === 'completed' ? alpha(theme.palette.success.main, 0.25)
            : theme.palette.divider
          }`,
          bgcolor: step2 === 'active'
            ? alpha(theme.palette.primary.main, isDark ? 0.04 : 0.02)
            : 'transparent',
          transition: 'all 0.35s ease',
        }}>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.75 }}>
            <Typography variant="caption" sx={{
              textTransform: 'uppercase', letterSpacing: 1.5, fontSize: '0.6rem',
              fontWeight: 700, color: 'text.secondary', opacity: 0.7,
            }}>
              {t('logAnalyzer.upload.processing')}
            </Typography>
            {!isComplete && !isError && totalEnabled > 0 && (
              <Typography variant="caption" fontWeight={700} sx={{ fontSize: '0.7rem' }}
                color={allParallelDone ? 'success.main' : 'text.secondary'}>
                {allParallelDone ? t('logAnalyzer.upload.done') : `${completedCount} / ${totalEnabled}`}
              </Typography>
            )}
          </Box>

          {/* Phase grid */}
          <Box sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(135px, 1fr))',
            gap: 0.75,
          }}>
            {steps.map((step, i) => (
              <PhaseTile key={step} label={stepTranslations[step] ?? step} state={parallelStates[i]} />
            ))}
          </Box>

          {/* Aggregate progress bar */}
          {!isComplete && !isError && totalEnabled > 0 && (
            <LinearProgress
              variant="determinate" value={progressPct}
              sx={{
                mt: 1.5, borderRadius: 2, height: 4,
                bgcolor: alpha(theme.palette.primary.main, 0.1),
                '& .MuiLinearProgress-bar': { borderRadius: 2 },
              }}
            />
          )}
        </Box>
      )}
    </Box>
  )
}

// ── Event log ───────────────────────────────────────────────────────────────

function isResultMessage(msg: string): boolean {
  return /^(Found |Parsed |Custom fields:|Critical issues:|NPE analysis:|Exception analysis:|Analysis complete)/.test(msg)
}

function EventLog({ logRef, events, borderTop = true }: { logRef: React.RefObject<HTMLDivElement | null>, events: ContainerEvent[], borderTop?: boolean }) {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const visible = useMemo(() => events.filter(e => e.message !== 'Skipped'), [events])

  const collapsed = useMemo(() => {
    const parsingEvents: number[] = []
    visible.forEach((e, i) => { if (e.step === 'Parsing' && e.type === 'PROGRESS') parsingEvents.push(i) })
    const hiddenSet = new Set(parsingEvents.slice(0, -3))
    return visible.filter((_, i) => !hiddenSet.has(i))
  }, [visible])

  return (
    <Paper
      ref={logRef}
      variant="outlined"
      sx={{
        maxHeight: 240, overflow: 'auto',
        bgcolor: isDark ? alpha(theme.palette.background.default, 0.6) : alpha(theme.palette.action.hover, 0.3),
        borderRadius: borderTop ? 2.5 : '0 0 10px 10px',
        borderTop: borderTop ? undefined : 'none',
        p: 0.5,
      }}
    >
      {collapsed.map((e, i) => {
        const isResult = isResultMessage(e.message)
        const isSuccess = e.type === 'SUCCESS'
        const isError = e.type === 'ERROR'
        const isProgress = e.type === 'PROGRESS'

        const dotColor = isError ? theme.palette.error.main
          : isSuccess ? theme.palette.success.main
          : isResult ? theme.palette.success.main
          : isProgress ? alpha(theme.palette.text.disabled, 0.25)
          : theme.palette.primary.main

        return (
          <Box
            key={i}
            sx={{
              display: 'flex', alignItems: 'center', gap: 1,
              px: 1.25, py: 0.5,
              mx: 0.25,
              borderRadius: 1.5,
              bgcolor: isSuccess ? alpha(theme.palette.success.main, 0.08)
                : isError ? alpha(theme.palette.error.main, 0.08)
                : isResult ? alpha(theme.palette.success.main, 0.04)
                : 'transparent',
              transition: 'background-color 0.2s ease',
              '&:hover': {
                bgcolor: isSuccess ? alpha(theme.palette.success.main, 0.12)
                  : isError ? alpha(theme.palette.error.main, 0.12)
                  : alpha(theme.palette.text.primary, 0.04),
              },
            }}
          >
            {/* Timeline dot + line */}
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', alignSelf: 'stretch', flexShrink: 0, width: 12 }}>
              <Box sx={{
                width: 7, height: 7, borderRadius: '50%',
                bgcolor: dotColor,
                border: isResult || isSuccess || isError ? `1.5px solid ${dotColor}` : 'none',
                boxShadow: isResult || isSuccess ? `0 0 6px ${alpha(dotColor, 0.4)}` : 'none',
                flexShrink: 0, mt: 0.5,
              }} />
              {i < collapsed.length - 1 && (
                <Box sx={{ width: 1, flex: 1, bgcolor: alpha(theme.palette.divider, 0.3), mt: 0.25 }} />
              )}
            </Box>

            {/* Content */}
            <Box sx={{ flex: 1, minWidth: 0, py: 0.125 }}>
              <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
                {/* Message */}
                <Typography noWrap sx={{
                  flex: 1,
                  fontFamily: "'JetBrains Mono', monospace",
                  fontSize: '0.72rem',
                  lineHeight: 1.5,
                  fontWeight: isResult || isSuccess || isError ? 600 : 400,
                  color: isError ? 'error.main'
                    : isSuccess ? 'success.main'
                    : isResult ? 'text.primary'
                    : isProgress ? 'text.disabled'
                    : 'text.secondary',
                }}>
                  {e.message}
                </Typography>

                {/* Step tag — right-aligned, only for non-progress */}
                {!isProgress && e.step && e.step !== 'Complete' && (
                  <Box sx={{
                    flexShrink: 0,
                    px: 0.75, py: 0.125,
                    borderRadius: 1,
                    bgcolor: isError ? alpha(theme.palette.error.main, 0.12)
                      : isResult ? alpha(theme.palette.success.main, 0.1)
                      : alpha(theme.palette.text.disabled, isDark ? 0.08 : 0.06),
                  }}>
                    <Typography sx={{
                      fontSize: '0.55rem', fontWeight: 700,
                      textTransform: 'uppercase', letterSpacing: 0.5,
                      color: isError ? 'error.main'
                        : isResult ? alpha(theme.palette.success.main, 0.8)
                        : 'text.disabled',
                      whiteSpace: 'nowrap',
                    }}>
                      {e.step}
                    </Typography>
                  </Box>
                )}
              </Box>
            </Box>
          </Box>
        )
      })}
    </Paper>
  )
}

// ── Collapsible log wrapper ──────────────────────────────────────────────────

function CollapsibleLog({ logRef, events }: { logRef: React.RefObject<HTMLDivElement | null>, events: ContainerEvent[] }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'

  return (
    <Box sx={{ mt: 2 }}>
      {/* Toggle button — minimal text link style */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Box
          onClick={() => setOpen(prev => !prev)}
          sx={{
            display: 'inline-flex', alignItems: 'center', gap: 0.5,
            cursor: 'pointer',
            py: 0.5, px: 1,
            borderRadius: 2,
            transition: 'all 0.2s ease',
            '&:hover': {
              bgcolor: alpha(theme.palette.text.secondary, 0.08),
            },
          }}
        >
          <ExpandMore sx={{
            fontSize: 16, color: 'text.disabled',
            transform: open ? 'rotate(180deg)' : 'rotate(0deg)',
            transition: 'transform 0.25s cubic-bezier(.4,0,.2,1)',
          }} />
          <Typography variant="caption" sx={{
            fontSize: '0.68rem', fontWeight: 600, color: 'text.disabled',
            letterSpacing: 0.3,
          }}>
            {open ? t('logAnalyzer.upload.hideDetails') : t('logAnalyzer.upload.showDetails')}
          </Typography>
        </Box>
      </Box>

      <Collapse in={open}>
        <Box sx={{ mt: 1 }}>
          <EventLog logRef={logRef} events={events} borderTop />
        </Box>
      </Collapse>
    </Box>
  )
}

// ── Root component ──────────────────────────────────────────────────────────

export default function OperationProgress({ events, steps = RUN_STEPS }: Props) {
  const { t } = useTranslation()
  const logRef = useRef<HTMLDivElement>(null)
  const lastEvent = events[events.length - 1]
  const isError = lastEvent?.type === 'ERROR'
  const isComplete = lastEvent?.type === 'SUCCESS'
  const isLogAnalysis = steps === LOG_ANALYSIS_STEPS
  const isLogCompose = steps === LOG_COMPOSE_STEPS

  const stepTranslations: Record<string, string> = t('steps', { returnObjects: true })

  const stepStates = useMemo(() => buildStepStates(events, steps), [events, steps])
  const activeIndex = isComplete
    ? steps.length
    : stepStates.reduce((max, state, idx) =>
        state !== 'pending' && state !== 'skipped' ? idx : max, -1)

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [events])

  return (
    <Box sx={{ py: 2 }}>
      {isLogAnalysis ? (
        <ParallelPhaseGrid events={events} steps={LOG_ANALYSIS_PARALLEL_STEPS} />
      ) : isLogCompose ? (
        <ParallelPhaseGrid events={events} steps={LOG_COMPOSE_PARALLEL_STEPS} firstStep="Merging" />
      ) : (
        <Stepper activeStep={activeIndex === -1 ? 0 : activeIndex} alternativeLabel>
          {steps.map((label, index) => {
            const state = stepStates[index]
            return (
              <Step key={label} completed={(state === 'completed' || isComplete) && state !== 'skipped'}>
                <StepLabel
                  error={isError && state === 'active'}
                  optional={state === 'skipped' ? (
                    <Typography variant="caption" color="text.disabled">&#x2717;</Typography>
                  ) : undefined}
                  sx={state === 'skipped' ? {
                    '& .MuiStepLabel-iconContainer .MuiStepIcon-root': { color: 'action.disabled' },
                    '& .MuiStepLabel-label': { color: 'text.disabled', textDecoration: 'line-through' },
                  } : undefined}
                >
                  {stepTranslations[label] ?? label}
                </StepLabel>
              </Step>
            )
          })}
        </Stepper>
      )}

      <CollapsibleLog logRef={logRef} events={events} />

      {!isError && !isComplete && !isLogAnalysis && <LinearProgress sx={{ mt: 2 }} />}
    </Box>
  )
}
