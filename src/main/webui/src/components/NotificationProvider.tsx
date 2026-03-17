import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import {
  Snackbar,
  Alert,
  type AlertColor,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Button,
} from '@mui/material'
import { useTranslation } from 'react-i18next'

interface NotificationContextType {
  notify: (message: string, severity?: AlertColor) => void
  confirm: (message: string) => Promise<boolean>
}

const NotificationContext = createContext<NotificationContextType>(null!)

export function useNotification() {
  return useContext(NotificationContext)
}

export default function NotificationProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const [snack, setSnack] = useState<{ message: string; severity: AlertColor; open: boolean }>({
    message: '',
    severity: 'success',
    open: false,
  })

  const [dialog, setDialog] = useState<{
    message: string
    open: boolean
    resolve: ((value: boolean) => void) | null
  }>({ message: '', open: false, resolve: null })

  const notify = useCallback((message: string, severity: AlertColor = 'success') => {
    setSnack({ message, severity, open: true })
  }, [])

  const confirm = useCallback((message: string): Promise<boolean> => {
    return new Promise((resolve) => {
      setDialog({ message, open: true, resolve })
    })
  }, [])

  function handleDialogClose(accepted: boolean) {
    dialog.resolve?.(accepted)
    setDialog({ message: '', open: false, resolve: null })
  }

  return (
    <NotificationContext.Provider value={{ notify, confirm }}>
      {children}

      <Snackbar
        open={snack.open}
        autoHideDuration={5000}
        onClose={() => setSnack((s) => ({ ...s, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          onClose={() => setSnack((s) => ({ ...s, open: false }))}
          severity={snack.severity}
          variant="filled"
          sx={{ width: '100%' }}
        >
          {snack.message}
        </Alert>
      </Snackbar>

      <Dialog open={dialog.open} onClose={() => handleDialogClose(false)}>
        <DialogTitle>{t('notification.confirmTitle')}</DialogTitle>
        <DialogContent>
          <DialogContentText>{dialog.message}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => handleDialogClose(false)} color="inherit">{t('common.cancel')}</Button>
          <Button onClick={() => handleDialogClose(true)} variant="contained" autoFocus>
            {t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>
    </NotificationContext.Provider>
  )
}
