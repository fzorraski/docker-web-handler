import { Box, Typography } from '@mui/material'

export default function Footer() {
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
      <Typography variant="body2">2024 Docker Handler.</Typography>
    </Box>
  )
}
