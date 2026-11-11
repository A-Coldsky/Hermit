export const outcomeLabels: Record<string, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' }> = {
  INSTALLED: { text: '已安装', tone: 'success' },
  SKIPPED_SAME: { text: '已是最新', tone: 'info' },
  SKIPPED_MIN_VERSION: { text: 'Crab 版本过低', tone: 'warning' },
  UPDATE_AVAILABLE: { text: '有新版本', tone: 'warning' },
  SKIPPED_OLDER: { text: '清单版本更低', tone: 'info' },
  SKIPPED_PINNED: { text: '已固定版本', tone: 'info' },
  REJECTED: { text: '已拒绝', tone: 'danger' },
  ROLLED_BACK: { text: '已回退', tone: 'warning' },
  DISABLED: { text: 'Reef 已停用，不再更新', tone: 'warning' },
  SKIPPED_LOCAL: { text: '本地同名，未安装', tone: 'warning' },
  NOT_SUBSCRIBED: { text: '未订阅', tone: 'info' },
  REPAIR_REQUIRED: { text: '需要修复', tone: 'warning' },
  RULE_DISABLED: { text: '新规则下已停用', tone: 'danger' },
  REPAIRED: { text: '已从本机重新载入', tone: 'success' },
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
  REEF_CONCH_DISABLED: { text: 'Reef 已停用', tone: 'warning' },
  REEF_CONCH_ENABLED: { text: 'Reef 重新启用', tone: 'success' },
  CONCH_UPDATED: { text: '更新规则包', tone: 'success' },
  REEF_BOOTSTRAPPED: { text: '连接 Reef', tone: 'success' },
  REPORT_SETTINGS_CHANGED: { text: '修改上报设置', tone: 'info' },
  CONCH_REPAIR_REQUIRED: { text: '同步发现需要修复', tone: 'warning' },
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
  POINTER_MISSING: '本机版本指针丢失，同步不会重新下载',
  NOT_LOADED: '本机文件还在，但这次启动没有载入',
  RULE_DISABLED: '新规则下已停用',
  RESTORED: '已重新载入本机验过签的版本',
  BAD_SIGNATURE: '本机文件验签失败',
  DIGEST_MISMATCH: '本机文件摘要与签名不一致',
  UNREADABLE: '本机文件读不出来',
  YAML_INVALID: '本机规则包 YAML 无效',
  MANIFEST_INVALID: '本机规则包清单无效',
  MANIFEST_MISMATCH: '本机规则包与清单不一致',
  ENTRY_SET: '本机规则包的文件列表不对',
  DUPLICATE_ENTRY: '本机规则包有重复文件',
  ENTRY_TOO_LARGE: '本机规则包里有文件过大',
}

export const channelLabels: Record<string, string> = {
  HTTP: 'OpenAPI',
  MCP: 'MCP',
  CONSOLE: '控制台',
}

export const actorLabels: Record<string, string> = {
  cli: '命令行',
  console: '控制台',
  mcp: 'MCP',
  sync: '同步',
  migration: '升级迁移',
}

export function reefImportErrorText(message: string): string | null {
  if (message === 'reef rejected the token') return 'Reef 拒绝了这把令牌：可能已被吊销、所属用户被禁用，或 JSON 被改过。请在 Reef 重新生成'
  if (message === 'cannot reach reef') return '连不上 Reef：确认 JSON 里的地址能从本机访问，Reef 正在运行'
  if (message === 'public key differs from installed conches')
    return '这个 Reef 的签名公钥和本机已装的正式规则包不一致。要换到另一个 Reef，先取消订阅全部正式规则包'
  if (message.startsWith('reef url did not answer as Reef')) return 'JSON 里的地址不是 Reef：请检查地址是否填错（端口、路径）'
  return null
}
