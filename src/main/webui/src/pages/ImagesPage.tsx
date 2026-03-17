import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { DockerImage } from '../types'
import { getImages } from '../services/imageService'
import { streamRemoveImage, type ContainerEvent } from '../services/sseService'
import OperationProgress, { REMOVE_IMAGE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
import {
  Box,
  Typography,
  TextField,
  InputAdornment,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TableSortLabel,
  Paper,
  Button,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  useTheme,
} from '@mui/material'
import { Search, Delete } from '@mui/icons-material'

export default function ImagesPage() {
  const theme = useTheme()
  const isDark = theme.palette.mode === 'dark'
  const theadBg = isDark ? 'background.paper' : 'primary.main'
  const theadColor = isDark ? 'text.primary' : 'white'
  const theadSortSx = isDark
    ? { color: 'text.primary !important', '& .MuiTableSortLabel-icon': { color: 'text.secondary !important' } }
    : { color: 'white !important', '& .MuiTableSortLabel-icon': { color: 'white !important' } }
  const { notify, confirm } = useNotification()
  const { t } = useTranslation()
  const [images, setImages] = useState<DockerImage[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [removeDialogOpen, setRemoveDialogOpen] = useState(false)
  const [removeEvents, setRemoveEvents] = useState<ContainerEvent[]>([])
  const [removeError, setRemoveError] = useState(false)
  const [removeDone, setRemoveDone] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)
  const [sortKey, setSortKey] = useState<string>('')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')

  const IMAGE_COLUMNS: { key: keyof DockerImage | 'action'; label: string }[] = useMemo(() => [
    { key: 'repository', label: t('images.columns.repository') },
    { key: 'tag', label: t('images.columns.tag') },
    { key: 'imageId', label: t('images.columns.imageId') },
    { key: 'created', label: t('images.columns.created') },
    { key: 'size', label: t('images.columns.size') },
    { key: 'action', label: t('images.columns.action') },
  ], [t])

  function handleSort(key: string) {
    if (key === 'action') return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  const loadImages = useCallback(() => {
    setLoading(true)
    getImages()
      .then(setImages)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    loadImages()
  }, [loadImages])

  async function handleRemove(imageId: string) {
    if (!(await confirm(t('images.confirmRemove', { id: imageId })))) return

    setRemoveDialogOpen(true)
    setRemoveEvents([])
    setRemoveError(false)
    setRemoveDone(false)

    cleanupSse.current = streamRemoveImage(
      imageId,
      (event) => setRemoveEvents((prev) => [...prev, event]),
      () => {
        setRemoveDone(true)
        setTimeout(() => {
          setRemoveDialogOpen(false)
          setRemoveEvents([])
          setRemoveDone(false)
          notify(t('images.imageRemoved'), 'success')
          loadImages()
        }, 1500)
      },
      () => {
        setRemoveError(true)
        loadImages()
      },
    )
  }

  function handleRemoveDialogClose() {
    if (cleanupSse.current) {
      cleanupSse.current()
      cleanupSse.current = null
    }
    setRemoveDialogOpen(false)
    setRemoveEvents([])
    setRemoveError(false)
    setRemoveDone(false)
    loadImages()
  }

  const filtered = useMemo(() => {
    const result = images.filter((img) =>
      Object.values(img).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
    )
    if (!sortKey) return result
    return [...result].sort((a, b) => {
      const va = (a[sortKey as keyof DockerImage] ?? '').toLowerCase()
      const vb = (b[sortKey as keyof DockerImage] ?? '').toLowerCase()
      const cmp = va.localeCompare(vb)
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [images, filter, sortKey, sortDir])

  return (
    <>
      <HeroBanner linkTo="/" linkLabel={t('hero.exploreContainers')} />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 3 }}>
          {t('images.title')}
        </Typography>

        <TextField
          fullWidth
          placeholder={t('images.searchPlaceholder')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          size="small"
          sx={{ mb: 3 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search color="action" />
                </InputAdornment>
              ),
            },
          }}
        />

        <TableContainer component={Paper} elevation={2} sx={{ borderRadius: 2 }}>
          <Table>
            <TableHead>
              <TableRow sx={{ bgcolor: theadBg }}>
                {IMAGE_COLUMNS.map((col) => (
                  <TableCell key={col.key} sx={{ color: theadColor, fontWeight: 600 }}>
                    {col.key !== 'action' ? (
                      <TableSortLabel
                        active={sortKey === col.key}
                        direction={sortKey === col.key ? sortDir : 'asc'}
                        onClick={() => handleSort(col.key)}
                        sx={theadSortSx}
                      >
                        {col.label}
                      </TableSortLabel>
                    ) : col.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {t('images.noImagesFound')}
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((img) => (
                <TableRow key={img.imageId} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{img.repository}</TableCell>
                  <TableCell>{img.tag}</TableCell>
                  <TableCell>{img.imageId}</TableCell>
                  <TableCell>{formatBackendDate(img.created)}</TableCell>
                  <TableCell>{img.size}</TableCell>
                  <TableCell>
                    <Button
                      size="small"
                      variant="contained"
                      color="error"
                      startIcon={<Delete />}
                      onClick={() => handleRemove(img.imageId)}
                    >
                      {t('common.remove')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <Dialog open={removeDialogOpen} onClose={handleRemoveDialogClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('images.removingImage')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeEvents} steps={REMOVE_IMAGE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeError || removeDone) && (
            <Button onClick={handleRemoveDialogClose} color="inherit">{t('common.close')}</Button>
          )}
        </DialogActions>
      </Dialog>
    </>
  )
}
