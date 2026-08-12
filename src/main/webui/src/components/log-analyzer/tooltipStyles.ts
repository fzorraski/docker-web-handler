/** slotProps for tooltips whose title is a '\n'-joined list of lines — MUI's default
 *  tooltip is white-space: normal, which would collapse them into one run-on line. */
export const multilineTooltipProps = {
  tooltip: {
    sx: { whiteSpace: 'pre-line' },
  },
} as const

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
