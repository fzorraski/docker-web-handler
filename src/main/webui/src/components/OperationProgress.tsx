import { useEffect, useRef } from 'react'
import {
  Box,
  Stepper,
  Step,
  StepLabel,
  Typography,
  LinearProgress,
  Paper,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { ContainerEvent } from '../services/sseService'

const RUN_STEPS = ['Validating', 'Pulling', 'Creating', 'Starting']
const REMOVE_STEPS = ['Cancelling', 'Stopping', 'Removing']
const REMOVE_IMAGE_STEPS = ['Removing']
const RESTORE_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring']
const RUN_WITH_RESTORE_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Starting']
const RESTORE_WITH_SCRIPTS_STEPS = ['Validating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts']
const RUN_WITH_RESTORE_AND_SCRIPTS_STEPS = ['Validating', 'Pulling', 'Creating', 'Preparing', 'Creating Database', 'Restoring', 'Running Scripts', 'Starting']
const SNAPSHOT_STEPS = ['Validating', 'Pulling Image', 'Creating Snapshot', 'Saving']

interface Props {
  events: ContainerEvent[]
  steps?: string[]
}

export { RUN_STEPS, REMOVE_STEPS, REMOVE_IMAGE_STEPS, RESTORE_STEPS, RUN_WITH_RESTORE_STEPS, RESTORE_WITH_SCRIPTS_STEPS, RUN_WITH_RESTORE_AND_SCRIPTS_STEPS, SNAPSHOT_STEPS }

export default function OperationProgress({ events, steps = RUN_STEPS }: Props) {
  const { t } = useTranslation()
  const logRef = useRef<HTMLDivElement>(null)
  const lastEvent = events[events.length - 1]
  const currentStep = lastEvent?.step ?? ''
  const isError = lastEvent?.type === 'ERROR'
  const isComplete = lastEvent?.type === 'SUCCESS'

  const stepTranslations: Record<string, string> = t('steps', { returnObjects: true })

  const activeIndex = currentStep === 'Complete'
    ? steps.length
    : steps.findIndex((s) => s === currentStep)

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [events])

  return (
    <Box sx={{ py: 2 }}>
      <Stepper activeStep={activeIndex === -1 ? 0 : activeIndex} alternativeLabel>
        {steps.map((label, index) => (
          <Step key={label} completed={index < activeIndex || isComplete}>
            <StepLabel error={isError && index === activeIndex}>
              {stepTranslations[label] ?? label}
            </StepLabel>
          </Step>
        ))}
      </Stepper>

      <Paper
        ref={logRef}
        variant="outlined"
        sx={{ mt: 3, p: 2, maxHeight: 200, overflow: 'auto', bgcolor: 'action.hover' }}
      >
        {events.map((e, i) => (
          <Typography
            key={i}
            variant="body2"
            sx={{
              fontFamily: 'monospace',
              fontSize: '0.8rem',
              color:
                e.type === 'ERROR'
                  ? 'error.main'
                  : e.type === 'SUCCESS'
                    ? 'success.main'
                    : 'text.secondary',
            }}
          >
            {e.message}
          </Typography>
        ))}
      </Paper>

      {!isError && !isComplete && <LinearProgress sx={{ mt: 2 }} />}
    </Box>
  )
}
