import { Box, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'

export default function Footer() {
  const { t } = useTranslation()

  return (
    <Box
      component="footer"
      sx={{
        bgcolor: 'primary.dark',
        color: '#f5f5f5',
        py: 2.5,
        mt: 'auto',
        textAlign: 'center',
      }}
    >
      <Typography variant="body2">{t('footer.copyright')}</Typography>
    </Box>
  )
}
