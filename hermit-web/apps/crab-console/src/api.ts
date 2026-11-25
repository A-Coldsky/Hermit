import { type ApiError, createClient, seg } from '@shared/http'
import type { GroupDef } from '@shared/groups'

export type { GroupDef }

let unauthorized: (error: ApiError) => void = () => {}

/** 由路由注册，避免 api 反向依赖路由。 */
export function onUnauthorized(handler: (error: ApiError) => void): void {
  unauthorized = handler
}

const http = createClient({
  headers: () => ({ 'X-Hermit-Console': '1' }),
  onUnauthorized: (error) => unauthorized(error),
})
const base = '/console/api'

/** 未设置口令时 `required` 为 false，`authenticated` 恒为 true。 */
export interface ConsoleSession {
  required: boolean
  authenticated: boolean
}

export interface ConchSummary {
  conch: string
  version: string
  title: string
  capabilityCount: number
  /** 本机是否已订阅。未订阅的正式包不会出现在这份已加载列表里；本地包为 false。 */
  subscribed: boolean
  accountCount: number
  /**
   * 账号数为 0、缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停。
   * 已经有账号时，`required: false` 的凭证可以不填。
   * 概览「待配置」按这个字段计数。
   */
  needsConfiguration: boolean
  /** 任一账号自动登录已暂停。原因和恢复方法在账号行。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。 */
  source: 'LOCAL' | 'REEF'
  /** 缺省或空白为 `http`。 */
  driver: 'http' | 'mcp'
  /**
   * Reef 已停用。本机仍保留已安装版本，HTTP/MCP 照常调用；同步不再更新这份包。
   * 本地包为 false。
   */
  reefDisabled: boolean
  /** 整个规则包在本机被停用：所有能力不可调用，也不出现在 MCP 和 OpenAPI 里。未载入的包没有这个字段。 */
  switchedOff?: boolean
  /**
   * 本机其它已启用、实际访问同一网站（协议、主机、端口相同）的规则包名，已排序。
   * 按生效的 `base_url` 判断，配置里填的优先。只是提示：它们各自登录，不共用 Cookie。
   */
  sameSite?: string[]
  /**
   * 本机其它已启用、和它经同一个认证地址（`auth.origins`，门户或 SSO）登录、但不是同一网站的规则包名，已排序。
   * 一个包的认证地址是另一个包的认证地址或站点就算。只是提示：它们各自登录，不共用 Cookie。
   */
  sameAuth?: string[]
  /** 本机同名本地包的版本，没有为 null。和 reefVersion 都有值时，两份同名，只有 source 那份生效。 */
  localVersion?: string | null
  /** 本机同名正式包的版本，没有为 null。 */
  reefVersion?: string | null
  /** 本机第一次记下停用墓碑的时间，ISO-8601。未停用为 null。 */
  reefDisabledAt: string | null
  /** 最近一次同步的清单版本高于已安装版本，且 Crab 可以安装它。 */
  updateAvailable: boolean
  /** 清单上的版本。本地包，以及清单里已经没有的停用包，为 null。 */
  latestVersion: string | null
  /** 清单要求、但当前已安装版本未要求的凭证名，已排序。 */
  newRequiredCredentials: string[]
  /** 当前校验载不进。列表仍给出卡片，可以进详情。 */
  ruleDisabled?: boolean
  /** 停用原因。已加载的卡片没有这个字段。 */
  message?: string | null
  /** 上一次切换换下来的版本。没有记录时为 null。文件不在时仍返回版本号。 */
  previousVersion?: string | null
  /**
   * 上一版的包还在，但当前规则载不进。没有这个字段时，页面按不能退回处理。
   */
  previousDisabled?: boolean
  /** 上一版的 package.zip 还在本机。没有这个字段时，页面按不能退回处理。 */
  previousPresent?: boolean
  fixHint?: string | null
}

export interface CredentialField {
  name: string
  type: string
  label: string
  required: boolean
  description: string | null
  options: string[] | null
}

export interface ConfigDeclaration extends CredentialField {
  defaultValue: unknown
}

export interface CapabilityState {
  action: string
  title: string
  description: string
  method: string
  readOnly: boolean
  disabled: boolean
  /** 所属分组路径（如 approval 或 approval/leave）；未分组为 null。 */
  group?: string | null
}

/**
 * 两个同网站的规则包可能在互相顶掉登录：10 分钟内各自被对方登录顶掉、交替出现 3 轮。
 * 只在内存里，Crab 重启后清空；最近一次达到阈值 24 小时后不再显示。
 */
export interface SessionContention {
  /** `scheme://host:port` */
  site: string
  /** 两个包名，已排序。 */
  conches: string[]
  /** 最近一次达到阈值的时间，ISO-8601。 */
  lastAt: string
}

export interface ConchDetail {
  conch: string
  /** 实际访问的站点 `scheme://host:port`。`driver: mcp`、已停用或地址读不出时为 null。 */
  site?: string | null
  /** 见 {@link ConchSummary.sameSite}。 */
  sameSite?: string[]
  /** 登录时还要访问的认证地址（`auth.origins`，配置引用按本机填的值），`scheme://host:port`。没有为空数组。 */
  authOrigins?: string[]
  /** 见 {@link ConchSummary.sameAuth}。 */
  sameAuth?: string[]
  /** `sameSite`、`sameAuth` 里可能正在和它互相顶掉登录的包，见 {@link SessionContention}。 */
  contendingWith?: string[]
  /** 已加载版本。签名损坏且未载入时为 null，不表示已安装。 */
  version: string | null
  title: string
  description: string | null
  credentials: CredentialField[]
  config: ConfigDeclaration[]
  /** 一/二级能力分组目录；未声明分组的规则包为空数组。 */
  groups?: GroupDef[]
  capabilities: CapabilityState[]
  /** 本机是否已订阅。本地包为 false。 */
  subscribed: boolean
  /**
   * 与列表相同：账号数为 0、缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停。
   * 已经有账号时，`required: false` 的凭证可以不填。
   * 账号行的同名字段不含「没有账号」，也不含自动登录暂停。
   * 签名损坏且未载入时为 false，不改已加载包的计算。
   */
  needsConfiguration: boolean
  /** 任一账号自动登录已暂停。账号行带原因和恢复方法。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。签名损坏且未载入时仍是 `REEF`。 */
  source: 'LOCAL' | 'REEF'
  /** 缺省或空白为 `http`。签名损坏且未载入时为 null，不从损坏的包里读。 */
  driver: 'http' | 'mcp' | null
  /** Reef 已停用。本机仍保留已安装版本并可调用。本地包为 false。 */
  reefDisabled: boolean
  /** 整个规则包在本机被停用：所有能力不可调用，也不出现在 MCP 和 OpenAPI 里。未载入的包没有这个字段。 */
  switchedOff?: boolean
  /** 本机同名本地包的版本，没有为 null。和 reefVersion 都有值时，两份同名，只有 source 那份生效。 */
  localVersion?: string | null
  /** 本机同名正式包的版本，没有为 null。 */
  reefVersion?: string | null
  /** 本机第一次记下停用墓碑的时间，ISO-8601。未停用为 null。 */
  reefDisabledAt: string | null
  /** 最近一次同步的清单版本高于已安装版本，且 Crab 可以安装它。 */
  updateAvailable: boolean
  /** 清单上的版本。本地包，以及清单里已经没有的停用包，为 null。 */
  latestVersion: string | null
  /** 清单要求、但当前已安装版本未要求的凭证名，已排序。 */
  newRequiredCredentials: string[]
  /**
   * 已订阅但签名损坏、没有载入时为 false。已加载的详情没有这个字段。
   */
  installed?: boolean
  /** 已订阅但签名损坏、没有载入时为 true。已加载的详情没有这个字段。 */
  repairRequired?: boolean
  /** `BAD_SIGNATURE`，否则没有这个字段。 */
  repairReason?: string | null
  /**
   * 签名损坏时为「签名已损坏，当前不可信。可以手动修复。」。
   * 已加载的详情没有这个字段。
   */
  message?: string | null
  /** 签名通过但当前校验载不进。文件还在，暂时不能调用。不是 Reef 停用。 */
  ruleDisabled?: boolean
  /**
   * 已停用时，声明是从验签通过的包里读出的。为 false 表示能力、配置和凭证还没读出来，
   * 空列表不表示这份规则没有声明。已加载的详情为 true。
   */
  declarationsLoaded?: boolean
  /** 上一次切换换下来的版本。没有记录时为 null。文件不在时仍返回版本号。 */
  previousVersion?: string | null
  /**
   * 上一版的包还在，但当前规则载不进。当前版本可用时也会返回。
   * 没有这个字段时，页面按不能退回处理。
   */
  previousDisabled?: boolean
  /** 上一版的 package.zip 还在本机。没有这个字段时，页面按不能退回处理。 */
  previousPresent?: boolean
  /** 下一步。正式包是更新、退回或等发布方出新版，本地规则包是到「规则包与账号」重新导入。 */
  fixHint?: string | null
}

