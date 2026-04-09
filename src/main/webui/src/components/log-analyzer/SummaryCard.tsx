import { ReactNode } from 'react'
import { Box, Paper, Typography } from '@mui/material'

export function SummaryCard({ label, value, color, onClick, icon }: {
  label: string
  value: string | number
  color?: string
  onClick?: () => void
  icon?: ReactNode
}) {
  return (
    <Paper
      onClick={onClick}
      elevation={0}
      sx={{
        px: 2.5, py: 2, minWidth: 140, position: 'relative', overflow: 'hidden',
        border: '1px solid',
        borderColor: 'divider',
        ...(onClick && {
          cursor: 'pointer',
          transition: 'all 0.2s ease',
          '&:hover': {
            transform: 'translateY(-2px)',
            boxShadow: 4,
            borderColor: color || 'primary.main',
          },
        }),
      }}
    >
      {icon && (
        <Box sx={{
          position: 'absolute', top: 8, right: 10,
          color: color || 'text.disabled', opacity: 0.15, fontSize: 20,
          display: 'flex', alignItems: 'center',
          '& svg': { fontSize: 'inherit' },
        }}>
          {icon}
        </Box>
      )}
      <Typography
        variant="caption"
        sx={{ color: 'text.secondary', fontWeight: 500, letterSpacing: '0.03em', textTransform: 'uppercase', fontSize: '0.65rem' }}
      >
        {label}
      </Typography>
      <Typography variant="h5" fontWeight={700} color={color} sx={{ mt: 0.25, position: 'relative' }}>
        {value}
      </Typography>
    </Paper>
  )
}
