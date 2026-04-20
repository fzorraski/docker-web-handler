// Live countdown chip for container expiration time, with auto-refresh on expiry.
import { useState, useEffect, useRef, useMemo } from 'react'
import { Chip } from '@mui/material'
import { Timer } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Props {
  expiresAt: string
  onCancel: () => void
  onExpired: () => void
  onClick?: () => void
}

export default function ExpirationChip({ expiresAt, onCancel, onExpired, onClick }: Props) {
  const { t } = useTranslation()
  const expiresMs = useMemo(() => new Date(expiresAt).getTime(), [expiresAt])
  const [remaining, setRemaining] = useState('')
  const expiredFired = useRef(false)
  const expiredTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    expiredFired.current = false
  }, [expiresMs])

  useEffect(() => {
    function update() {
      const diff = expiresMs - Date.now()
      if (diff <= 0) {
        setRemaining(t('containers.expiring'))
        if (!expiredFired.current) {
          expiredFired.current = true
          expiredTimerRef.current = setTimeout(onExpired, 6000)
        }
        return
      }
      const h = Math.floor(diff / 3600000)
      const m = Math.floor((diff % 3600000) / 60000)
      const s = Math.floor((diff % 60000) / 1000)
      setRemaining(h > 0 ? `${h}h ${m}m ${s}s` : m > 0 ? `${m}m ${s}s` : `${s}s`)
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
      color="warning"
      icon={<Timer />}
      variant="outlined"
      onDelete={onCancel}
      onClick={onClick}
      sx={onClick ? { cursor: 'pointer' } : undefined}
    />
  )
}
