import { describe, expect, it } from 'vitest'
import css from '../shared/theme.css?raw'

function block(selector: string): Map<string, string> {
  const start = css.indexOf(`${selector} {`)
  const body = css.slice(start, css.indexOf('}', start))
  return new Map([...body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()] as const))
}

const vars = block('html:root')
/** 暗色块只写差异，没写的变量沿用浅色块。 */
const dark = new Map([...vars, ...block('html.dark')])

function resolve(name: string, from: Map<string, string> = vars): string {
  const value = from.get(name)
  if (value === undefined) {
    throw new Error(`${name} 未定义`)
  }
  const ref = /^var\((--[\w-]+)\)$/.exec(value)
  return ref ? resolve(ref[1], from) : value
}

function mix(base: string, color: string, weight: number): string {
  const rgb = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
  const a = rgb(base)
  const b = rgb(color)
  return `#${a.map((v, i) => Math.round(v * weight + b[i] * (1 - weight)).toString(16).padStart(2, '0')).join('')}`
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

const semantic = [
  ['success', '--hm-success'],
  ['warning', '--hm-warning'],
  ['danger', '--hm-danger'],
  ['info', '--hm-muted'],
] as const

describe('Element 语义色', () => {
  it.each(semantic)('%s 指向 %s', (name, token) => {
    expect(vars.get(`--el-color-${name}`)).toBe(`var(${token})`)
  })

  it.each(semantic)('%s 的明暗阶与 token 一致', (name, token) => {
    const base = resolve(name === 'warning' ? '--hm-accent' : token)
    for (const level of [3, 5, 7, 8, 9]) {
      expect(resolve(`--el-color-${name}-light-${level}`)).toBe(mix('#ffffff', base, level / 10))
    }
    if (name !== 'warning') {
      expect(resolve(`--el-color-${name}-dark-2`)).toBe(mix('#000000', base, 0.2))
    }
  })

  it.each([
    ['--hm-warning', '#ffffff'],
    ['--hm-warning', '--el-color-warning-light-9'],
    ['--el-color-warning-dark-2', '--el-color-warning-light-9'],
    ['--hm-danger', '#ffffff'],
    ['--hm-danger', '--el-color-danger-light-9'],
  ])('浅色 %s 在 %s 上对比度不低于 4.5:1', (fg, bg) => {
    const back = bg.startsWith('#') ? bg : resolve(bg)
    expect(contrast(resolve(fg), back)).toBeGreaterThanOrEqual(4.5)
  })

  it('error 与 danger 同色', () => {
    for (const suffix of ['', '-light-3', '-light-5', '-light-7', '-light-8', '-light-9', '-dark-2']) {
      expect(resolve(`--el-color-error${suffix}`)).toBe(resolve(`--el-color-danger${suffix}`))
    }
  })
})

describe('文字对比度', () => {
  it('成功绿当小字用时在白底上不低于 4.5:1', () => {
    expect(contrast(resolve('--hm-success'), '#ffffff')).toBeGreaterThanOrEqual(4.5)
  })

  it('暗色实心主按钮的文字对比度不低于 4.5:1', () => {
    const rule = /html\.dark \.el-button--primary[^{]*\{([^}]*)\}/.exec(css)
    expect(rule).not.toBeNull()
    const text = /--el-button-text-color:\s*(#[0-9a-f]{6})/i.exec(rule![1])![1]
    expect(contrast(resolve('--el-color-primary', dark), text)).toBeGreaterThanOrEqual(4.5)
  })
})

describe('暗色语义色', () => {
  const darkOwn = block('html.dark')
  it.each(semantic)('暗色块显式声明 %s，避免被 Element 暗色样式盖回默认色', (name, token) => {
    expect(darkOwn.get(`--el-color-${name}`)).toBe(`var(${token})`)
  })

  const names: [string, string][] = ['primary', ...semantic.map(([n]) => n)].map((n) => [n, `--el-color-${n}`])
  it.each(names)(
    '%s 的明暗阶按 Element 暗色算法与底色 #141414 混合',
    (name, token) => {
      const base = resolve(token, dark)
      for (const level of [3, 5, 7, 8, 9]) {
        expect(resolve(`--el-color-${name}-light-${level}`, dark)).toBe(mix('#141414', base, level / 10))
      }
      expect(resolve(`--el-color-${name}-dark-2`, dark)).toBe(mix('#ffffff', base, 0.2))
    },
  )

  it('error 与 danger 同色', () => {
    for (const suffix of ['', '-light-3', '-light-5', '-light-7', '-light-8', '-light-9', '-dark-2']) {
      expect(resolve(`--el-color-error${suffix}`, dark)).toBe(resolve(`--el-color-danger${suffix}`, dark))
    }
  })

  it('暗色危险色在卡片和危险底色上对比度不低于 4.5:1', () => {
    const danger = resolve('--hm-danger', dark)
    expect(contrast(danger, resolve('--hm-surface', dark))).toBeGreaterThanOrEqual(4.5)
    expect(contrast(danger, resolve('--el-color-danger-light-9', dark))).toBeGreaterThanOrEqual(4.5)
  })

  it.each([
    ['浅色', vars],
    ['暗色', dark],
  ])('%s实色危险按钮白字对比度不低于 4.5:1', (_name, from) => {
    for (const token of ['--hm-danger-solid', '--hm-danger-solid-hover']) {
      expect(contrast('#ffffff', resolve(token, from))).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('实色危险按钮用 --hm-danger-solid，plain/text/link 不受影响', () => {
    expect(css).toMatch(
      /html \.el-button--danger:not\(\.is-plain\):not\(\.is-text\):not\(\.is-link\) \{[^}]*--el-button-bg-color: var\(--hm-danger-solid\);/,
    )
  })

  it('暗色 plain 标签边框跟随文字色', () => {
    expect(css).toMatch(/html\.dark \.el-tag--plain \{\s*--el-tag-border-color: currentColor;/)
  })
})
