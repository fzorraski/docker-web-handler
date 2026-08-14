// Live countdown chip for container expiration time, with auto-refresh on expiry.
import { useState, useEffect, useRef, useMemo } from 'react'
import { Chip } from '@mui/material'
import { Timer } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { expirySeverity, formatRemaining, type ExpirySeverity } from '../utils/expiry'

interface Props {
  expiresAt: string
  onCancel?: () => void
  onExpired: () => void
  onClick?: () => void
}

export default function ExpirationChip({ expiresAt, onCancel, onExpired, onClick }: Props) {
  const { t } = useTranslation()
  const expiresMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState('')
  const [severity, setSeverity] = useState<ExpirySeverity>('info')
  const expiredFired = useRef(false)
  const expiredTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    expiredFired.current = false
  }, [expiresMs])

  useEffect(() => {
    function update() {
      const diff = expiresMs - Date.now()
      setSeverity(expirySeverity(diff))
      if (diff <= 0) {
        setRemaining(t('containers.expiring'))
        if (!expiredFired.current) {
          expiredFired.current = true
          expiredTimerRef.current = setTimeout(onExpired, 6000)
        }
        return
      }
      setRemaining(formatRemaining(diff))
    }
    update()
    const id = setInterval(update, 1000)
    return () => {
      clearInterval(id)
      if (expiredTimerRef.current) clearTimeout(expiredTimerRef.current)
    }
  }, [expiresMs, onExpired, t])

  return (
    <Chip
      label={remaining}
      size="small"
      color={severity}
      icon={<Timer />}
      variant="outlined"
      onDelete={onCancel}
      onClick={onClick}
      sx={{
        // the seconds tick every second - proportional digits make the chip
        // breathe in and out, which is maddening in a column of twenty
        '& .MuiChip-label': { fontVariantNumeric: 'tabular-nums' },
        ...(onClick ? { cursor: 'pointer' } : {}),
      }}
    />
  )
}
