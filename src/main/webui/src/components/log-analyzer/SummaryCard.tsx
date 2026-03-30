import { Paper, Typography } from '@mui/material'

export function SummaryCard({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <Paper sx={{ px: 2.5, py: 1.5, minWidth: 120 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h6" fontWeight={700} color={color}>{value}</Typography>
    </Paper>
  )
}
