import { Paper, Typography } from '@mui/material'

export function SummaryCard({ label, value, color, onClick }: { label: string; value: string | number; color?: string; onClick?: () => void }) {
  return (
    <Paper
      onClick={onClick}
      sx={{
        px: 2.5, py: 1.5, minWidth: 120,
        ...(onClick && {
          cursor: 'pointer',
          transition: 'transform 0.15s, box-shadow 0.15s',
          '&:hover': { transform: 'translateY(-2px)', boxShadow: 4 },
        }),
      }}
    >
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" fontWeight={700} color={color}>{value}</Typography>
    </Paper>
  )
}
