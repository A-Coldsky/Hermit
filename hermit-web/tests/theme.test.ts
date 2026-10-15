import { describe, expect, it } from 'vitest'
import css from '../shared/theme.css?raw'

const root = css.slice(css.indexOf(':root'), css.indexOf('}'))
const vars = new Map(
  [...root.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()] as const),
)

function resolve(name: string): string {
  const value = vars.get(name)
  if (value === undefined) {
    throw new Error(`${name} 未定义`)
  }
  const ref = /^var\((--[\w-]+)\)$/.exec(value)
  return ref ? resolve(ref[1]) : value
}

function mix(base: string, color: string, weight: number): string {
  const rgb = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
  const a = rgb(base)
  const b = rgb(color)
  return `#${a.map((v, i) => Math.round(v * weight + b[i] * (1 - weight)).toString(16).padStart(2, '0')).join('')}`
}

const semantic = [
  ['success', '--hm-success'],
  ['warning', '--hm-warning'],
  ['danger', '--hm-danger'],
] as const

describe('Element 语义色', () => {
  it.each(semantic)('%s 指向 %s', (name, token) => {
    expect(vars.get(`--el-color-${name}`)).toBe(`var(${token})`)
  })

  it.each(semantic)('%s 的明暗阶与 token 一致', (name, token) => {
    const base = resolve(token)
    for (const level of [3, 5, 7, 8, 9]) {
      expect(resolve(`--el-color-${name}-light-${level}`)).toBe(mix('#ffffff', base, level / 10))
    }
    expect(resolve(`--el-color-${name}-dark-2`)).toBe(mix('#000000', base, 0.2))
  })

  it('error 与 danger 同色', () => {
    for (const suffix of ['', '-light-3', '-light-5', '-light-7', '-light-8', '-light-9', '-dark-2']) {
      expect(resolve(`--el-color-error${suffix}`)).toBe(resolve(`--el-color-danger${suffix}`))
    }
  })
})
