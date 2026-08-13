import { Tooltip } from '@mui/material'
import { truncate } from '../utils/format'

interface Props {
  value: string
  /** longest value rendered untouched; anything longer is cut with a tooltip */
  max?: number
}

/**
 * Ellipsis-truncation for table cells fed unbounded values (an image tag may
 * be a full digest). Short values render as plain text with no tooltip, so
 * hovering the common case stays quiet.
 */
export default function TruncatedText({ value, max = 18 }: Props) {
  if (value.length <= max) {
    return <>{value}</>
  }
  return (
    <Tooltip title={value}>
      <span>{truncate(value, max)}</span>
    </Tooltip>
  )
}