/** 身份里的一个成员：某个规则包（某个来源那一份）的一个账号。 */
export interface IdentityMember {
  origin: 'OFFICIAL' | 'LOCAL'
  conch: string
  account: string
  /** 包不在本机时为 null。 */
  title: string | null
  /** 包不在本机（被撤下、文件读不出来）时为 false：绑定留着，这个成员现在不会被调用。 */
  installed: boolean
  /** 同名的另一份（本地包或正式包）在生效时为 false。 */
  active: boolean
  /** 这个包会访问的地址：站点加认证地址。 */
  addresses: string[]
}

/** 一个身份：某个网站或认证中心上的某个人。成员共用一份 Cookie，调用排队执行。 */
export interface Identity {
  id: string
  name: string
  createdAt: string
  members: IdentityMember[]
  /** 最近一次「带着已有 Cookie 登录没成功、清空重来」。只在内存里，重启后为 null。 */
  lastCleared: { at: string; conch: string; account: string } | null
}

/** 绑定前的检查结果。凭证只比较文本类型的字段，不返回值。 */
export interface IdentityCheck {
  allowed: boolean
  /** 不能绑定的原因；能绑定时为 null。 */
  problem: string | null
  peers: {
    origin: string
    conch: string
    account: string
    /** 和这个成员相同的地址。 */
    addresses: string[]
    /** SAME：有相同的文本凭证值；DIFFERENT：都填了但没有相同的；UNKNOWN：比不出来。 */
    credentials: 'SAME' | 'DIFFERENT' | 'UNKNOWN'
  }[]
  warnings: string[]
}

export interface Account {
  name: string
  default: boolean
  /** 绑到的身份；没绑为 null。 */
  identity?: { id: string; name: string } | null
  /** 该账号缺必填凭证，或已存凭证无法用当前主密钥解密。没有账号、自动登录暂停都不计入这里。 */
  needsConfiguration: boolean
  missingFields: string[]
  /** 按声明顺序的「显示名（键名）」。显示名为空或与键名相同时只剩键名。 */
  missingLabels?: string[]
  /** 已保存的密文无法解密。为 true 时 needsConfiguration 也为 true。 */
  credentialsUnreadable: boolean
  /** 无法解密时为「凭证无法解密，需重新配置」，否则 null。 */
  credentialMessage: string | null
  loginPaused: boolean
  /** 暂停时为原因，否则 null。 */
  loginPauseReason: string | null
  /** 暂停时为恢复方法（修正凭证后测试登录成功才恢复），否则 null。 */
  loginPauseRecovery: string | null
  /**
   * 有一条待输入的登录（图片验证码、短信或动态口令）。为 true 时账号页显示「需要验证码」。
   * 没有待输入时为 false。
   */
  loginInputRequired: boolean
  /** 待输入登录的过期时间，ISO-8601。没有待输入时为 null。 */
  loginInputExpiresAt: string | null
  /**
   * 非 OAuth 包为 null。OAuth 包给出是否已连接，以及访问令牌的到期时间。
   * 未连接，或上游没有给到期时间时 expiresAt 为 null。
   * needsConfiguration 不把「还没连接上游」算进去。
   */
  oauth: OAuthStatus | null
}

/**
 * `GET .../login-input` 的正文。没有待输入登录时该接口是 404。
 * `resendAfter` 是距下次可换图或重发的秒数，0 表示冷却已过。
 * `canResend` 表示次数还没用完；冷却中仍为 true，次数用尽为 false 且 `resendAfter` 为 0。
 */
export interface LoginInput {
  kind: 'image' | 'code'
  label: string
  hint: string | null
  /** ISO-8601。 */
  expiresAt: string
  hasImage: boolean
  canResend: boolean
  resendAfter: number
}

export interface OAuthStatus {
  connected: boolean
  /** ISO-8601。未连接，或上游没有给到期时间时为 null。 */
  expiresAt: string | null
}

export interface OAuthStart {
  authorizationUrl: string
}

export interface ConfigValue {
  type: string
  label: string
  required: boolean
  description: string | null
  options: string[] | null
  value: string | null
  defaultValue: unknown
  missing: boolean
}

export interface ApiKeyScope {
  /** 全部规则包的全部能力，包括本地包。所有集合都为空的旧 Key 也是 true。只读仍只放行只读能力。 */
  all: boolean
  conches: string[] | null
  groups?: string[] | null
  actions: string[] | null
  readOnly: boolean
}

export interface ApiKey {
  id: string
  name: string
  prefix: string
  scope: ApiKeyScope
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
}

export interface CreatedApiKey extends Omit<ApiKey, 'revokedAt' | 'lastUsedAt'> {
  plaintext: string
}

/** 配置审计一行。只有谁、做了什么、何时；没有凭证或配置的值。 */
export interface ConfigAuditEntry {
  id: number
  at: string
  actor: string
  /** AuditKind 名称，例如 CREDENTIALS_WRITTEN、CONFIG_PUT、CAPABILITY_DISABLED、REEF_CONCH_DISABLED、REEF_CONCH_ENABLED、REEF_BOOTSTRAPPED、REPORT_SETTINGS_CHANGED。 */
  action: string
  origin: string | null
  conch: string | null
  account: string | null
  /**
   * 涉及的名字，按字典序。凭证写入含写入和清除的字段；配置是 key；
   * 凭证声明变更是新增必填和移除的字段；能力开关是能力名。不含字段值。
   */
  fields: string[]
}

export interface ConfigAuditPage {
  entries: ConfigAuditEntry[]
  page: number
  limit: number
  total: number
}

export interface CallSummary {
  traceId: string
  at: string
  origin: string
  conch: string
  action: string
  account: string | null
  conchVersion: string
  success: boolean
  errorCode: string | null
  errorMessage: string | null
  durationMs: number
  apiKeyId: string | null
  /** 规则包来源。本地包为 `LOCAL`，其余记为 `REEF`。与 `channel` 不是同一件事。 */
  source: 'LOCAL' | 'REEF'
  /**
   * 调用入口。`HTTP` 是对外 OpenAPI，`MCP` 是 MCP。
   * `CONSOLE` 是本地包试跑。测试登录不写调用记录。
   */
  channel: 'HTTP' | 'MCP' | 'CONSOLE'
}

