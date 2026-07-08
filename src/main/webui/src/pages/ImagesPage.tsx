import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import type { DockerImage } from '../types'
import { getImages } from '../services/imageService'
import { streamRemoveImage } from '../services/sseService'
import OperationProgress, { REMOVE_IMAGE_STEPS, PRUNE_IMAGES_STEPS } from '../components/OperationProgress'
import { useNotification } from '../components/NotificationProvider'
import { useAuth } from '../components/AuthProvider'
import { P } from '../utils/permissions'
import HeroBanner from '../components/HeroBanner'
import { useTranslation } from 'react-i18next'
import { formatBackendDate } from '../utils/format'
import { useTableSort } from '../hooks/useTableSort'
import { useTableHeaderTheme } from '../hooks/useTableHeaderTheme'
import { useStickyHeader } from '../hooks/useStickyHeader'
import { useSseOperation } from '../hooks/useSseOperation'
import { usePruneDialog } from '../hooks/usePruneDialog'
import { useActionMenu } from '../hooks/useActionMenu'
import { useTablePagination } from '../hooks/useTablePagination'
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
  Chip,
  FormControlLabel,
  Switch,
  Slider,
  Alert,
  Menu,
  MenuItem,
  ListItemIcon,
  ListItemText,
  TablePagination,
} from '@mui/material'
import { Search, Delete, DeleteSweep, CleaningServices, AccountTree, Info, Warning, PhotoLibrary, CheckCircle, RemoveCircleOutline, DataUsage } from '@mui/icons-material'
import { getLastUsedColor, getLastUsedLabel } from '../utils/lastUsedColor'

const filterImage = (img: DockerImage, query: string) => {
  const q = query.toLowerCase()
  return img.repository.toLowerCase().includes(q)
    || img.tag.toLowerCase().includes(q)
    || img.imageId.toLowerCase().includes(q)
    || img.size.toLowerCase().includes(q)
}

const sortImageValue = (img: DockerImage, key: string) => {
  if (key === 'inUse') return img.inUse ? '0' : '1'
  if (key === 'lastUsedAt') return img.lastUsedAt ?? ''
  return (img[key as keyof DockerImage] ?? '').toString().toLowerCase()
}

import { DAY_MARKS } from '../utils/constants'

