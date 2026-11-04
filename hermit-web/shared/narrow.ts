import { onBeforeUnmount, ref, type Ref } from 'vue'

export const NARROW_QUERY = '(max-width: 640px)'

/** 与 theme.css 的窄屏断点一致。jsdom 等没有 matchMedia 的环境按宽屏处理。 */
export function useNarrow(): Ref<boolean> {
  const narrow = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return narrow
  }
  const media = window.matchMedia(NARROW_QUERY)
  narrow.value = media.matches
  const onChange = (event: MediaQueryListEvent) => {
    narrow.value = event.matches
  }
  media.addEventListener('change', onChange)
  onBeforeUnmount(() => media.removeEventListener('change', onChange))
  return narrow
}
