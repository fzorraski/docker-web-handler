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
    if (!thead) return

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
      } else {
        thead.style.transform = ''
        thead.style.position = ''
        thead.style.zIndex = ''
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
    }
  }, [containerRef])
}
