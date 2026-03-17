import { useState, useMemo } from 'react'

type SortDirection = 'asc' | 'desc'

interface UseTableSortOptions<T> {
  data: T[]
  filterFn: (item: T, query: string) => boolean
  sortValueFn: (item: T, key: string) => string | number
  nonsortableKeys?: string[]
}

interface UseTableSortResult<T> {
  filter: string
  setFilter: (value: string) => void
  sortKey: string
  sortDir: SortDirection
  handleSort: (key: string) => void
  sorted: T[]
}

export function useTableSort<T>({
  data,
  filterFn,
  sortValueFn,
  nonsortableKeys = ['action', 'actions'],
}: UseTableSortOptions<T>): UseTableSortResult<T> {
  const [filter, setFilter] = useState('')
  const [sortKey, setSortKey] = useState('')
  const [sortDir, setSortDir] = useState<SortDirection>('asc')

  function handleSort(key: string) {
    if (nonsortableKeys.includes(key)) return
    setSortDir(sortKey === key && sortDir === 'asc' ? 'desc' : 'asc')
    setSortKey(key)
  }

  const sorted = useMemo(() => {
    const filtered = data.filter((item) => filterFn(item, filter))
    if (!sortKey) return filtered
    return [...filtered].sort((a, b) => {
      const va = sortValueFn(a, sortKey)
      const vb = sortValueFn(b, sortKey)
      const cmp = typeof va === 'number' && typeof vb === 'number'
        ? va - vb
        : String(va).toLowerCase().localeCompare(String(vb).toLowerCase())
      return sortDir === 'asc' ? cmp : -cmp
    })
  }, [data, filter, sortKey, sortDir, filterFn, sortValueFn])

  return { filter, setFilter, sortKey, sortDir, handleSort, sorted }
}