export interface DevTraceVariable {
  name: string
  summary: unknown
}

/**
 * 实际发出的请求。入库前已脱敏。
 * `target` 是请求行里的 path 加查询串，不含 fragment。
 * `headers` 含客户端的 `User-Agent`、`Accept-Encoding`，不含 `Cookie`、`Set-Cookie`、`Authorization`、`Proxy-Authorization`、`Mcp-Session-Id`。
 * `body` 按发出时的字符集解码；先脱敏，再截到 64 KiB。超出时 `truncated` 为 true，`truncation` 为「实际发送的正文超过 64 KiB，已截断。」。
 */
export interface DevTraceWire {
  method: string | null
  target: string
  /** 实际发出的值，包含 charset。没有请求体时为 null。 */
  contentType: string | null
  headers: Record<string, string>
  body: string | null
  truncated?: boolean
  truncation?: string
}

/**
 * 步骤上的跟踪警告。已知 code：`cross_origin`、`secure_cookie`、`cookie_rejected`、`body_ended_by_idle`、`location_unresolved`、`not_text`、`snapshot_skipped`、`shared_session_cleared`、`session_expired`。
 * `snapshot_skipped` 挂在产出该变量的登录步骤上，message 只带变量名，不带值。
 * `shared_session_cleared` 表示带着身份里已有的 Cookie 登录没成功，清空后从头登录了一次。
 * `session_expired` 表示命中 `auth.expired_when`、未通过 `alive_check.expect` 或 `assert.on_fail: session_expired`，按会话失效处理。
 */
export interface DevTraceWarning {
  code:
    | 'cross_origin'
    | 'secure_cookie'
    | 'cookie_rejected'
    | 'body_ended_by_idle'
    | 'location_unresolved'
    | 'not_text'
    | 'snapshot_skipped'
    | 'shared_session_cleared'
    | 'session_expired'
  message: string
}

/** 脚本抛出的错误。已脱敏。`source` 是脚本库名字，或步骤自己的 `code`。面板把 `code` 写成「本步脚本」，库名写成「脚本库「名字」」。列号未知时为 null。 */
export interface ScriptError {
  source: string
  line: number
  column: number | null
  message: string
}

export interface DevTraceStep {
  index: number
  phase: string
  type: string
  durationMs: number
  method?: string | null
  /** 原始 path，百分号编码保持原样，不含 fragment。查询串在 `wire.target`。 */
  path?: string | null
  status?: number | null
  /** 规则里的 query 映射。不是请求行上已经编码的查询串。 */
  query?: unknown
  requestHeaders?: Record<string, unknown>
  requestBody?: unknown
  responseHeaders?: Record<string, unknown>
  responseBody?: unknown
  /** `download` 存进暂存区的文件。只有元数据，没有内容。 */
  file?: { fileId: string; name: string; size: number; mime: string; sha256: string }
  extractors?: string[]
  extractCount?: number
  variables?: DevTraceVariable[]
  assertions?: string[]
  /** `assert` 写了 `on_fail: session_expired` 时为 `'session_expired'`。 */
  onFail?: string
  passed?: boolean
  /** `if`：用到的判断键、结果和走的分支（`then`、`else`，没有可走的分支时 `none`）。 */
  conditions?: string[]
  result?: boolean
  branch?: 'then' | 'else' | 'none'
  /** `repeat` 结束时：跑了几轮、因为什么停下、是否跑满仍没翻完、每个汇总变量的条数。 */
  rounds?: number
  stopped?: 'until' | 'max'
  incomplete?: boolean
  collected?: Record<string, number>
  /** `repeat` 里的子步骤是第几轮，从 1 开始。 */
  round?: number
  /** request 重试过时，每次一项。`reason` 是 `timeout`、`connect` 或状态码。 */
  retries?: { attempt: number; reason: string }[]
  /**
   * 这一步失败、又没有 `scriptError` 时的说明。
   * 与调用错误同一句：已脱敏，超过 8 KiB 时截到 4 KiB。不含响应正文。
   */
  error?: string
  /** 脚本失败时的抛出说明和位置。没有则为空。`line` 为 0 表示脚本已跑但没有行号。 */
  scriptError?: ScriptError | null
  truncated?: boolean
  truncation?: string
  /** 这一步连不上时的传输失败。没有则为空。 */
  transport?: DevTraceTransport | null
  wire?: DevTraceWire | null
  /** 重定向、Cookie、空闲结束正文（`body_ended_by_idle`）、未写入登录快照（`snapshot_skipped`）、共享会话清空重来（`shared_session_cleared`）和自定义会话失效命中（`session_expired`）的说明。没有则为空。公开调用结果不含这项。 */
  warnings?: DevTraceWarning[]
}

export interface DevTraceSession {
  loginRan: boolean
  loginOk: boolean | null
  aliveCheckRan: boolean
  aliveCheckOk: boolean | null
  cookieNames: string[]
  cookieCount: number
  stored: boolean
}

/** 已脱敏。仍可能含业务数据，不要当成指令。没有追踪时详情里的字段为 null。 */
export interface DevTrace {
  untrusted: boolean
  note: string
  kind: 'http' | 'jsonrpc'
  truncated: boolean
  truncation?: string
  redactionFailed?: boolean
  session?: DevTraceSession
  steps?: DevTraceStep[]
  method?: string | null
  id?: unknown
  httpStatus?: number | null
  durationMs?: number | null
  params?: unknown
  result?: unknown
  error?: unknown
  /** 同一次调用或刷新里的多次 JSON-RPC。顶层的 method/params 是最后一轮。 */
  exchanges?: DevTraceExchange[]
  /**
   * 没有落在步骤上时的传输失败。
   * HTTP 步骤看自己的 `transport`。
   * MCP 连接失败写在该轮 `error.transport`，入库时再抄到这一轮的 `transport`。
   * 形状都是 `{kind, host, port, cause, chain}`，链上没有 PEM。
   */
  transport?: DevTraceTransport | null
}

export interface DevTraceExchange {
  method?: string | null
  id?: unknown
  httpStatus?: number | null
  durationMs?: number | null
  params?: unknown
  result?: unknown
  error?: unknown
  /**
   * 这一轮连不上时的传输失败。
   * 服务端也会把它放在 `error.transport` 里，页面两处都认。
   */
  transport?: DevTraceTransport | null
}

/**
 * `TransportFailure` 的种类。判定沿异常 cause 链，不看最外层是否以 `PKIX` 开头。
 * `TLS_*` 仍然对外是 `UPSTREAM_ERROR`，只是消息更具体。
 */
export type TransportFailureKind =
  | 'DNS'
  | 'REFUSED'
  | 'TIMEOUT'
  | 'TLS_UNTRUSTED'
  | 'TLS_INTERMEDIATE'
  | 'TLS_HOSTNAME'
  | 'TLS_EXPIRED'
  | 'TLS_PROTOCOL'
  | 'TLS_CIPHER'
  | 'TLS_CERT_ALGORITHM'
  | 'TLS_SNI'
  | 'TLS_OTHER'
  | 'NO_RESPONSE'
  | 'HEADER_LIMIT'
  | 'OTHER'

/** 握手记录里的一张证书。主题和指纹不是秘密。 */
export interface TlsCertificate {
  subject: string
  issuer: string
  /** SHA-256，十六进制。 */
  sha256: string
  /** ISO-8601。 */
  notBefore: string
  /** ISO-8601。 */
  notAfter: string
  ca: boolean
  /**
   * 信任管理接口在对端发来证书、可以写入 CA 目录时才带。
   * 开发追踪的 chain 没有这一项。
   */
  pem?: string | null
}

