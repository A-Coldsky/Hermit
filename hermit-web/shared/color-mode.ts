import { ref } from 'vue'

export type ColorMode = 'light' | 'dark'

/** 与 index.html 里的首帧脚本共用同一个键，改名要两边一起改。 */
export const COLOR_MODE_KEY = 'hermit.color-mode'

function stored(): ColorMode | null {
  try {
    const value = localStorage.getItem(COLOR_MODE_KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

function system(): ColorMode {
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const colorMode = ref<ColorMode>(stored() ?? system())

function apply(mode: ColorMode) {
  document.documentElement.classList.toggle('dark', mode === 'dark')
}

apply(colorMode.value)

export function toggleColorMode(): void {
  colorMode.value = colorMode.value === 'dark' ? 'light' : 'dark'
  apply(colorMode.value)
  try {
    localStorage.setItem(COLOR_MODE_KEY, colorMode.value)
  } catch {
    // 隐私模式下写不进去，只影响下次打开
  }
}
