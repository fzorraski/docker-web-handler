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
  fetchFn: () => Promise<PaginatedResult<T>>,
  deps: unknown[],
): UsePaginatedFetchResult<T> {
  const [data, setData] = useState<T[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const genRef = useRef(0)

  useEffect(() => {
    setLoading(true)
    setError(null)
    const gen = ++genRef.current
    fetchFn()
      .then((r) => {
        if (gen !== genRef.current) return
        setData(r.data)
        setTotal(r.total)
      })
      .catch((err) => {
        if (gen !== genRef.current) return
        setError(err?.message ?? 'Fetch failed')
      })
      .finally(() => {
        if (gen === genRef.current) setLoading(false)
      })
  }, deps)

  return { data, total, loading, error }
}
