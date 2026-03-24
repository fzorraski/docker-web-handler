// Client-side table pagination: manages page index, rows-per-page, and slices data.
import { useState, useEffect, useMemo, useCallback } from 'react'

const STORAGE_PREFIX = 'tablePagination_'
const ROWS_PER_PAGE_OPTIONS = [10, 25, 50, 100]

function readStoredRowsPerPage(storageKey: string | undefined, fallback: number): number {
  if (!storageKey) return fallback
  try {
    const stored = localStorage.getItem(STORAGE_PREFIX + storageKey)
    if (stored) {
      const n = Number(stored)
      if (Number.isFinite(n) && n > 0) return n
    }
  } catch { /* ignore */ }
  return fallback
}

interface Options {
  storageKey?: string
  defaultRowsPerPage?: number
}

export function useTablePagination<T>(data: T[], options?: Options) {
  const { storageKey, defaultRowsPerPage = 10 } = options ?? {}

  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(() => readStoredRowsPerPage(storageKey, defaultRowsPerPage))

  // Reset to last valid page when data shrinks (e.g. after filtering)
  useEffect(() => {
    if (data.length > 0 && page * rowsPerPage >= data.length) {
      setPage(Math.max(0, Math.ceil(data.length / rowsPerPage) - 1))
    }
  }, [data.length, page, rowsPerPage])

  const paginatedData = useMemo(
    () => rowsPerPage > 0 ? data.slice(page * rowsPerPage, page * rowsPerPage + rowsPerPage) : data,
    [data, page, rowsPerPage],
  )

  const handleChangePage = useCallback((_: unknown, newPage: number) => {
    setPage(newPage)
  }, [])

  const handleChangeRowsPerPage = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const value = parseInt(e.target.value, 10)
    if (!Number.isFinite(value) || value < 1) return
    setRowsPerPage(value)
    setPage(0)
    if (storageKey) {
      try { localStorage.setItem(STORAGE_PREFIX + storageKey, String(value)) } catch { /* ignore */ }
    }
  }, [storageKey])

  return {
    page,
    rowsPerPage,
    paginatedData,
    totalCount: data.length,
    rowsPerPageOptions: ROWS_PER_PAGE_OPTIONS,
    handleChangePage,
    handleChangeRowsPerPage,
  }
}
