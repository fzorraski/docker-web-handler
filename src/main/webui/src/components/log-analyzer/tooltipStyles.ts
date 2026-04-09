/** Shared slotProps for message-preview tooltips on truncated table cells. */
export const truncatedTooltipProps = {
  tooltip: {
    sx: {
      maxWidth: 600,
      fontFamily: "'JetBrains Mono', monospace",
      fontSize: '0.75rem',
      whiteSpace: 'pre-wrap',
      wordBreak: 'break-all',
    },
  },
} as const
