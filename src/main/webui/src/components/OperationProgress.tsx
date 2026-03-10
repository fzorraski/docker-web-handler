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
import type { ContainerEvent } from '../services/sseService'

const RUN_STEPS = ['Validating', 'Pulling', 'Creating', 'Starting']
const REMOVE_STEPS = ['Cancelling', 'Stopping', 'Removing']
const REMOVE_IMAGE_STEPS = ['Removing']

interface Props {
  events: ContainerEvent[]
  steps?: string[]
}

export { RUN_STEPS, REMOVE_STEPS, REMOVE_IMAGE_STEPS }

export default function OperationProgress({ events, steps = RUN_STEPS }: Props) {
  const logRef = useRef<HTMLDivElement>(null)
  const lastEvent = events[events.length - 1]
  const currentStep = lastEvent?.step ?? ''
  const isError = lastEvent?.type === 'ERROR'
  const isComplete = lastEvent?.type === 'SUCCESS'

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
            <StepLabel error={isError && index === activeIndex}>{label}</StepLabel>
          </Step>
        ))}
      </Stepper>

      <Paper
        ref={logRef}
        variant="outlined"
        sx={{ mt: 3, p: 2, maxHeight: 200, overflow: 'auto', bgcolor: 'grey.50' }}
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