/**
 * 开发追踪里失败步骤的 `transport`。
 * `cause` 是根因类名加原文，最长 300 字，已经过凭证打码。
 */
export interface DevTraceTransport {
  kind: TransportFailureKind
  host: string
  /** 未知时为 -1，页面不显示这个端口。 */
  port: number
  /** 根因原文，最长 300 字，已经过凭证打码。没有时为空串。 */
  cause: string | null
  /** 握手链。没有 PEM。 */
  chain: TlsCertificate[]
}

/** `GET /console/api/tls/trust` 的一个信任来源。JDK、操作系统、CA 目录各一条。 */
export interface TlsTrustSource {
  kind: 'JDK' | 'OS' | 'CA_DIR'
  /** 给人看的名字。空着时页面按 kind 显示「JDK」「操作系统」「CA 目录」。 */
  label: string
  /** 这一来源载入的证书张数。加载失败时为 0。 */
  count: number
  /** 操作系统证书包路径或 CA 目录。JDK 为 null。 */
  location: string | null
  /** 这一来源加载失败时的警告。成功为 null。 */
  error: string | null
}

export interface TlsCaFile {
  name: string
  certificates: TlsCertificate[]
  /** 成功载入的文件为 null。跳过的文件不在这里，而在 `loadErrors`。 */
  error: string | null
}

/** schema 17 `tls_trusted_hosts` 的一行。每个 host:port 一张证书。 */
export interface TlsTrustedHost {
  host: string
  port: number
  sha256: string
  subject: string
  issuer: string
  /** ISO-8601。 */
  notAfter: string
  createdAt: string
  createdBy: string
}

export interface TlsLoadError {
  /** 文件名。来源级错误为 null。 */
  file: string | null
  /** 不再重复文件名。文件级错误拼起来是 `文件名：message`。 */
  message: string
}

/**
 * `GET /console/api/tls/trust`。
 * `sources` 是页面上的 JDK / 操作系统 / CA 目录和证书数量；
 * 另外三部分是 `caFiles`、`trustedHosts`、`loadErrors`。
 */
export interface TlsTrustView {
  sources: TlsTrustSource[]
  caFiles: TlsCaFile[]
  trustedHosts: TlsTrustedHost[]
  loadErrors: TlsLoadError[]
}

/** `GET /console/api/tls/failures` 的一条。只在内存里，按 host:port 保留最近的失败。 */
export interface TlsFailure {
  host: string
  port: number
  kind: TransportFailureKind
  message: string
  /** ISO-8601。 */
  at: string
  chain: TlsCertificate[]
}

/** `POST /console/api/tls/probe` 的结果。只握手，不发请求。 */
export interface TlsProbeResult {
  host: string
  port: number
  /** 标准校验或已登记的指纹是否放行。 */
  trusted: boolean
  /** 失败种类。已经受信任时为 null。 */
  kind: TransportFailureKind | null
  message: string | null
  chain: TlsCertificate[]
}

export interface McpRefreshResult {
  version: string
  added: string[]
  removed: string[]
  changed: string[]
  traceId: string
}

export interface CallStep {
  index: number
  type: string
  durationMs: number
  method: string | null
  path: string | null
  status: number | null
  extractCount: number | null
}

export interface CallDetail extends CallSummary {
  steps: CallStep[]
  devTrace: DevTrace | null
}

export interface DebugState {
  enabled: boolean
  expiresAt: string | null
}

export interface CrabRelease {
  latestVersion: string
  downloadUrl: string | null
}

export interface SyncError {
  code: string
  message: string
}

export type SyncOutcomeKind =
  | 'INSTALLED'
  | 'SKIPPED_SAME'
  | 'SKIPPED_MIN_VERSION'
  | 'SKIPPED_PINNED'
  | 'UPDATE_AVAILABLE'
  | 'SKIPPED_OLDER'
  | 'REJECTED'
  | 'ROLLED_BACK'
  | 'DISABLED'
  | 'SKIPPED_LOCAL'
  | 'NOT_SUBSCRIBED'
  /** 已订阅、装过，但本机文件或版本指针坏了。同步不下载，等使用者修复。 */
  | 'REPAIR_REQUIRED'
  /** 验签通过但当前校验载不进。不是文件或指针损坏。 */
  | 'RULE_DISABLED'
  /** 修复时重新载入了本机仍能验签的版本，没有下载。 */
  | 'REPAIRED'

export interface SyncReport {
  notModified: boolean
  conches: { conch: string; version: string | null; kind: SyncOutcomeKind | null; reason: string | null }[]
  removed: string[]
  credentialChanges: { conch: string; addedRequired: string[]; removed: string[] }[]
  latestCrab: CrabRelease | null
  error: SyncError | null
}

export interface ValidationIssue {
  /**
   * 校验代码。请求编码相关：`IGNORED_REQUEST_FIELD`、`DOUBLE_ENCODE`、
   * `PERCENT_ENCODED`、`FRAGMENT_DROPPED`、`BODY_CONTENT_TYPE` 是警告，不阻止保存；
   * `QUERY_STYLE` 和 `REQUEST_BODY` 是错误。
   * 提取和 assert 的形状：`EXTRACT_SPEC`、`EXTRACT_FIELD`、`EXTRACT_OPTIONAL`、
   * `ASSERT_KEY`、`ASSERT_STATUS` 是警告，不阻止保存。
   * `min_crab_version` 不低于 1.9.0 的包里，`IGNORED_REQUEST_FIELD`、`EXTRACT_FIELD`、`ASSERT_KEY` 是错误。
   * `FLOW_STEP`（`if` / `repeat` 写法）和 `RETRY`（request 的 `retry`）是错误。
   * `HOSTNAME` 是错误（下划线或非 ASCII 主机名）；`SINGLE_LABEL_HOST` 是警告。
   */
  code: string
  path: string
  message: string
  /** 从 1 计的 YAML 行。定位不到时没有这个字段。 */
  line?: number | null
}

export interface LocalImportResult {
  conch: string
  version: string
  replaced: boolean
  /** 导入后本机其它已启用的同网站规则包，见 {@link ConchSummary.sameSite}。 */
  sameSite?: string[]
  /** 见 {@link ConchSummary.sameAuth}。 */
  sameAuth?: string[]
  /**
   * 不阻止保存。控制台给这些代码加短中文：`IGNORED_REQUEST_FIELD`（未知字段不会发送）、
   * `DOUBLE_ENCODE`（`query` / `form` 用了 `urlencode`）、`PERCENT_ENCODED`（`query` 字面值里已有 `%xx`）、
   * `FRAGMENT_DROPPED`（path 或 url 里的 `#`）、`BODY_CONTENT_TYPE`（`body` 未声明 Content-Type）。
   * `EXTRACT_SPEC`、`EXTRACT_FIELD`、`EXTRACT_OPTIONAL`、`ASSERT_KEY`、`ASSERT_STATUS` 也是警告，不阻止保存；
   * 没有单独的短标签，仍显示服务端 message。未知代码同样显示服务端 message。
   * 不合法的 `query_style` 是错误 `QUERY_STYLE`，`body` 与 `form`/`json` 冲突或不是字符串是错误 `REQUEST_BODY`，
   * 出现在失败响应的 `errors`，不在这份列表里。
   */
  warnings: ValidationIssue[]
  /** 覆盖时的上一版。新建为 null。 */
  previousVersion: string | null
  /** `.history` 里上一份的编号。新建为 null。 */
  backupId: string | null
}

