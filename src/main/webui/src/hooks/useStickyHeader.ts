import { useEffect, useRef } from 'react'

function getScrollParent(el: HTMLElement): HTMLElement {
  let node = el.parentElement
  while (node) {
    const { overflowY } = getComputedStyle(node)
    if (overflowY === 'auto' || overflowY === 'scroll') return node
    node = node.parentElement
  }
  return document.documentElement
}

export function useStickyHeader(containerRef: React.RefObject<HTMLElement | null>) {
  const raf = useRef(0)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const thead = el.querySelector('thead') as HTMLElement | null
    const tbody = el.querySelector('tbody') as HTMLElement | null
    if (!thead) return

    // Safari: tbody needs its own stacking context so thead z-index is respected
    if (tbody) {
      tbody.style.position = 'relative'
      tbody.style.zIndex = '1'
    }

    const scroller = getScrollParent(el)

    const tick = () => {
      const box = el.getBoundingClientRect()
      const top =
        scroller === document.documentElement
          ? 0
          : scroller.getBoundingClientRect().top

      const dy = top - box.top
      const max = el.offsetHeight - thead.offsetHeight

      if (dy > 0 && dy < max) {
        thead.style.transform = `translateY(${dy}px)`
        thead.style.position = 'relative'
        thead.style.zIndex = '4'
        thead.style.willChange = 'transform'
        thead.style.boxShadow = '0 2px 6px rgba(0,0,0,0.25)'
      } else {
        thead.style.transform = ''
        thead.style.position = ''
        thead.style.zIndex = ''
        thead.style.willChange = ''
        thead.style.boxShadow = ''
      }
    }

    const onScroll = () => {
      cancelAnimationFrame(raf.current)
      raf.current = requestAnimationFrame(tick)
    }

    scroller.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll, { passive: true })

    return () => {
      cancelAnimationFrame(raf.current)
      scroller.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
      thead.style.transform = ''
      thead.style.position = ''
      thead.style.zIndex = ''
      thead.style.willChange = ''
      thead.style.boxShadow = ''
      if (tbody) {
        tbody.style.position = ''
        tbody.style.zIndex = ''
      }
    }
  }, [containerRef])
}
