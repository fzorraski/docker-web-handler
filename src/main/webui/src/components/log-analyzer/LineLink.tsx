import { Typography } from '@mui/material'

export function LineLink({ line, onClick }: { line: number; onClick: (line: number) => void }) {
  return (
    <Typography
      variant="body2"
      component="span"
      fontFamily="'JetBrains Mono', monospace"
      fontSize="0.8rem"
      sx={{ cursor: 'pointer', color: 'primary.main', '&:hover': { textDecoration: 'underline' } }}
      onClick={(e) => { e.stopPropagation(); onClick(line) }}
    >
      {line}
    </Typography>
  )
}
