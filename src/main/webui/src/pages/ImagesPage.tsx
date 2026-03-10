import { useState, useEffect, useCallback } from 'react'
import type { DockerImage } from '../types'
import { getImages, removeImage } from '../services/imageService'
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
} from '@mui/material'
import { Search, Delete } from '@mui/icons-material'

const ERROR_IMAGE_IN_USE = 100
const ERROR_IMAGE_WITH_CHILD = 101

export default function ImagesPage() {
  const [images, setImages] = useState<DockerImage[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)

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
    if (!confirm(`Remove image ${imageId} ?`)) return
    try {
      const res = await removeImage(imageId)
      if (res.state === ERROR_IMAGE_IN_USE) {
        alert('** Image in use **\n' + res.message)
      } else if (res.state === ERROR_IMAGE_WITH_CHILD) {
        alert('** Image has dependent child images **\n' + res.message)
      }
      loadImages()
    } catch (err) {
      alert('Error: ' + err)
    }
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
    </>
  )
}
