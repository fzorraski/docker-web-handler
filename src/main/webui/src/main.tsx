import { StrictMode, useState, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider'
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs'
import dayjs from 'dayjs'
import localizedFormat from 'dayjs/plugin/localizedFormat'
import customParseFormat from 'dayjs/plugin/customParseFormat'
import 'dayjs/locale/pt-br'
import 'dayjs/locale/es'
import i18n from './i18n'

dayjs.extend(customParseFormat)
dayjs.extend(localizedFormat)
import App from './App'
import ThemeModeProvider from './components/ThemeModeProvider'
import NotificationProvider from './components/NotificationProvider'

const DAYJS_LOCALE_MAP: Record<string, string> = {
  en: 'en',
  'pt-BR': 'pt-br',
  es: 'es',
}

function syncDayjsLocale(lng: string) {
  const mapped = DAYJS_LOCALE_MAP[lng] ?? lng.toLowerCase()
  dayjs.locale(mapped)
  return mapped
}

function Root() {
  const initial = i18n.resolvedLanguage ?? 'en'
  const initialLocale = syncDayjsLocale(initial)
  const [locale, setLocale] = useState(initialLocale)

  useEffect(() => {
    const handler = (lng: string) => {
      const mapped = syncDayjsLocale(lng)
      setLocale(mapped)
    }
    i18n.on('languageChanged', handler)
    return () => { i18n.off('languageChanged', handler) }
  }, [])

  return (
    <ThemeModeProvider>
      <LocalizationProvider key={locale} dateAdapter={AdapterDayjs} adapterLocale={locale}>
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
