import { useState, useCallback, useMemo } from 'react'
import type { SxProps, Theme } from '@mui/material'

interface UseFullScreenDialogReturn {
  fullScreen: boolean
  toggleFullScreen: () => void
  resetFullScreen: () => void
  dialogProps: { fullScreen: boolean }
  contentSx: SxProps<Theme>
  viewerSx: (defaultHeight: number) => SxProps<Theme>
}

export default function useFullScreenDialog(): UseFullScreenDialogReturn {
  const [fullScreen, setFullScreen] = useState(false)

  const toggleFullScreen = useCallback(() => setFullScreen(f => !f), [])
  const resetFullScreen = useCallback(() => setFullScreen(false), [])

  const dialogProps = useMemo(() => ({ fullScreen }), [fullScreen])

  const contentSx = useMemo<SxProps<Theme>>(() =>
    fullScreen ? { display: 'flex', flexDirection: 'column', overflow: 'hidden' } : {},
    [fullScreen]
  )

  const viewerSx = useCallback(
    (defaultHeight: number): SxProps<Theme> =>
      fullScreen ? { flex: 1, minHeight: 0 } : { height: defaultHeight },
    [fullScreen]
  )

  return { fullScreen, toggleFullScreen, resetFullScreen, dialogProps, contentSx, viewerSx }
}