/**
 * `GET /console/api/local-conches/pending` 的一条。
 * 开发者模式下，agent 扩大了目的地、等控制台确认的覆盖。只在内存里，每个包最多一条，30 分钟过期，重启清空。
 * 确认时把 `sha256` 原样提交。磁盘文件已经不是 `baseSha` 时，确认返回 409，
 * message 为「规则包在等待确认期间被改过，请让 agent 重新保存。」
 */
export interface PendingLocalOverwrite {
  conch: string
  /** 待写入 YAML 的 SHA-256，十六进制。 */
  sha256: string
  /** 提交时磁盘文件的 SHA-256。页面不展示，确认由服务端比对。 */
  baseSha?: string | null
  /** 相对当前文件新增的目的地，`scheme://host:port`。认证方式变化时可以是空数组。 */
  addedDestinations: string[]
  /** 磁盘上的当前版本。旧文件读不出来时为 null。 */
  fromVersion: string | null
  /** 待写入的版本。 */
  toVersion: string
  /** 提交这次覆盖的 API Key 名称。没有时为 null。 */
  keyName: string | null
  /** 提交时间，ISO-8601。 */
  submittedAt: string
  /** 到期时间，ISO-8601。提交时间加 30 分钟。 */
  expiresAt: string
  /** 有无法静态确定的地址时为 true。 */
  undetermined: boolean
  /** 待写入的 YAML 原文。 */
  yaml: string
}

/**
 * `GET /console/api/local-conches/grants` 的一条。
 * 只在这次开发者模式的内存里。`since` 是勾选「本次开发者模式内不再询问」并成功确认的时间。
 */
export interface LocalOverrideGrant {
  conch: string
  /** ISO-8601。 */
  since: string
}

/**
 * `GET /console/api/local-conches/{conch}/history` 的一条。
 * `id` 是 `.history/{conch}/` 下的文件名去掉 `.yaml`，作为回退 URL 的单个路径段。
 */
export interface LocalConchHistoryEntry {
  id: string
  version: string
  /** 备份时间，ISO-8601。 */
  savedAt: string | null
}

/**
 * `GET /local-conches` 的一行。已载入的本地包和载入失败的坏包都在这里。
 * 解析或读失败的坏包不在 `GET /conches` 里，详情、导出和调用都是 404，只能删除后重新导入。
 * 校验载不进时仍在这份列表，详情返回 200，`fixHint` 为「改规则后，到「规则包与账号」点「导入本地规则包」重新导入」。
 */
export interface LocalConchEntry {
  conch: string
  file: string
  /** 已载入的版本。坏包为 null，不表示已安装。 */
  version: string | null
  loaded: boolean
  broken: boolean
  /** 坏包为中文原因，含文件名、包名和原因；已载入为 null。 */
  reason: string | null
  /** 文件还在，但这版规则过不了当前校验。解析失败仍是 false。 */
  ruleDisabled?: boolean
  /** 校验载不进时为「改规则后，到「规则包与账号」点「导入本地规则包」重新导入」。 */
  fixHint?: string | null
}

/** 同名本地包已存在、未带 `confirm=true` 时 409 `CONFLICT` 的 data。 */
export interface LocalImportConflict {
  conch: string
  confirm: true
}

export interface LocalFileResult {
  file: string
  conch: string | null
  version: string | null
  ok: boolean
  issues: ValidationIssue[]
}

/**
 * 目录里的一条。`disabled` 与停用墓碑一致。
 * Reef 清单已经不再列出、但本机有墓碑时，`version` 为 null，`installedVersion` 是本机保留的版本。
 */
export interface CatalogConch {
  conch: string
  version: string | null
  title: string
  description: string | null
  capabilityCount: number
  subscribed: boolean
  installed: boolean
  installedVersion: string | null
  disabled: boolean
  localConflict: boolean
  updateAvailable: boolean
  latestVersion: string | null
  newRequiredCredentials: string[]
  /** 已订阅且因签名损坏没有载入时为 true。其它情况为 false。 */
  repairRequired: boolean
  /** `BAD_SIGNATURE`，没有需要修复的签名损坏时为 null。 */
  repairReason: string | null
  /**
   * 签名损坏时为「签名已损坏，当前不可信。」。
   * 校验不过、已经停用时是停用说明。其它情况为 null。
   */
  message: string | null
  /** 签名通过但当前校验载不进。`installed` 仍为 true，页面不要再显示健康的「已安装」。 */
  ruleDisabled: boolean
  /** 下一步。没有上一版时不含退回。 */
  fixHint?: string | null
  previousVersion?: string | null
  /** 没有这个字段时，页面按不能退回处理。 */
  previousDisabled?: boolean
  /** 上一版的 package.zip 还在本机。没有这个字段时，页面按不能退回处理。 */
  previousPresent?: boolean
}

export interface Catalog {
  conches: CatalogConch[]
  syncedAt: string | null
  configured: boolean
}

export interface SubscriptionOutcome {
  conch: string
  version: string | null
  kind: SyncOutcomeKind | null
  reason: string | null
}

/** `subscribed` 为 false 时这次没有订上，`outcome` 为 null。 */
export interface SubscriptionResult {
  conch: string
  subscribed: boolean
  /** 订阅后本机其它已启用的同网站规则包，见 {@link ConchSummary.sameSite}。 */
  sameSite?: string[]
  /** 见 {@link ConchSummary.sameAuth}。 */
  sameAuth?: string[]
  outcome: SubscriptionOutcome | null
}

export interface UpdateResult {
  conch: string
  outcome: SubscriptionOutcome | null
}

export interface UnsubscribeResult {
  conch: string
  subscribed: false
}

/** `source` 为 `BOOTSTRAP`、`CONFIG`，或尚未配置时的 null。响应不含令牌明文。 */
export interface ReefConnection {
  configured: boolean
  source: 'BOOTSTRAP' | 'CONFIG' | null
  url: string | null
  owner: string | null
  tokenName: string | null
  tokenPrefix: string | null
  publicKeyFingerprint: string | null
  instanceId: string
  importedAt: string | null
}

/** 控制台保存的上报开关。`intervalSeconds` 至少为 60，并覆盖 config.yml。 */
/** 文件暂存区的设置。字节数都是整数。 */
export interface FileStageSettings {
  ttlSeconds: number
  maxFileBytes: number
  maxTotalBytes: number
}

/** 暂存区里的一个文件。清单不带下载链接。 */
export interface StagedFile {
  fileId: string
  name: string
  size: number
  mime: string
  expiresAt: string
  createdAt?: string
  /** 控制台上传的文件，所有 API Key 都能在调用里使用。 */
  shared?: boolean
  apiKeyId?: string | null
}

export interface FileStageView {
  settings: FileStageSettings
  usage: { files: number; bytes: number }
  files: StagedFile[]
}

export interface ReportSettings {
  enabled: boolean
  intervalSeconds: number
}

export interface ReportBatch {
  instanceId: string
  crabVersion: string
  sentAt: string
  failures: {
    traceId: string
    conch: string
    conchVersion: string
    action: string
    stepIndex: number | null
    stepType: string | null
    errorCode: string
    summary: string | null
    occurredAt: string
  }[]
  counts: {
    conch: string
    conchVersion: string
    action: string
    hourStart: string
    success: number
    failure: number
    avgMillis: number
    p95Millis: number
  }[]
  events: {
    conch: string
    fromVersion: string
    toVersion: string
    reason: string
    occurredAt: string
    eventId: string
  }[]
}

