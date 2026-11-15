import type { BarPoint } from '@shared/components/BarChart.vue'
import type { TidePoint } from './api'

export interface ActionTotals {
  conch: string
  action: string
  success: number
  failure: number
  failureRate: number
  avgMillis: number | null
  p95Millis: number | null
}

export function bucketLabel(value: string, bucket: string): string {
  const date = new Date(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  const day = `${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  return bucket === 'hour' ? `${day} ${pad(date.getHours())}:00` : day
}

/** 本地时区的零点（按天）或整点（按小时）。服务端按同一个时区切日，键能对上。 */
function bucketFloor(time: number, bucket: string): number {
  const date = new Date(time)
  if (bucket === 'hour') {
    date.setMinutes(0, 0, 0)
  } else {
    date.setHours(0, 0, 0, 0)
  }
  return date.getTime()
}

function nextBucket(time: number, bucket: string): number {
  const date = new Date(time)
  if (bucket === 'hour') {
    date.setHours(date.getHours() + 1)
  } else {
    date.setDate(date.getDate() + 1)
  }
  return date.getTime()
}

export function toBars(series: TidePoint[], bucket: string, from?: string, to?: string): BarPoint[] {
  const byBucket = new Map<number, BarPoint>()
  const start = from ? bucketFloor(new Date(from).getTime(), bucket) : NaN
  const end = to ? new Date(to).getTime() : NaN
  const limit = bucket === 'hour' ? 24 * 92 : 400
  if (Number.isFinite(start) && Number.isFinite(end)) {
    let count = 0
    for (let t = start; t < end && count < limit; t = nextBucket(t, bucket), count++) {
      byBucket.set(t, { label: bucketLabel(new Date(t).toISOString(), bucket), success: 0, failure: 0 })
    }
  }
  for (const point of series) {
    const t = new Date(point.bucketStart).getTime()
    let bar = byBucket.get(t)
    if (!bar) {
      bar = { label: bucketLabel(point.bucketStart, bucket), success: 0, failure: 0 }
      byBucket.set(t, bar)
    }
    bar.success += point.success
    bar.failure += point.failure
  }
  return [...byBucket.entries()].sort(([a], [b]) => a - b).map(([, bar]) => bar)
}

export function totalsByAction(series: TidePoint[]): ActionTotals[] {
  const map = new Map<string, ActionTotals & { weighted: number; weightedCalls: number }>()
  for (const point of series) {
    const key = `${point.conch}\u0000${point.action}`
    let row = map.get(key)
    if (!row) {
      row = {
        conch: point.conch,
        action: point.action,
        success: 0,
        failure: 0,
        failureRate: 0,
        avgMillis: null,
        p95Millis: null,
        weighted: 0,
        weightedCalls: 0,
      }
      map.set(key, row)
    }
    row.success += point.success
    row.failure += point.failure
    const calls = point.success + point.failure
    if (point.avgMillis !== null && calls > 0) {
      row.weighted += point.avgMillis * calls
      row.weightedCalls += calls
    }
    if (point.p95Millis !== null) {
      row.p95Millis = Math.max(row.p95Millis ?? 0, point.p95Millis)
    }
  }
  return [...map.values()]
    .map(({ weighted, weightedCalls, ...row }) => ({
      ...row,
      failureRate: row.success + row.failure ? row.failure / (row.success + row.failure) : 0,
      avgMillis: weightedCalls ? weighted / weightedCalls : null,
    }))
    .sort((a, b) => b.success + b.failure - (a.success + a.failure))
}

export function totals(series: TidePoint[]): { calls: number; failures: number; rate: number } {
  let calls = 0
  let failures = 0
  for (const point of series) {
    calls += point.success + point.failure
    failures += point.failure
  }
  return { calls, failures, rate: calls ? failures / calls : 0 }
}
