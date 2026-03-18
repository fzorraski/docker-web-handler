import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { DockerImage } from '../types'
import { getImages } from '../services/imageService'
import { streamRemoveImage, type ContainerEvent } from '../services/sseService'
import OperationProgress, { REMOVE_IMAGE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
import { useTableSort } from '../hooks/useTableSort'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useSseOperation } from '../hooks/useSseOperation'
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
  IconButton,
  Tooltip,
} from '@mui/material'
import { Search, Delete } from '@mui/icons-material'

const filterImage = (img: DockerImage, query: string) =>
  Object.values(img).some((v) => v.toLowerCase().includes(query.toLowerCase()))

const sortImageValue = (img: DockerImage, key: string) =>
  (img[key as keyof DockerImage] ?? '').toLowerCase()

export default function ImagesPage() {
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()
  const { notify, confirm } = useNotification()
  const { t } = useTranslation()
  const [images, setImages] = useState<DockerImage[]>([])
  const [loading, setLoading] = useState(true)
  const removeSse = useSseOperation()

  const { filter, setFilter, sortKey, sortDir, handleSort, sorted: filtered } = useTableSort({
    data: images,
    filterFn: filterImage,
    sortValueFn: sortImageValue,
  })

  const IMAGE_COLUMNS: { key: keyof DockerImage | 'action'; label: string }[] = useMemo(() => [
    { key: 'repository', label: t('images.columns.repository') },
    { key: 'tag', label: t('images.columns.tag') },
    { key: 'imageId', label: t('images.columns.imageId') },
    { key: 'created', label: t('images.columns.created') },
    { key: 'size', label: t('images.columns.size') },
    { key: 'action', label: t('images.columns.action') },
  ], [t])

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

    removeSse.start(
      (onEvent, onDone, onError) => streamRemoveImage(imageId, onEvent, onDone, onError),
      () => {
        setTimeout(() => {
          removeSse.reset()
          notify(t('images.imageRemoved'), 'success')
          loadImages()
        }, 1500)
      },
      () => loadImages(),
    )
  }

  function handleRemoveDialogClose() {
    removeSse.cleanup()
    removeSse.reset()
    loadImages()
  }

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
                    <Tooltip title={t('common.remove')}>
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => handleRemove(img.imageId)}
                      >
                        <Delete />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <Dialog open={removeSse.events.length > 0} onClose={handleRemoveDialogClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'error.main', color: 'white' }}>
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('images.removingImage')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeSse.events} steps={REMOVE_IMAGE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeSse.hasError || removeSse.isDone) && (
            <Button onClick={handleRemoveDialogClose} color="inherit">{t('common.close')}</Button>
          )}
        </DialogActions>
      </Dialog>
    </>
  )
}
