import { useState } from 'react'
import { IconButton, Menu, MenuItem, ListItemText, Tooltip } from '@mui/material'
import { Language } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'

const LANGUAGES = [
  { code: 'en', flag: '\u{1F1FA}\u{1F1F8}', label: 'English' },
  { code: 'pt-BR', flag: '\u{1F1E7}\u{1F1F7}', label: 'Portugues' },
  { code: 'es', flag: '\u{1F1EA}\u{1F1F8}', label: 'Espanol' },
]

export default function LanguageSwitcher() {
  const { i18n, t } = useTranslation()
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null)

  function handleSelect(code: string) {
    i18n.changeLanguage(code)
    setAnchorEl(null)
  }

  return (
    <>
      <Tooltip title={t('navbar.language')} arrow>
        <IconButton
          onClick={(e) => setAnchorEl(e.currentTarget)}
          sx={{ color: 'inherit' }}
        >
          <Language />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchorEl}
        open={Boolean(anchorEl)}
        onClose={() => setAnchorEl(null)}
      >
        {LANGUAGES.map((lang) => (
          <MenuItem
            key={lang.code}
            onClick={() => handleSelect(lang.code)}
            selected={i18n.resolvedLanguage === lang.code}
          >
            <ListItemText>{lang.flag} {lang.label}</ListItemText>
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}
