import type { ReactNode } from 'react'
import { Box, TextField, InputAdornment, Chip } from '@mui/material'
import { Search } from '@mui/icons-material'

interface Props {
  filter: string
  onFilterChange: (value: string) => void
  placeholder: string
  /** e.g. "12 users" - reflects the filtered count */
  countLabel: string
  /** create button (or anything else) pinned to the right */
  action?: ReactNode
}

/** Search field + result count + optional action, shared by the admin tables. */
export default function AdminTableToolbar({ filter, onFilterChange, placeholder, countLabel, action }: Props) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
      <TextField
        size="small"
        value={filter}
        onChange={(e) => onFilterChange(e.target.value)}
        placeholder={placeholder}
        sx={{ flexGrow: 1, maxWidth: 420 }}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <Search fontSize="small" />
              </InputAdornment>
            ),
          },
        }}
      />
      <Chip label={countLabel} size="small" variant="outlined" />
      <Box sx={{ flexGrow: 1 }} />
      {action}
    </Box>
  )
}
