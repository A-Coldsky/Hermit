export const alertKindLabels: Record<string, string> = {
  NEW_VERSION_REGRESSION: '新版本失败率上升',
  RULE_MISMATCH_SURGE: '规则不匹配激增',
  UPSTREAM_ERROR_SURGE: '老系统错误激增',
}

export const auditActionLabels: Record<string, string> = {
  LOGIN_SUCCESS: '登录成功',
  LOGIN_FAILURE: '登录失败',
  UPLOAD: '上传',
  PUBLISH: '发布',
  ROLLBACK: '回滚',
}

export const versionStatusLabels: Record<string, string> = {
  UPLOADED: '已上传',
  PUBLISHED: '已发布',
}

export const credentialTypeLabels: Record<string, string> = {
  text: '文本',
  secret: '密文',
  select: '选项',
  url: '地址',
}

export function isoDaysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString()
}
