export const outcomeLabels: Record<string, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' }> = {
  INSTALLED: { text: '已安装', tone: 'success' },
  SKIPPED_SAME: { text: '已是最新', tone: 'info' },
  SKIPPED_MIN_VERSION: { text: 'Crab 版本过低', tone: 'warning' },
  SKIPPED_PINNED: { text: '已固定版本', tone: 'info' },
  REJECTED: { text: '已拒绝', tone: 'danger' },
}

export const originLabels: Record<string, string> = {
  OFFICIAL: '官方',
  LOCAL: '本地',
}

export const stepTypeLabels: Record<string, string> = {
  request: '请求',
  extract: '提取',
  assert: '断言',
  set: '赋值',
  script: '脚本',
}
