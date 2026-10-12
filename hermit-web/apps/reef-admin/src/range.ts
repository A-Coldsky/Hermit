import { isoDaysAgo } from './labels'

export interface RangePreset {
  key: string
  label: string
  days: number
  bucket: 'hour' | 'day'
}

export const rangePresets: RangePreset[] = [
  { key: '1d', label: '24 小时', days: 1, bucket: 'hour' },
  { key: '7d', label: '7 天', days: 7, bucket: 'day' },
  { key: '30d', label: '30 天', days: 30, bucket: 'day' },
  { key: '90d', label: '90 天', days: 90, bucket: 'day' },
]

export function presetOf(key: string): RangePreset {
  return rangePresets.find((p) => p.key === key) ?? rangePresets[1]
}

export function rangeQuery(key: string): { from: string; bucket: 'hour' | 'day' } {
  const preset = presetOf(key)
  return { from: isoDaysAgo(preset.days), bucket: preset.bucket }
}
