import { IconButton, Tooltip } from '@mui/material'
import { Fullscreen, FullscreenExit } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

interface Props {
  fullScreen: boolean
  onToggle: () => void
  color?: string
}

export default function FullscreenToggleButton({ fullScreen, onToggle, color }: Props) {
  const { t } = useTranslation()

  return (
    <Tooltip title={fullScreen ? t('common.exitFullscreen') : t('common.fullscreen')}>
      <IconButton onClick={onToggle} size="small" sx={{ color }}>
        {fullScreen ? <FullscreenExit sx={{ fontSize: 18 }} /> : <Fullscreen sx={{ fontSize: 18 }} />}
      </IconButton>
    </Tooltip>
  )
}
