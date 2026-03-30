import { useState, useEffect } from 'react'
import {
  Autocomplete, Box, TextField,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { RawLogTab } from './RawLogTab'
import type { ThreadInfo } from '../../services/logAnalyzerService'
import * as logService from '../../services/logAnalyzerService'

export function ThreadViewTab({ analysisId }: { analysisId: string }) {
  const { t } = useTranslation()
  const [threads, setThreads] = useState<ThreadInfo[]>([])
  const [selectedThread, setSelectedThread] = useState('')

  useEffect(() => {
    logService.getThreads(analysisId).then((th) => {
      setThreads(th)
      setSelectedThread(prev => prev || (th.length > 0 ? th[0].thread : ''))
    }).catch(() => {})
  }, [analysisId])

  return (
    <Box>
      <Autocomplete
        size="small"
        sx={{ minWidth: 350, mb: 2 }}
        options={threads}
        getOptionLabel={(th) => `${th.thread} (${th.lineCount.toLocaleString()} ${t('logAnalyzer.common.lines')})`}
        value={threads.find((th) => th.thread === selectedThread) ?? null}
        onChange={(_, v) => setSelectedThread(v?.thread ?? '')}
        isOptionEqualToValue={(o, v) => o.thread === v.thread}
        renderInput={(params) => <TextField {...params} label={t('logAnalyzer.threads.select')} />}
      />
      {selectedThread && <RawLogTab key={selectedThread} analysisId={analysisId} initialThread={selectedThread} />}
    </Box>
  )
}
