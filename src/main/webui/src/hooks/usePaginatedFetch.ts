import { useState, useEffect, useRef } from 'react'

interface PaginatedResult<T> {
  data: T[]
  total: number
  page: number
  size: number
}

interface UsePaginatedFetchResult<T> {
  data: T[]
  total: number
  loading: boolean
  error: string | null
}

export function usePaginatedFetch<T>(
  fetchFn: (signal: AbortSignal) => Promise<PaginatedResult<T>>,
  deps: unknown[],
): UsePaginatedFetchResult<T> {
  const [data, setData] = useState<T[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const genRef = useRef(0)

  useEffect(() => {
    const controller = new AbortController()
    const gen = ++genRef.current
    setLoading(true)
    setError(null)
    fetchFn(controller.signal)
      .then((r) => {
        if (gen !== genRef.current) return
        setData(r.data)
        setTotal(r.total)
      })
      .catch((err) => {
        if (gen !== genRef.current) return
        if (err?.name === 'AbortError') return
        setError(err?.message ?? 'Fetch failed')
      })
      .finally(() => {
        if (gen === genRef.current) setLoading(false)
      })
    return () => controller.abort()
  }, deps)

  return { data, total, loading, error }
}