export default function ImagesPage() {
  const { theadBg, theadColor, theadSortSx } = useTableHeaderTheme()
  const tableRef = useRef<HTMLDivElement>(null)
  useStickyHeader(tableRef)
  const { notify, confirm } = useNotification()
  const { rbacEnabled, hasPermission } = useAuth()
  const canManageImages = hasPermission(P.IMAGES_MANAGE)
  const { t } = useTranslation()
  const [images, setImages] = useState<DockerImage[]>([])
  const [loading, setLoading] = useState(true)
  const [showUnusedOnly, setShowUnusedOnly] = useState(false)
  const removeSse = useSseOperation()
  const imageMenu = useActionMenu<DockerImage>()

  const filteredByUsage = useMemo(
    () => showUnusedOnly ? images.filter(img => !img.inUse) : images,
    [images, showUnusedOnly]
  )

  const { filter, setFilter, sortKey, sortDir, handleSort, sorted: filtered } = useTableSort({
    data: filteredByUsage,
    filterFn: filterImage,
    sortValueFn: sortImageValue,
  })

  const pagination = useTablePagination(filtered, { storageKey: 'images' })

  const IMAGE_COLUMNS: { key: string; label: string; sortable: boolean }[] = useMemo(() => [
    { key: 'repository', label: t('images.columns.repository'), sortable: true },
    { key: 'tag', label: t('images.columns.tag'), sortable: true },
    { key: 'imageId', label: t('images.columns.imageId'), sortable: true },
    { key: 'created', label: t('images.columns.created'), sortable: true },
    { key: 'size', label: t('images.columns.size'), sortable: true },
    { key: 'inUse', label: t('images.columns.status'), sortable: true },
    { key: 'lastUsedAt', label: t('images.columns.lastUsed'), sortable: true },
    { key: 'action', label: t('images.columns.action'), sortable: false },
  ], [t])

  const unusedCount = useMemo(() => images.filter(img => !img.inUse).length, [images])
  const inUseCount = useMemo(() => images.filter(img => img.inUse).length, [images])

  const totalSize = useMemo(() => {
    let bytes = 0
    for (const img of images) {
      const match = img.size.match(/([\d.]+)\s*MB/)
      if (match) bytes += parseFloat(match[1])
    }
    if (bytes >= 1024) return (bytes / 1024).toFixed(2) + ' GB'
    return bytes.toFixed(2) + ' MB'
  }, [images])

  const loadImages = useCallback(() => {
    setLoading(true)
    getImages()
      .then(setImages)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const prune = usePruneDialog({ notify, t, loadImages })

  const pruneByIdleCandidates = useMemo(() => {
    if (prune.mode !== 'byDate') return []
    const cutoff = Date.now() - prune.minDays * 24 * 60 * 60 * 1000
    return images.filter(img => {
      if (img.inUse) return false
      if (!img.lastUsedAt) return true
      // Parse dd/MM/yyyy HH:mm:ss format
      const parts = img.lastUsedAt.match(/^(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2}):(\d{2})$/)
      const ts = parts
        ? new Date(+parts[3], +parts[2] - 1, +parts[1], +parts[4], +parts[5], +parts[6]).getTime()
        : new Date(img.lastUsedAt).getTime()
      return !isNaN(ts) && ts < cutoff
    })
  }, [images, prune.mode, prune.minDays])

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

      <Box sx={{ maxWidth: { xs: '95%', md: '90%', lg: '85%' }, mx: 'auto', mt: 5, mb: 4 }}>
        <Typography variant="h4" fontWeight="bold" sx={{ mb: 3 }}>
          {t('images.title')}
        </Typography>

        {!loading && images.length > 0 && (
          <Paper elevation={2} sx={{ p: 2.5, mb: 3, borderRadius: 2 }}>
            <Box sx={{ display: 'flex', gap: 4, flexWrap: 'wrap', justifyContent: 'space-around' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <PhotoLibrary color="primary" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{images.length}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('images.overview.total')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <CheckCircle color="success" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{inUseCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('images.overview.inUse')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <RemoveCircleOutline color={unusedCount > 0 ? 'warning' : 'disabled'} sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{unusedCount}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('images.overview.unused')}</Typography>
                </Box>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <DataUsage color="info" sx={{ fontSize: 32 }} />
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2, fontFamily: "'JetBrains Mono', monospace" }}>{totalSize}</Typography>
                  <Typography sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.06em', fontSize: '0.65rem', fontWeight: 600 }}>{t('images.overview.totalSize')}</Typography>
                </Box>
              </Box>
            </Box>
          </Paper>
        )}

        <Box sx={{ display: 'flex', gap: 2, mb: 3, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            placeholder={t('images.searchPlaceholder')}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            size="small"
            sx={{ flex: 1, minWidth: 200 }}
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
          <FormControlLabel
            control={
              <Switch
                checked={showUnusedOnly}
                onChange={(e) => setShowUnusedOnly(e.target.checked)}
                size="small"
              />
            }
            label={
              <Typography variant="body2">
                {t('images.showUnusedOnly')} ({unusedCount})
              </Typography>
            }
          />
          {canManageImages && (
            <Tooltip title={t('images.cleanUpByIdleDesc')}>
              <Button
                variant="contained"
                color="warning"
                startIcon={<CleaningServices />}
                onClick={() => prune.open('byDate')}
                disabled={unusedCount === 0}
                size="small"
              >
                {t('images.cleanUpByIdle')}
              </Button>
            </Tooltip>
          )}
          {canManageImages && (
            <Tooltip title={t('images.removeAllUnusedDesc')}>
              <Button
                variant="contained"
                color="error"
                startIcon={<DeleteSweep />}
                onClick={() => prune.open('all')}
                disabled={unusedCount === 0}
                size="small"
              >
                {t('images.removeAllUnused')}
              </Button>
            </Tooltip>
          )}
        </Box>

        <Paper elevation={2} sx={{ borderRadius: 2 }}>
          <TableContainer ref={tableRef}>
            <Table stickyHeader aria-label="Docker images">
            <TableHead>
              <TableRow>
                {IMAGE_COLUMNS.map((col) => (
                  <TableCell key={col.key} sx={{ bgcolor: theadBg, color: theadColor, fontWeight: 600 }}>
                    {col.sortable ? (
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
                  <TableCell colSpan={IMAGE_COLUMNS.length} align="center" sx={{ py: 4 }}>
                    <CircularProgress size={28} />
                  </TableCell>
                </TableRow>
              )}
              {!loading && filtered.length === 0 && (
                <TableRow>
                  <TableCell colSpan={IMAGE_COLUMNS.length} align="center" sx={{ py: 4, color: 'text.secondary' }}>
                    {t('images.noImagesFound')}
                  </TableCell>
                </TableRow>
              )}
              {pagination.paginatedData.map((img) => (
                <TableRow
                  key={img.imageId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onContextMenu={(e) => {
                    e.preventDefault()
                    imageMenu.openByPosition({ top: e.clientY, left: e.clientX }, img)
                  }}
                >
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                      <Typography variant="body2" sx={{ fontWeight: 600, fontFamily: "'JetBrains Mono', monospace" }}>{img.repository}</Typography>
                      {img.parentId && (
                        <Tooltip title={t('images.parentImage', { id: img.parentId })}>
                          <AccountTree sx={{ fontSize: 16, color: 'text.secondary' }} />
                        </Tooltip>
                      )}
                      {img.childIds && img.childIds.length > 0 && (
                        <Tooltip title={t('images.childImages', { count: img.childIds.length })}>
                          <Info sx={{ fontSize: 16, color: 'info.main' }} />
                        </Tooltip>
                      )}
                    </Box>
                  </TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{img.tag}</TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>{img.imageId}</TableCell>
                  <TableCell>{formatBackendDate(img.created)}</TableCell>
                  <TableCell sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.85rem' }}>{img.size}</TableCell>
                  <TableCell>
                    <Tooltip title={img.inUse ? t('images.usedByContainers', { count: img.containerCount }) : ''}>
                      <Chip
                        label={img.inUse ? t('images.inUse') : t('images.unused')}
                        color={img.inUse ? 'success' : 'default'}
                        size="small"
                        variant="outlined"
                      />
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={getLastUsedLabel(img.lastUsedAt, img.inUse, t('images.activeNow'), t('images.neverUsed'))}
                      color={getLastUsedColor(img.lastUsedAt, img.inUse as boolean)}
                      size="small"
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>
                    {canManageImages && (
                      <Tooltip title={t('common.remove')}>
                        <IconButton
                          size="small"
                          color="error"
                          onClick={() => handleRemove(img.imageId)}
                        >
                          <Delete />
                        </IconButton>
                      </Tooltip>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          </TableContainer>
        </Paper>
        <TablePagination
          component="div"
          count={pagination.totalCount}
          page={pagination.page}
          onPageChange={pagination.handleChangePage}
          rowsPerPage={pagination.rowsPerPage}
          onRowsPerPageChange={pagination.handleChangeRowsPerPage}
          rowsPerPageOptions={[10, 25, 50, 100]}
          labelRowsPerPage={t('common.rowsPerPage')}
        />
      </Box>

      <Menu
        open={imageMenu.menuOpen && imageMenu.target !== null}
        onClose={imageMenu.close}
        anchorReference="anchorPosition"
        anchorPosition={imageMenu.contextMenuPos ?? undefined}
        slotProps={{ ...imageMenu.menuSlotProps, paper: { sx: { minWidth: 200 } } }}
      >
        {imageMenu.target && canManageImages && (
          <MenuItem
            onClick={() => {
              handleRemove(imageMenu.target!.imageId)
              imageMenu.close()
            }}
            sx={{ color: 'error.main' }}
          >
            <ListItemIcon><Delete fontSize="small" color="error" /></ListItemIcon>
            <ListItemText>{t('common.remove')}</ListItemText>
          </MenuItem>
        )}
      </Menu>

      {/* Remove Image SSE Dialog */}
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

      {/* Prune Confirmation Dialog */}
      <Dialog open={prune.mode !== null} onClose={prune.close} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: prune.mode === 'all' ? 'error.main' : 'warning.main', color: 'white' }}>
          {prune.mode === 'all'
            ? <><DeleteSweep sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('images.removeAllUnused')}</>
            : <><CleaningServices sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('images.cleanUpByIdle')}</>
          }
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          {prune.mode === 'all' ? (
            <Typography sx={{ mb: 3 }}>
              {t('images.confirmPruneAll')}
            </Typography>
          ) : (
            <>
              <Typography sx={{ mb: 2 }}>
                {t('images.confirmPruneByDate')}
              </Typography>

              <Alert severity="info" icon={<Warning />} sx={{ mb: 3 }}>
                {t('images.idleTrackingWarning')}
              </Alert>

              <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
                {t('images.minDaysLabel')}
              </Typography>
              <Box sx={{ px: 2, mb: 3 }}>
                <Slider
                  value={prune.minDays}
                  onChange={(_, v) => prune.setMinDays(v as number)}
                  min={1}
                  max={90}
                  step={1}
                  marks={DAY_MARKS}
                  valueLabelDisplay="auto"
                  valueLabelFormat={(v) => t('images.daysValue', { count: v })}
                />
              </Box>

              {pruneByIdleCandidates.length > 0 ? (
                <>
                  <Alert severity="warning" sx={{ mb: 1 }}>
                    <Typography variant="body2" fontWeight={600} sx={{ mb: 0.5 }}>
                      {t('images.cleanupAffected', { count: pruneByIdleCandidates.length })}
                    </Typography>
                    <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                      {pruneByIdleCandidates.map((img) => (
                        <li key={img.imageId}>
                          <Typography variant="body2" sx={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '0.8rem' }}>
                            {img.repository}:{img.tag}
                            <Typography component="span" variant="caption" sx={{ ml: 1, color: 'text.secondary' }}>
                              ({img.size})
                            </Typography>
                          </Typography>
                        </li>
                      ))}
                    </Box>
                  </Alert>
                  <Alert severity="info" sx={{ mb: 3 }}>
                    {t('images.parentImageSkipWarning')}
                  </Alert>
                </>
              ) : (
                <Alert severity="success" sx={{ mb: 3 }}>
                  {t('images.cleanupNoneAffected')}
                </Alert>
              )}
            </>
          )}

          {!rbacEnabled && (
            <TextField
              fullWidth
              type="password"
              label={t('common.operationsPassword')}
              value={prune.password}
              onChange={(e) => prune.setPassword(e.target.value)}
              size="small"
              autoComplete="off"
              error={!!prune.error}
              helperText={prune.error}
            />
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          <Button onClick={prune.close} color="inherit" disabled={prune.preparing}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="contained"
            color={prune.mode === 'all' ? 'error' : 'warning'}
            onClick={prune.confirm}
            disabled={prune.preparing || (!rbacEnabled && !prune.password)}
            startIcon={prune.preparing ? <CircularProgress size={20} /> : (prune.mode === 'all' ? <DeleteSweep /> : <CleaningServices />)}
          >
            {prune.preparing ? t('common.preparing') : t('common.confirm')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Prune SSE Progress Dialog */}
      <Dialog open={prune.pruneSse.events.length > 0} onClose={prune.closeProgress} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ bgcolor: 'warning.main', color: 'white' }}>
          <DeleteSweep sx={{ mr: 1, verticalAlign: 'middle' }} /> {t('images.pruningImages')}
        </DialogTitle>
        <DialogContent dividers sx={{ pt: 3 }}>
          <OperationProgress events={prune.pruneSse.events} steps={PRUNE_IMAGES_STEPS} />
        </DialogContent>
        <DialogActions sx={{ px: 3, py: 2 }}>
          {(prune.pruneSse.hasError || prune.pruneSse.isDone) && (
            <Button onClick={prune.closeProgress} color="inherit">{t('common.close')}</Button>
          )}
        </DialogActions>
      </Dialog>
    </>
  )
}
