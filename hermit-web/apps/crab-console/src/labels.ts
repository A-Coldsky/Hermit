export const outcomeLabels: Record<string, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' }> = {
  INSTALLED: { text: '已安装', tone: 'success' },
  SKIPPED_SAME: { text: '已是最新', tone: 'info' },
  SKIPPED_MIN_VERSION: { text: 'Crab 版本过低', tone: 'warning' },
  UPDATE_AVAILABLE: { text: '有新版本', tone: 'warning' },
  SKIPPED_OLDER: { text: '清单版本更低', tone: 'info' },
  SKIPPED_PINNED: { text: '已固定版本', tone: 'info' },
  REJECTED: { text: '已拒绝', tone: 'danger' },
  ROLLED_BACK: { text: '已回退', tone: 'warning' },
  DISABLED: { text: '已失效，不再更新', tone: 'warning' },
  SKIPPED_LOCAL: { text: '本地同名，未安装', tone: 'warning' },
  NOT_SUBSCRIBED: { text: '未订阅', tone: 'info' },
}

export const sourceLabels: Record<string, string> = {
  LOCAL: '本地',
  REEF: 'Reef',
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

export const configAuditLabels: Record<string, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' | 'primary' }> = {
  ACCOUNT_CREATED: { text: '新建账号', tone: 'success' },
  ACCOUNT_DELETED: { text: '删除账号', tone: 'danger' },
  DEFAULT_CHANGED: { text: '更换默认账号', tone: 'info' },
  CREDENTIALS_WRITTEN: { text: '修改凭证', tone: 'primary' },
  CONFIG_PUT: { text: '修改配置', tone: 'primary' },
  CONFIG_DELETED: { text: '清除配置', tone: 'warning' },
  API_KEY_CREATED: { text: '创建 API Key', tone: 'success' },
  API_KEY_REVOKED: { text: '吊销 API Key', tone: 'danger' },
  CAPABILITY_ENABLED: { text: '启用能力', tone: 'success' },
  CAPABILITY_DISABLED: { text: '停用能力', tone: 'warning' },
  DEBUG_CHANGED: { text: '切换调试', tone: 'info' },
  TEST_LOGIN: { text: '测试登录', tone: 'info' },
  SYNC_REQUESTED: { text: '手动同步', tone: 'info' },
  CREDENTIAL_DECLARATION: { text: '凭证声明变更', tone: 'warning' },
  CONCH_SUBSCRIBED: { text: '订阅规则包', tone: 'success' },
  CONCH_UNSUBSCRIBED: { text: '取消订阅', tone: 'warning' },
  LOCAL_CONCH_IMPORTED: { text: '导入本地规则包', tone: 'success' },
  LOCAL_CONCH_DELETED: { text: '删除本地规则包', tone: 'danger' },
  REEF_CONCH_DISABLED: { text: 'Reef 停用（失效）', tone: 'warning' },
  REEF_CONCH_ENABLED: { text: 'Reef 重新启用', tone: 'success' },
  CONCH_UPDATED: { text: '更新规则包', tone: 'success' },
}

export const syncReasonLabels: Record<string, string> = {
  SAME_VERSION: '已是 Reef 当前版本',
  MIN_CRAB_VERSION: '需要更高版本的 Crab',
  UPDATE_AVAILABLE: 'Reef 有更高版本，点「更新」后安装',
  OLDER_MANIFEST: 'Reef 当前版本低于本机，本机版本保持不变',
  ZIP_TOO_LARGE: '规则包文件过大',
  MANIFEST_ENTRY_MISMATCH: '下载的包与清单不一致',
  VERSION_INVALID: '版本号无效',
  CONCH_NAME: '规则包名称无效',
  PATH_UNSAFE: '路径不安全',
  CURRENT_WRITE_FAILED: '本机写入失败',
  REGISTRY_REJECTED: '规则包没有通过本机校验',
  CREDENTIAL_DELETE_FAILED: '清理已移除的凭证失败',
  PACKAGE_MISSING: '本机缺少这个版本的文件',
  DISABLED: 'Reef 已停用，本机版本仍可调用，不再更新',
  DISABLE_FAILED: '记录停用状态失败',
  LOCAL_KEPT: '本机已有同名本地规则包',
}

export const channelLabels: Record<string, string> = {
  HTTP: 'OpenAPI',
  MCP: 'MCP',
  CONSOLE: '控制台',
}

export const actorLabels: Record<string, string> = {
  cli: '命令行',
  console: '控制台',
  sync: '同步',
  migration: '升级迁移',
}