const conchPath = (conch: string) => `${base}/conches/${seg(conch)}`
const accountPath = (conch: string, account: string) => `${conchPath(conch)}/accounts/${seg(account)}`

export const api = {
  session: () => http.get<ConsoleSession>(`${base}/session`),
  /**
   * 成功后服务端写 HttpOnly Cookie `hermit_console`，12 小时有效。
   * 口令错误或已锁定都是 401「口令错误」；未设置口令是 409 `NOT_CONFIGURED`。
   */
  login: (password: string) => http.post<{ ok: true }>(`${base}/session`, { password }),
  /** 成功后服务端清掉所有会话，包括当前这个，需要重新登录。 */
  changePassword: (current: string, next: string) =>
    http.post<{ ok: true }>(`${base}/session/password`, { current, next }),

  conches: () => http.get<{ conches: ConchSummary[]; contention?: SessionContention[] }>(`${base}/conches`),
  conch: (conch: string) => http.get<ConchDetail>(conchPath(conch)),

  accounts: (conch: string) => http.get<{ accounts: Account[] }>(`${conchPath(conch)}/accounts`),
  createAccount: (conch: string, name: string) =>
    http.post<{ name: string; default: boolean }>(`${conchPath(conch)}/accounts`, { name }),
  deleteAccount: (conch: string, account: string) => http.del<{ deleted: boolean }>(accountPath(conch, account)),
  makeDefault: (conch: string, account: string) => http.post<{ name: string }>(`${accountPath(conch, account)}/default`),
  credentialNames: (conch: string, account: string) =>
    http.get<{ fields: string[] }>(`${accountPath(conch, account)}/credentials`),
  writeCredentials: (conch: string, account: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${accountPath(conch, account)}/credentials`, values),
  testLogin: (conch: string, account: string) => http.post<unknown>(`${accountPath(conch, account)}/test-login`),
  /**
   * 待输入登录。路径接在账号接口下：
   * `GET/POST/DELETE /console/api/conches/{conch}/accounts/{account}/login-input`，
   * `POST .../login-input/resend`，`GET .../login-input/image?t=`（时间戳防缓存）。
   * 没有待输入时 GET 为 404。POST 正文是 `{value}`，接着登录；成功的 data 与测试登录相同。
   * 后面还有一次输入时，POST 为 409 `LOGIN_INPUT_REQUIRED`。
   * 重发成功后再 GET 一次，用新的过期时间和冷却刷新页面；不依赖 POST 的 data 字段。
   * DELETE 只看成功信封。图片走二进制响应，带 `X-Hermit-Console`，`cache: no-store`。
   */
  loginInput: (conch: string, account: string) => http.get<LoginInput>(`${accountPath(conch, account)}/login-input`),
  loginInputImage: (conch: string, account: string) =>
    http.blob(`${accountPath(conch, account)}/login-input/image`, { t: Date.now() }),
  submitLoginInput: (conch: string, account: string, value: string) =>
    http.post<unknown>(`${accountPath(conch, account)}/login-input`, { value }),
  resendLoginInput: (conch: string, account: string) =>
    http.post<unknown>(`${accountPath(conch, account)}/login-input/resend`),
  abandonLoginInput: (conch: string, account: string) =>
    http.del<unknown>(`${accountPath(conch, account)}/login-input`),
  /**
   * 开始上游 OAuth。浏览器打开 authorizationUrl；授权端点再跳回 {@link oauthCallbackPath}。
   * 回调不在 /console/api 下，响应是 HTML，不是 JSON。
   */
  startOAuth: (conch: string, account: string) =>
    http.post<OAuthStart>(`${accountPath(conch, account)}/oauth/start`),
  disconnectOAuth: (conch: string, account: string) =>
    http.del<{ oauth: OAuthStatus }>(`${accountPath(conch, account)}/oauth`),
  /** 浏览器导航地址，不走 http 客户端。 */
  oauthCallbackPath: '/console/oauth/callback',

  config: (conch: string) => http.get<{ fields: Record<string, ConfigValue> }>(`${conchPath(conch)}/config`),
  writeConfig: (conch: string, values: Record<string, string>) =>
    http.put<{ updated: boolean; sameSite?: string[]; sameAuth?: string[] }>(`${conchPath(conch)}/config`, values),

  /** 启用或停用整个规则包。停用后所有 Key 都不能调用，凭证、账号和配置不动。 */
  setConchEnabled: (conch: string, enabled: boolean) =>
    http.post<{ conch: string; switchedOff: boolean; sameSite?: string[]; sameAuth?: string[] }>(`${conchPath(conch)}/${enabled ? 'enable' : 'disable'}`),

  /** 同名的本地包和正式包之间切换生效的那份。copy 只在切到 REEF 时有效：复制本地包的账号、凭证和配置。 */
  setConchSource: (conch: string, source: 'LOCAL' | 'REEF', copy = false) =>
    http.post<{ conch: string; source: 'LOCAL' | 'REEF'; copied: boolean; copiedAccounts: number; copiedConfigKeys: number; sameSite?: string[]; sameAuth?: string[] }>(
      `${conchPath(conch)}/source`,
      source === 'REEF' ? { source, copy } : { source },
    ),

  setCapability: (conch: string, action: string, enabled: boolean) =>
    http.post<{ disabled: boolean }>(`${base}/capabilities/${seg(conch)}/${seg(action)}/${enabled ? 'enable' : 'disable'}`),

  /** 按一级或二级分组批量启停能力；一级分组包含下属二级子组。返回每个受影响能力的最新状态。 */
  setGroup: (conch: string, group: string, enabled: boolean) =>
    http.post<{ conch: string; group: string; disabled: boolean; actions: { action: string; disabled: boolean }[] }>(
      `${conchPath(conch)}/groups/${enabled ? 'enable' : 'disable'}`,
      { group },
    ),

  apiKeys: () => http.get<{ apiKeys: ApiKey[] }>(`${base}/apikeys`),
  createApiKey: (body: { name: string; readOnly: boolean; all: boolean; conches: string[]; groups?: string[]; actions: string[] }) =>
    http.post<CreatedApiKey>(`${base}/apikeys`, body),
  revokeApiKey: (id: string) => http.del<{ revoked: boolean }>(`${base}/apikeys/${seg(id)}`),

  /**
   * `action` 可以是能力名，或 `conch.action`（按第一个 `.` 拆开）。与 `conch` 同时给出且包名不同时，接口返回 400。
   * `channel` 为 `HTTP`、`MCP` 或 `CONSOLE`。不传 `limit` 时服务端默认 100，最大 500。
   */
  calls: (query: { conch?: string; action?: string; channel?: 'HTTP' | 'MCP' | 'CONSOLE'; limit?: number }) =>
    http.get<{ calls: CallSummary[] }>(`${base}/calls`, query),
  audit: (query: { conch?: string; account?: string; page?: number; limit?: number } = {}) =>
    http.get<ConfigAuditPage>(`${base}/audit`, query),
  call: (traceId: string) => http.get<CallDetail>(`${base}/calls/${seg(traceId)}`),

  debug: () => http.get<DebugState>(`${base}/debug`),
  setDebug: (enabled: boolean) => http.post<DebugState>(`${base}/debug`, { enabled }),

  /** `intervalSeconds` 是自动同步的间隔（config.yml 的 sync.interval_seconds）。 */
  syncStatus: () =>
    http.get<{ report: SyncReport | null; latestCrab: CrabRelease | null; intervalSeconds?: number }>(`${base}/sync`),
  sync: () => http.post<SyncReport>(`${base}/sync`),

  /** Reef 未配置时 `configured` 为 false，`conches` 为空。 */
  catalog: () => http.get<Catalog>(`${base}/catalog`),
  subscribe: (conch: string) => http.post<SubscriptionResult>(`${conchPath(conch)}/subscription`),
  /** 安装最近一次同步清单里的更高版本。不重新拉取清单。 */
  update: (conch: string) => http.post<UpdateResult>(`${conchPath(conch)}/update`),
  rollback: (conch: string) => http.post<UpdateResult>(`${conchPath(conch)}/rollback`),
  unsubscribe: (conch: string) => http.del<UnsubscribeResult>(`${conchPath(conch)}/subscription`),

  /** 当前生效的 Reef 连接。令牌只返回前缀。 */
  reef: () => http.get<ReefConnection>(`${base}/reef`),
  /** 导入引导 JSON。服务端会先连 Reef 校验令牌，再热更新本进程。 */
  importReefBootstrap: (body: unknown) => http.post<ReefConnection>(`${base}/reef/bootstrap`, body),

  identities: () => http.get<{ identities: Identity[] }>(`${base}/identities`),
  createIdentity: (name: string) => http.post<Identity>(`${base}/identities`, { name }),
  renameIdentity: (id: string, name: string) => http.put<{ renamed: boolean }>(`${base}/identities/${seg(id)}`, { name }),
  deleteIdentity: (id: string) => http.del<{ deleted: boolean }>(`${base}/identities/${seg(id)}`),
  /** 只检查，不绑定。 */
  checkIdentityMember: (id: string, conch: string, account: string) =>
    http.post<IdentityCheck>(`${base}/identities/${seg(id)}/check`, { conch, account }),
  /** 绑定要使用者确认过，所以总是带 confirm。 */
  bindIdentityMember: (id: string, conch: string, account: string) =>
    http.post<IdentityCheck>(`${base}/identities/${seg(id)}/members`, { conch, account, confirm: true }),
  unbindIdentityMember: (id: string, member: { origin: string; conch: string; account: string }) =>
    http.del<{ unbound: boolean }>(
      `${base}/identities/${seg(id)}/members/${seg(member.origin)}/${seg(member.conch)}/${seg(member.account)}`,
    ),

  files: () => http.get<FileStageView>(`${base}/files`),
  /** 请求体就是文件内容。上传的文件所有 API Key 都能用，返回里的 fileId 填进能力的文件参数。 */
  uploadFile: (file: File) => http.raw<StagedFile>(`${base}/files`, file, { name: file.name }),
  deleteFile: (fileId: string) => http.del<unknown>(`${base}/files/${seg(fileId)}`),
  updateFileSettings: (body: FileStageSettings) => http.put<FileStageSettings>(`${base}/files/settings`, body),
  reportSettings: () => http.get<ReportSettings>(`${base}/reports/settings`),
  updateReportSettings: (body: ReportSettings) => http.put<ReportSettings>(`${base}/reports/settings`, body),

  reportPreview: () => http.get<{ batch: ReportBatch; dropped: number }>(`${base}/reports/preview`),
  sendReport: () => http.post<{ attempted: boolean; accepted: boolean; status: number }>(`${base}/reports/send`),

  /** 按 `conch` 排序，含 `broken` 为 true 的坏包。 */
  localConches: () => http.get<{ conches: LocalConchEntry[] }>(`${base}/local-conches`),
  /**
   * `file` 是 multipart 字段名。校验失败时 400，data 为 `{ errors, warnings }`。
   * 同名本地包（含坏包）已存在且未确认时 409 `CONFLICT`，data 为 {@link LocalImportConflict}，
   * 用户同意后带 `confirm` 再传一次。与正式包同名可以导入，导入后原来生效的正式包继续生效。
   */
  importLocalConch: (file: File, confirm = false) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<LocalImportResult>(`${base}/local-conches${confirm ? '?confirm=true' : ''}`, form)
  },
  /**
   * 等待控制台确认的覆盖。固定路径，不是包名。
   * `data` 为 `{pending}`。没有待确认时 `pending` 为空数组。
   */
  pendingLocalOverwrites: () =>
    http.get<{ pending: PendingLocalOverwrite[] }>(`${base}/local-conches/pending`),
  /**
   * 确认一条待覆盖。正文有 `{sha256}`，必须是该条记录的 SHA-256。
   * `remember` 为 true 时再带 `remember: true`，只记住这一个包；false 时不传这个字段。
   * 等待期间磁盘文件变了：409，message 为「规则包在等待确认期间被改过，请让 agent 重新保存。」
   */
  confirmPendingOverwrite: (conch: string, sha256: string, remember = false) =>
    http.post<LocalImportResult>(
      `${base}/local-conches/${seg(conch)}/pending/confirm`,
      remember ? { sha256, remember: true } : { sha256 },
    ),
  /** 拒绝这条待确认覆盖，不写文件。 */
  rejectPendingOverwrite: (conch: string) =>
    http.del<{ conch: string; rejected: boolean }>(`${base}/local-conches/${seg(conch)}/pending`),
  /** 这次开发者模式里已勾选不再询问的包。`data` 为 `{grants}`。没有时为空数组。 */
  localOverrideGrants: () =>
    http.get<{ grants: LocalOverrideGrant[] }>(`${base}/local-conches/grants`),
  /** 撤销这一个包的自动确认。没有这项授权时 404。 */
  revokeLocalOverrideGrant: (conch: string) =>
    http.del<{ conch: string; revoked: boolean }>(`${base}/local-conches/grants/${seg(conch)}`),
  /**
   * 当前 yaml 之外保留的最近备份，最多 3 份。`data` 为 `{history}`。
   * 删除包后历史仍在，再次导入同名包可以继续回退。
   */
  localConchHistory: (conch: string) =>
    http.get<{ history: LocalConchHistoryEntry[] }>(`${base}/local-conches/${seg(conch)}/history`),
  /**
   * 回退到一份备份。回退前服务端先备份当前版本；地址扩大时清空这个包的登录会话。
   * `id` 是历史接口返回的编号。
   */
  restoreLocalConch: (conch: string, id: string) =>
    http.post<LocalImportResult>(`${base}/local-conches/${seg(conch)}/history/${seg(id)}/restore`),
  reloadLocalConches: () => http.post<{ results: LocalFileResult[] }>(`${base}/local-conches/reload`),
  deleteLocalConch: (conch: string) =>
    http.del<{ conch: string; deleted: boolean }>(`${base}/local-conches/${seg(conch)}`),
  /** 响应体是原始 YAML 文本，与磁盘文件一致。 */
  exportLocalConch: (conch: string) => http.text(`${base}/local-conches/${seg(conch)}/export`),
  /**
   * 本地 HTTP 或 MCP 包试跑。正式包为 409 `CONFLICT`。开关关掉为 409 `NOT_CONFIGURED`。
   * 成功时 data 是能力输出。MCP 成功时优先 structuredContent，否则 `{content}`。追踪在调用详情的 `devTrace`。
   */
  runLocal: (conch: string, action: string, body: { account?: string; input?: Record<string, unknown> }) =>
    http.postEnvelope<Record<string, unknown>>(`${base}/local-conches/${seg(conch)}/actions/${seg(action)}/run`, body),
  /**
   * 开发者模式打开时，从上游 tools/list 重写本地 MCP 包。正式包为 409 `CONFLICT`。
   * OAuth 包使用已连接的令牌；没连上是 409 `NOT_CONFIGURED`，不打上游。
   * 令牌端点非 200 是 422，message 带 HTTP 状态和 OAuth `error` 码，不带 `error_description`。
   * 刷新后 `tools/list` 仍是 401 时，message 仍是「上游拒绝了登录。请到控制台重新连接。」。
   * version 不变。traceId 指向 action 为 mcp_refresh 的 JSON-RPC 追踪，没有对应调用记录。
   */
  refreshLocalMcp: (conch: string, account?: string) => {
    const query = account ? `?account=${encodeURIComponent(account)}` : ''
    return http.post<McpRefreshResult>(`${base}/local-conches/${seg(conch)}/mcp/refresh${query}`)
  },

  /**
   * 控制台登录才能调用，API Key 不行。
   * 信任来源、CA 文件、已信任的站点和加载错误。
   */
  tlsTrust: () => http.get<TlsTrustView>(`${base}/tls/trust`),
  /** 最近的传输失败，含种类、消息、证书链和时间。 */
  tlsFailures: () => http.get<{ failures: TlsFailure[] }>(`${base}/tls/failures`),
  /**
   * 把 PEM 写入 CA 目录并重新加载，成功返回信任视图。
   * `name` 是 `[A-Za-z0-9_.-]{1,64}`，且以 `.pem`、`.crt` 或 `.cer` 结尾。
   * 否则 400「名字只能包含字母、数字、点、下划线和连字符，长度 1 到 64，并且以 .pem、.crt 或 .cer 结尾。」。
   * 私钥是「文件名：含有私钥，已跳过整个文件」，超过 64 KiB 是「文件名：超过 64 KiB，已跳过」，
   * 解析不成证书是「文件名：不是证书」。这些情况不留下新文件。
   */
  importTlsCa: (name: string, pem: string) => http.post<TlsTrustView>(`${base}/tls/ca`, { name, pem }),
  /** `{deleted: true}` 或 `{deleted: false}`。删掉后重新加载，并使 TLS 会话失效。 */
  deleteTlsCa: (file: string) => http.del<{ deleted: boolean }>(`${base}/tls/ca/${seg(file)}`),
  /** 重新读 CA 目录和操作系统信任库。不清 Cookie，不重建客户端。返回信任视图。 */
  reloadTls: () => http.post<TlsTrustView>(`${base}/tls/reload`),
  /**
   * 只握手，不发 HTTP 请求。
   * 地址必须是已加载规则的 `base_url`、MCP 上游或 OAuth 端点，或 Reef 地址。
   * 否则 400「这个地址不在已安装规则、MCP 上游或 Reef 地址里。」。
   * 已经受信任时 `kind` 和 `message` 为 null。链上带 `pem`。
   */
  probeTls: (host: string, port: number) => http.post<TlsProbeResult>(`${base}/tls/probe`, { host, port }),
  /**
   * 登记指纹。`sha256` 是 64 位十六进制，必须和该地址最近一小时内见过的 leaf 一致。
   * 见过的不是这张时 400「没有在最近一小时内见过这张证书。请先检测这个站点。」。
   * 过期是「这张证书已经过期，不能登记。」，尚未生效是「这张证书尚未生效，不能登记。」。
   * 指纹格式不对是「指纹需要是 64 位十六进制。」。同一 `host:port` 再次登记会替换。
   * 成功返回这一行，不是整份信任视图。
   */
  trustTlsHost: (host: string, port: number, sha256: string) =>
    http.post<TlsTrustedHost>(`${base}/tls/trusted-hosts`, { host, port, sha256 }),
  /** `{deleted: true}` 或 `{deleted: false}`。删除后清掉 TLS 会话。 */
  removeTlsHost: (host: string, port: number) =>
    http.del<{ deleted: boolean }>(`${base}/tls/trusted-hosts/${seg(host)}/${port}`),

  /** 开发者模式浏览器录制状态、自动探测到的本机浏览器以及内存中的脱敏快照列表。 */
  recorderStatus: () => http.get<RecorderStatus>(`${base}/recorder/status`),
  recorderStart: (body: RecorderStartRequest) => http.post<RecorderStatus>(`${base}/recorder/start`, body),
  recorderStop: () => http.post<RecorderStatus>(`${base}/recorder/stop`),
  recorderEntries: (includeStatic = false) =>
    http.get<{ entries: RecorderEntrySummary[] }>(`${base}/recorder/entries`, includeStatic ? { includeStatic: '1' } : undefined),
  recorderEntry: (id: string) => http.get<RecorderEntryDetail>(`${base}/recorder/entries/${seg(id)}`),
  recorderImportHar: (file: File) => http.raw<RecorderStatus>(`${base}/recorder/import-har`, file, { name: file.name }),
  recorderClearSession: () => http.del<RecorderStatus>(`${base}/recorder/session`),
  recorderSnapshots: () => http.get<{ snapshots: RecorderSnapshotSummary[] }>(`${base}/recorder/snapshots`),
  recorderCreateSnapshot: (body: RecorderCreateSnapshotRequest) =>
    http.post<RecorderSnapshotDetail>(`${base}/recorder/snapshots`, body),
  recorderSnapshot: (id: string) => http.get<RecorderSnapshotDetail>(`${base}/recorder/snapshots/${seg(id)}`),
  recorderDownloadHar: (id: string) => http.blob(`${base}/recorder/snapshots/${seg(id)}/har`),
  recorderDeleteSnapshot: (id: string) => http.del<{ id: string; deleted: boolean }>(`${base}/recorder/snapshots/${seg(id)}`),
}

export interface DetectedBrowser {
  id: string
  name: string
  path: string
}

export interface RecorderHeader {
  name: string
  value: string
}

export interface RecorderEntrySummary {
  id: string
  index: number
  startedAt: string
  durationMs: number
  method: string
  url: string
  origin: string
  path: string
  resourceType: string
  staticAsset: boolean
  status: number
  statusText: string
  mimeType: string
  redirectTo: string | null
  binary: boolean
  contentDisposition: string | null
  responseBytes: number
  errorText: string | null
  hasRequestBody: boolean
  hasResponseBody: boolean
}

export interface RecorderEntryDetail extends RecorderEntrySummary {
  requestHeaders: RecorderHeader[]
  requestBody: string | null
  requestBodyTruncated: boolean
  responseHeaders: RecorderHeader[]
  responseBody: string | null
  responseBodyTruncated: boolean
}

export interface RecorderSnapshotSummary {
  id: string
  title: string
  createdAt: string
  source: string
  origins: string[]
  cookieNames: string[]
  hasRedirects: boolean
  hasDownloads: boolean
  entryCount: number
}

export interface RecorderSnapshotDetail extends RecorderSnapshotSummary {
  entries: RecorderEntrySummary[]
}

export interface RecorderStatus {
  devMode: boolean
  state: 'IDLE' | 'RECORDING' | 'STOPPED'
  sessionId: string | null
  mode: string | null
  source: string | null
  startUrl: string | null
  startedAt: string | null
  stoppedAt: string | null
  totalEntries: number
  businessEntries: number
  browserConnected: boolean
  disconnectReason: string | null
  defaultRemoteDebuggingDetected: boolean
  browsers: DetectedBrowser[]
  snapshots: RecorderSnapshotSummary[]
}

export interface RecorderStartRequest {
  mode: 'launch' | 'attach'
  startUrl?: string
  browserPath?: string
  debugEndpoint?: string
  ignoreCertErrors?: boolean
}

export interface RecorderCreateSnapshotRequest {
  title?: string
  entryIds: string[]
  extraSecrets?: string[]
}

