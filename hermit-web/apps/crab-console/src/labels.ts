export const outcomeLabels: Record<string, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' }> = {
  INSTALLED: { text: '已安装', tone: 'success' },
  SKIPPED_SAME: { text: '已是最新', tone: 'info' },
  SKIPPED_MIN_VERSION: { text: 'Crab 版本过低', tone: 'warning' },
  SKIPPED_PINNED: { text: '已固定版本', tone: 'info' },
  REJECTED: { text: '已拒绝', tone: 'danger' },
  ROLLED_BACK: { text: '已回退', tone: 'warning' },
  DISABLED: { text: '已停用', tone: 'danger' },
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
}

export const actorLabels: Record<string, string> = {
  cli: '命令行',
  console: '控制台',
  sync: '同步',
  migration: '升级迁移',
}
