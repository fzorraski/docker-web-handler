import { useState, useEffect, useCallback, useRef } from 'react'
import type { DockerImage } from '../types'
import { getImages } from '../services/imageService'
import { streamRemoveImage, type ContainerEvent } from '../services/sseService'
import OperationProgress, { REMOVE_IMAGE_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import HeroBanner from '../components/HeroBanner'
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
  Paper,
  Button,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
} from '@mui/material'
import { Search, Delete } from '@mui/icons-material'

export default function ImagesPage() {
  const { notify, confirm } = useNotification()
  const [images, setImages] = useState<DockerImage[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [removeDialogOpen, setRemoveDialogOpen] = useState(false)
  const [removeEvents, setRemoveEvents] = useState<ContainerEvent[]>([])
  const [removeError, setRemoveError] = useState(false)
  const [removeDone, setRemoveDone] = useState(false)
  const cleanupSse = useRef<(() => void) | null>(null)

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
    if (!(await confirm(`Remove image ${imageId}? This action cannot be undone.`))) return

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
          notify('Image removed successfully.', 'success')
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

  const filtered = images.filter((img) =>
    Object.values(img).some((v) => v.toLowerCase().includes(filter.toLowerCase()))
  )

  return (
    <>
      <HeroBanner linkTo="/" linkLabel="Explore Containers" />

      <Box sx={{ maxWidth: '85%', mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 3 }}>
          Images
        </Typography>

        <TextField
          fullWidth
          placeholder="Search images..."
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
              <TableRow sx={{ bgcolor: 'primary.main' }}>
                {['Repository', 'Tag', 'Image ID', 'Created', 'Size', 'Action'].map((h) => (
                  <TableCell key={h} sx={{ color: 'white', fontWeight: 600 }}>{h}</TableCell>
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
                    No images found
                  </TableCell>
                </TableRow>
              )}
              {filtered.map((img) => (
                <TableRow key={img.imageId} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{img.repository}</TableCell>
                  <TableCell>{img.tag}</TableCell>
                  <TableCell>{img.imageId}</TableCell>
                  <TableCell>{img.created}</TableCell>
                  <TableCell>{img.size}</TableCell>
                  <TableCell>
                    <Button
                      size="small"
                      variant="contained"
                      color="error"
                      startIcon={<Delete />}
                      onClick={() => handleRemove(img.imageId)}
                    >
                      Remove
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
          <Delete sx={{ mr: 1, verticalAlign: 'middle' }} /> Removing Image
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={removeEvents} steps={REMOVE_IMAGE_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(removeError || removeDone) && (
            <Button onClick={handleRemoveDialogClose} color="inherit">Close</Button>
          )}
        </DialogActions>
      </Dialog>
    </>
  )
}
