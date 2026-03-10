import { Box, Typography, Button } from '@mui/material'
import { Link } from 'react-router-dom'

interface Props {
  linkTo: string
  linkLabel: string
}

export default function HeroBanner({ linkTo, linkLabel }: Props) {
  return (
    <Box
      sx={{
        background: 'linear-gradient(to right, #ff6f61, #de6b48)',
        color: 'white',
        py: 8,
        textAlign: 'center',
      }}
    >
      <Typography variant="h2" fontWeight="bold" gutterBottom>
        Docker Handler
      </Typography>
      <Typography variant="h6" sx={{ mb: 3 }}>
        A simple docker web handler!
      </Typography>
      <Button
        component={Link}
        to={linkTo}
        variant="contained"
        size="large"
        sx={{
          bgcolor: 'rgba(255,255,255,0.2)',
          color: 'white',
          borderRadius: '50px',
          px: 4,
          '&:hover': { bgcolor: 'rgba(255,255,255,0.35)' },
        }}
      >
        {linkLabel}
      </Button>
    </Box>
  )
}
