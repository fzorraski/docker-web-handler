import { useState } from 'react'
import { Box, Checkbox, Chip, Collapse, FormControlLabel, Typography } from '@mui/material'
import { ExpandMore } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { PostRestoreScriptsResponse } from '../services/dumpService'
import { formatScriptSize } from '../utils/format'

interface Props {
  scriptsResponse: PostRestoreScriptsResponse
  selectedOptionalScripts: string[]
  onSelectedOptionalScriptsChange: (scripts: string[]) => void
  tPrefix: 'newContainer' | 'restoreDump'
}

const chipSx = { ml: 0.5, height: 20, '& .MuiChip-label': { px: 0.75, fontSize: '0.7rem' } }

export default function PostRestoreScriptsSection({
  scriptsResponse,
  selectedOptionalScripts,
  onSelectedOptionalScriptsChange,
  tPrefix,
}: Props) {
  const { t: _t } = useTranslation()
  const t = _t as (key: string, options?: Record<string, unknown>) => string
  const [mandatoryOpen, setMandatoryOpen] = useState(false)
  const [optionalOpen, setOptionalOpen] = useState(true)
  const selectedSet = new Set(selectedOptionalScripts)

  return (
    <>
      <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>
        {t(`${tPrefix}.postRestoreScripts`)}
      </Typography>

      {scriptsResponse.mandatory.length > 0 && (
        <Box sx={{ mb: 1 }}>
          <Box
            onClick={() => setMandatoryOpen((v) => !v)}
            sx={{ display: 'flex', alignItems: 'center', cursor: 'pointer', userSelect: 'none', gap: 0.5 }}
            data-testid="mandatory-scripts-header"
          >
            <ExpandMore sx={{ fontSize: 18, transition: 'transform 0.2s', transform: mandatoryOpen ? 'rotate(0deg)' : 'rotate(-90deg)' }} />
            <Typography variant="caption" color="text.secondary">{t(`${tPrefix}.mandatoryScripts`)}</Typography>
            <Chip label={t(`${tPrefix}.scriptsCount`, { count: scriptsResponse.mandatory.length })} size="small" variant="outlined" sx={chipSx} />
          </Box>
          <Collapse in={mandatoryOpen}>
            <Box sx={{ mt: 0.5, maxHeight: 200, overflowY: 'auto' }} data-testid="mandatory-scripts-list">
              {scriptsResponse.mandatory.map((s) => (
                <FormControlLabel
                  key={s.filename}
                  control={<Checkbox checked disabled size="small" />}
                  label={
                    <Typography variant="body2">
                      {s.filename}
                      <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                    </Typography>
                  }
                  sx={{ display: 'flex', ml: 0 }}
                />
              ))}
            </Box>
          </Collapse>
        </Box>
      )}

      {scriptsResponse.optional.length > 0 && (
        <Box sx={{ mb: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            <Box
              onClick={() => setOptionalOpen((v) => !v)}
              sx={{ display: 'flex', alignItems: 'center', cursor: 'pointer', userSelect: 'none', gap: 0.5 }}
              data-testid="optional-scripts-header"
            >
              <ExpandMore sx={{ fontSize: 18, transition: 'transform 0.2s', transform: optionalOpen ? 'rotate(0deg)' : 'rotate(-90deg)' }} />
              <Typography variant="caption" color="text.secondary">{t(`${tPrefix}.optionalScripts`)}</Typography>
              <Chip
                label={selectedOptionalScripts.length > 0
                  ? t(`${tPrefix}.scriptsSelectedCount`, { selected: selectedOptionalScripts.length, total: scriptsResponse.optional.length })
                  : t(`${tPrefix}.scriptsCount`, { count: scriptsResponse.optional.length })}
                size="small" variant="outlined"
                color={selectedOptionalScripts.length > 0 ? 'primary' : 'default'}
                sx={chipSx}
              />
            </Box>
            {optionalOpen && (
              <Typography
                variant="caption"
                color="primary"
                onClick={(e) => {
                  e.stopPropagation()
                  if (selectedOptionalScripts.length === scriptsResponse.optional.length) {
                    onSelectedOptionalScriptsChange([])
                  } else {
                    onSelectedOptionalScriptsChange(scriptsResponse.optional.map((s) => s.filename))
                  }
                }}
                sx={{ ml: 'auto', cursor: 'pointer', userSelect: 'none', '&:hover': { textDecoration: 'underline' } }}
                data-testid="toggle-select-all"
              >
                {selectedOptionalScripts.length === scriptsResponse.optional.length
                  ? t(`${tPrefix}.deselectAll`)
                  : t(`${tPrefix}.selectAll`)}
              </Typography>
            )}
          </Box>
          <Collapse in={optionalOpen}>
            <Box sx={{ mt: 0.5, maxHeight: 200, overflowY: 'auto' }} data-testid="optional-scripts-list">
              {scriptsResponse.optional.map((s) => (
                <FormControlLabel
                  key={s.filename}
                  control={
                    <Checkbox
                      checked={selectedSet.has(s.filename)}
                      onChange={(e) => {
                        if (e.target.checked) {
                          onSelectedOptionalScriptsChange([...selectedOptionalScripts, s.filename])
                        } else {
                          onSelectedOptionalScriptsChange(selectedOptionalScripts.filter((f) => f !== s.filename))
                        }
                      }}
                      size="small"
                    />
                  }
                  label={
                    <Typography variant="body2">
                      {s.filename}
                      <Chip label={formatScriptSize(s.fileSize)} size="small" variant="outlined" sx={{ ml: 1 }} />
                    </Typography>
                  }
                  sx={{ display: 'flex', ml: 0 }}
                />
              ))}
            </Box>
          </Collapse>
        </Box>
      )}

      <Typography variant="caption" color="text.secondary">
        {scriptsResponse.onFailure === 'stop' ? t(`${tPrefix}.onFailureStop`) : t(`${tPrefix}.onFailureContinue`)}
      </Typography>
    </>
  )
}
