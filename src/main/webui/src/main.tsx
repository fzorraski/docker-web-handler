import { StrictMode, useState, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider'
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs'
import dayjs from 'dayjs'
import localizedFormat from 'dayjs/plugin/localizedFormat'

dayjs.extend(localizedFormat)
import App from './App'
import ThemeModeProvider from './components/ThemeModeProvider'
import NotificationProvider from './components/NotificationProvider'
import { getLocale } from './services/containerService'

function Root() {
  const [locale, setLocale] = useState<string | undefined>()

  useEffect(() => {
    getLocale()
      .then(async (loc) => {
        const trimmed = loc?.trim()
        if (trimmed) {
          try {
            await import(`dayjs/locale/${trimmed}.js`)
            dayjs.locale(trimmed)
            setLocale(trimmed)
          } catch { /* fallback to browser default */ }
        }
      })
      .catch(() => {})
  }, [])

  return (
    <ThemeModeProvider>
      <LocalizationProvider key={locale ?? 'default'} dateAdapter={AdapterDayjs} adapterLocale={locale}>
        <NotificationProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </NotificationProvider>
      </LocalizationProvider>
    </ThemeModeProvider>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
