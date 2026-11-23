<script setup lang="ts">
import { CopyDocument, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ApiError } from '@shared/http'
import { copyText, errorCodeLabels, errorText, formatTime, notifyError } from '@shared/format'
import { useNarrow } from '@shared/narrow'
import { api, type Account, type CredentialField, type LoginInput } from '../api'
import CredentialDialog from './CredentialDialog.vue'
import TrustJump from './TrustJump.vue'

const props = defineProps<{
  conch: string
  accounts: Account[]
  credentials: CredentialField[]
  /** 这版规则已停用：不在调用路径上，测试登录也跑不了，按钮不出现。 */
  ruleDisabled?: boolean
  /**
   * 为 false 时只藏账号卡片。验证码等弹窗仍挂在卡片外面，其它标签下也能弹。
   * 缺省显示卡片。
   */
  shown?: boolean
}>()
const emit = defineEmits<{ changed: [] }>()

const creating = reactive({ open: false, name: '', saving: false })
const editing = reactive({ open: false, account: '' })
const busy = ref<string | null>(null)
const narrow = useNarrow()
const testResult = reactive({
  open: false,
  account: '',
  success: false,
  data: null as unknown,
  message: '',
  code: '',
  traceId: '' as string | null,
  requestId: null as string | null,
  requestNote: null as string | null,
})
function failureTitle(code: string): string {
  return errorCodeLabels[code] ?? '登录失败'
}

function failureDetail(code: string, message: string): string {
  const prefix = `${failureTitle(code)}：`
  return message.startsWith(prefix) ? message.slice(prefix.length) : message
}

const namePattern = /^[A-Za-z0-9_-]{1,64}$/

async function createAccount() {
  const name = creating.name.trim()
  if (!namePattern.test(name)) {
    ElMessage.warning('账号名只能包含字母、数字、下划线和短横线，最长 64 个字符')
    return
  }
  creating.saving = true
  try {
    const result = await api.createAccount(props.conch, name)
    ElMessage.success(result.default ? `已创建 ${name}，并设为默认账号` : `已创建 ${name}`)
    creating.open = false
    creating.name = ''
    emit('changed')
    if (props.credentials.length) {
      editing.account = name
      editing.open = true
    }
  } catch (error) {
    notifyError(error)
  } finally {
    creating.saving = false
  }
}

async function makeDefault(account: Account) {
  busy.value = account.name
  try {
    await api.makeDefault(props.conch, account.name)
    ElMessage.success(`${account.name} 已设为默认账号`)
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}

async function remove(account: Account) {
  const identityNote = account.identity
    ? `它已绑定到身份「${account.identity.name}」，删除后会同时从该身份解除绑定。`
    : ''
  try {
    await ElMessageBox.confirm(
      `删除账号 ${account.name} 会同时删除它保存的凭证和登录会话，删除后不可恢复。${identityNote}${account.default ? '它是默认账号，删除后不带 X-Hermit-Account 的调用将无法选中账号。' : ''}`,
      '删除账号',
      { confirmButtonText: '删除', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  busy.value = account.name
  try {
    await api.deleteAccount(props.conch, account.name)
    ElMessage.success(`已删除 ${account.name}`)
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}

async function testLogin(account: Account) {
  if (account.identity) {
    try {
      await ElMessageBox.confirm(
        `账号 ${account.name} 已绑定到身份「${account.identity.name}」。测试登录会清空这个身份共用的 Cookie，身份里的所有账号下次调用都会重新登录。`,
        '继续测试登录？',
        { confirmButtonText: '继续测试登录', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  busy.value = account.name
  let openedCaptcha = false
  try {
    const data = await api.testLogin(props.conch, account.name)
    Object.assign(testResult, {
      open: true,
      account: account.name,
      success: true,
      data,
      message: '',
      code: '',
      traceId: null,
      requestId: null,
      requestNote: null,
    })
  } catch (error) {
    if (error instanceof ApiError && error.code === 'LOGIN_INPUT_REQUIRED') {
      openedCaptcha = true
      delete suppressed[account.name]
      await openLoginInput(account.name, true)
    } else {
      Object.assign(testResult, {
        open: true,
        account: account.name,
        success: false,
        data: null,
        message: errorText(error),
        code: error instanceof ApiError ? error.code : '',
        traceId: null,
        requestId: error instanceof ApiError ? error.requestId : null,
        requestNote: error instanceof ApiError ? error.requestNote : null,
      })
    }
  } finally {
    busy.value = null
    if (account.loginPaused || openedCaptcha) {
      emit('changed')
    }
  }
}

function editCredentials(account: Account) {
  editing.account = account.name
  editing.open = true
}

const usesOAuth = computed(() => props.accounts.some((a) => a.oauth !== null))
const connecting = reactive({ open: false, account: '' })

function expired(account: Account): boolean {
  const at = account.oauth?.expiresAt
  return !!at && Date.parse(at) <= Date.now()
}

function refreshOnReturn() {
  if (connecting.open) {
    emit('changed')
  }
}
window.addEventListener('focus', refreshOnReturn)

interface LoginInputResult {
  success: boolean
  message: string
  code: string
  requestId: string | null
  requestNote: string | null
}

const suppressed = reactive<Record<string, boolean>>({})
const codeInput = ref<{ focus: () => void } | null>(null)
const loginInput = reactive({
  open: false,
  account: '',
  loading: false,
  prompt: null as LoginInput | null,
  imageUrl: null as string | null,
  imageFailed: false,
  value: '',
  submitting: false,
  resending: false,
  abandoning: false,
  resendReadyAt: 0,
  now: Date.now(),
  ticket: 0,
  result: null as LoginInputResult | null,
})

let clock: ReturnType<typeof setInterval> | undefined

function ensureClock() {
  if (clock !== undefined) return
  clock = setInterval(() => {
    loginInput.now = Date.now()
  }, 1000)
}

function resendSeconds(): number {
  return Math.max(0, Math.ceil((loginInput.resendReadyAt - loginInput.now) / 1000))
}

function expirySeconds(): number {
  const at = loginInput.prompt ? Date.parse(loginInput.prompt.expiresAt) : Number.NaN
  if (Number.isNaN(at)) return 0
  return Math.max(0, Math.ceil((at - loginInput.now) / 1000))
}

function formatRemain(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  if (minutes <= 0) return `${rest} 秒`
  return `${minutes} 分 ${rest.toString().padStart(2, '0')} 秒`
}

const expiryText = computed(() => {
  if (!loginInput.prompt) return ''
  const seconds = expirySeconds()
  if (seconds <= 0) return '已过期，请重新登录'
  return `还剩 ${formatRemain(seconds)}`
})

const resendLabel = computed(() => {
  const verb = loginInput.prompt?.kind === 'code' ? '重新发送' : '换一张'
  const wait = resendSeconds()
  return wait > 0 ? `${verb}（${wait} 秒）` : verb
})

const resendReady = computed(
  () =>
    !!loginInput.prompt &&
    loginInput.prompt.canResend &&
    resendSeconds() === 0 &&
    expirySeconds() > 0 &&
    !loginInput.loading &&
    !loginInput.submitting &&
    !loginInput.resending &&
    !loginInput.abandoning,
)

const submitDisabled = computed(
  () => loginInput.loading || !loginInput.prompt || expirySeconds() <= 0 || loginInput.submitting,
)

function revokeImage() {
  if (loginInput.imageUrl) {
    URL.revokeObjectURL(loginInput.imageUrl)
    loginInput.imageUrl = null
  }
}

function hasControl(value: string): boolean {
  return /[\u0000-\u001F\u007F-\u009F]/.test(value)
}

function applyPrompt(prompt: LoginInput) {
  const now = Date.now()
  loginInput.now = now
  loginInput.prompt = prompt
  loginInput.resendReadyAt = now + Math.max(0, prompt.resendAfter) * 1000
  loginInput.value = ''
  loginInput.result = null
  loginInput.imageFailed = false
}

async function loadImage(ticket: number) {
  revokeImage()
  try {
    const blob = await api.loginInputImage(props.conch, loginInput.account)
    if (loginInput.ticket !== ticket) return
    loginInput.imageUrl = URL.createObjectURL(blob)
    loginInput.imageFailed = false
  } catch (error) {
    if (loginInput.ticket !== ticket) return
    loginInput.imageFailed = true
    notifyError(error)
  }
}

async function reloadPrompt(name: string) {
  const ticket = ++loginInput.ticket
  const prompt = await api.loginInput(props.conch, name)
  if (loginInput.ticket !== ticket || loginInput.account !== name) return
  applyPrompt(prompt)
  if (prompt.kind === 'image' && prompt.hasImage) {
    await loadImage(ticket)
  } else {
    revokeImage()
  }
}

function focusInput() {
  codeInput.value?.focus()
}

async function openLoginInput(name: string, focus: boolean) {
  delete suppressed[name]
  if (loginInput.open && loginInput.account === name && loginInput.prompt && !loginInput.result) {
    if (focus) {
      await nextTick()
      focusInput()
    }
    return
  }
  ensureClock()
  loginInput.open = true
  loginInput.account = name
  loginInput.loading = true
  loginInput.prompt = null
  loginInput.value = ''
  loginInput.result = null
  loginInput.imageFailed = false
  revokeImage()
  try {
    await reloadPrompt(name)
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      suppressed[name] = true
      loginInput.open = false
      emit('changed')
      notifyError(error)
      return
    }
    notifyError(error)
  } finally {
    if (loginInput.account === name) loginInput.loading = false
  }
  if (focus && loginInput.open && loginInput.account === name && loginInput.prompt) {
    await nextTick()
    focusInput()
  }
}

function openFromRow(account: Account) {
  delete suppressed[account.name]
  void openLoginInput(account.name, true)
}

function syncLoginInput() {
  const pending = new Set(
    props.accounts.filter((account) => account.loginInputRequired).map((account) => account.name),
  )
  for (const name of Object.keys(suppressed)) {
    if (!pending.has(name)) delete suppressed[name]
  }
  if (loginInput.open) return
  for (const name of pending) {
    if (!suppressed[name]) {
      void openLoginInput(name, false)
      return
    }
  }
}

watch(
  () => props.accounts.map((account) => `${account.name}:${account.loginInputRequired ? 1 : 0}`).join('\n'),
  () => syncLoginInput(),
  { immediate: true },
)

function onLoginInputClosed() {
  if (loginInput.open) return
  loginInput.ticket += 1
  const name = loginInput.account
  revokeImage()
  loginInput.prompt = null
  loginInput.result = null
  loginInput.value = ''
  loginInput.loading = false
  if (name && props.accounts.some((account) => account.name === name && account.loginInputRequired)) {
    suppressed[name] = true
  }
}

async function submitLoginInput() {
  if (loginInput.submitting || loginInput.loading || !loginInput.prompt) return
  if (expirySeconds() <= 0) {
    ElMessage.warning('验证码已过期，请重新登录')
    return
  }
  const value = loginInput.value
  if (value.length < 1) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (hasControl(value)) {
    ElMessage.warning('验证码不能包含控制字符')
    return
  }
  if (value.length > 64) {
    ElMessage.warning('验证码最长 64 个字符')
    return
  }
  const name = loginInput.account
  loginInput.submitting = true
  try {
    await api.submitLoginInput(props.conch, name, value)
    loginInput.result = { success: true, message: '', code: '', requestId: null, requestNote: null }
    loginInput.prompt = null
    revokeImage()
    emit('changed')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'LOGIN_INPUT_REQUIRED') {
      try {
        await reloadPrompt(name)
        ElMessage.info('请再输入一次验证码')
        emit('changed')
        await nextTick()
        focusInput()
      } catch (reloadError) {
        if (reloadError instanceof ApiError && reloadError.status === 404) {
          suppressed[name] = true
          loginInput.open = false
        }
        notifyError(reloadError)
      }
      return
    }
    loginInput.result = {
      success: false,
      message: errorText(error),
      code: error instanceof ApiError ? error.code : '',
      requestId: error instanceof ApiError ? error.requestId : null,
      requestNote: error instanceof ApiError ? error.requestNote : null,
    }
    loginInput.prompt = null
    revokeImage()
    emit('changed')
  } finally {
    loginInput.submitting = false
  }
}

async function resendLoginInput() {
  if (!resendReady.value || !loginInput.account) return
  const name = loginInput.account
  loginInput.resending = true
  try {
    await api.resendLoginInput(props.conch, name)
    await reloadPrompt(name)
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      suppressed[name] = true
      loginInput.open = false
    }
    notifyError(error)
  } finally {
    loginInput.resending = false
  }
}

async function abandonLoginInput() {
  if (!loginInput.account || loginInput.abandoning) return
  try {
    await ElMessageBox.confirm(
      '放弃后，这次等待输入的登录会作废，需要重新登录。',
      '放弃验证码',
      { confirmButtonText: '放弃', cancelButtonText: '继续输入', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  const name = loginInput.account
  loginInput.abandoning = true
  try {
    await api.abandonLoginInput(props.conch, name)
    ElMessage.success('已放弃这次登录')
    suppressed[name] = true
    loginInput.open = false
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    loginInput.abandoning = false
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshOnReturn)
  if (clock !== undefined) clearInterval(clock)
  revokeImage()
})

async function connect(account: Account) {
  // 先同步开窗，等接口返回再跳转，避免被浏览器当成弹窗拦掉。
  const popup = window.open('', '_blank')
  busy.value = account.name
  try {
    const { authorizationUrl } = await api.startOAuth(props.conch, account.name)
    if (popup) {
      popup.opener = null
      popup.location.href = authorizationUrl
    } else {
      window.location.assign(authorizationUrl)
      return
    }
    connecting.account = account.name
    connecting.open = true
  } catch (error) {
    popup?.close()
    notifyError(error)
  } finally {
    busy.value = null
  }
}

function finishConnect() {
  connecting.open = false
  emit('changed')
}

async function disconnect(account: Account) {
  try {
    await ElMessageBox.confirm(
      `断开后会删除 ${account.name} 在本机保存的上游令牌，之后的调用会提示「尚未连接上游」，需要重新连接。上游那边的授权不会被撤销。`,
      '断开上游',
      { confirmButtonText: '断开', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  busy.value = account.name
  try {
    await api.disconnectOAuth(props.conch, account.name)
    ElMessage.success(`${account.name} 已断开上游`)
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div v-show="shown !== false" class="hm-card">
    <div class="hm-card-title">
      <span>账号</span>
      <el-button type="primary" plain :icon="Plus" @click="creating.open = true">新建账号</el-button>
    </div>
    <p class="hm-muted hint">
      调用时用请求头 <code>X-Hermit-Account</code> 选择账号，不带时使用默认账号。每个账号有自己的凭证和登录会话。
    </p>
    <p class="hm-scroll-hint">表格可以左右滑动，操作在最右侧。</p>
    <el-table :data="accounts" empty-text="还没有账号，新建一个后填写凭证">
      <el-table-column label="账号" min-width="160">
        <template #default="{ row }">
          <span class="mono strong">{{ row.name }}</span>
          <el-tag v-if="row.default" size="small" class="tag">默认</el-tag>
          <router-link v-if="row.identity" to="/identities" class="identity" data-test="account-identity">
            <el-tag size="small" type="info" effect="plain" class="tag" :title="`和身份「${row.identity.name}」里的其它账号共用 Cookie`">
              身份：{{ row.identity.name }}
            </el-tag>
          </router-link>
        </template>
      </el-table-column>
      <el-table-column label="凭证" min-width="200">
        <template #default="{ row }">
          <template v-if="row.credentialsUnreadable">
            <el-tag type="danger" effect="plain" size="small">无法解密</el-tag>
            <span class="note">{{ row.credentialMessage ?? '凭证无法解密，需重新配置' }}</span>
          </template>
          <el-tag v-else-if="!row.needsConfiguration" type="success" effect="plain" size="small">已填写</el-tag>
          <template v-else>
            <el-tag type="warning" effect="plain" size="small">缺少</el-tag>
            <span class="mono missing">{{ (row.missingLabels ?? row.missingFields).join('、') }}</span>
          </template>
        </template>
      </el-table-column>
      <el-table-column v-if="usesOAuth" label="上游连接" min-width="200">
        <template #default="{ row }">
          <div v-if="row.oauth" data-test="oauth-status">
            <template v-if="row.oauth.connected">
              <el-tag :type="expired(row) ? 'warning' : 'success'" effect="plain" size="small">
                {{ expired(row) ? '令牌已过期' : '已连接' }}
              </el-tag>
              <div class="hm-muted note">
                {{ row.oauth.expiresAt ? `访问令牌到期：${formatTime(row.oauth.expiresAt)}` : '上游没有给出到期时间' }}
              </div>
              <div v-if="expired(row)" class="hm-muted note">下次调用会自动刷新；刷新失败需要重新连接。</div>
            </template>
            <template v-else>
              <el-tag type="warning" effect="plain" size="small">未连接</el-tag>
              <div class="hm-muted note">调用会提示「尚未连接上游」，点「连接上游」登录。</div>
            </template>
          </div>
          <span v-else class="hm-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="自动登录" min-width="200">
        <template #default="{ row }">
          <template v-if="row.loginPaused">
            <el-tag type="warning" effect="plain" size="small">已暂停</el-tag>
            <div v-if="row.loginPauseReason" class="note">{{ row.loginPauseReason }}</div>
            <div class="hm-muted note">重新填写凭证后点「测试登录」，登录成功即恢复。</div>
          </template>
          <div v-if="row.loginInputRequired" data-test="login-input-required">
            <el-tag type="warning" effect="plain" size="small">需要验证码</el-tag>
            <div class="hm-muted note">
              登录停在验证码，请在输入框里填写后提交。
              <template v-if="row.loginInputExpiresAt">过期时间：{{ formatTime(row.loginInputExpiresAt) }}。</template>
            </div>
            <el-button link type="primary" data-test="login-input-open" @click="openFromRow(row)">输入验证码</el-button>
          </div>
          <span v-if="!row.loginPaused && !row.loginInputRequired && row.credentialsUnreadable" class="hm-muted">无法登录：凭证无法解密</span>
          <span v-else-if="!row.loginPaused && !row.loginInputRequired && row.needsConfiguration" class="hm-muted">无法登录：缺少凭证</span>
          <span v-else-if="!row.loginPaused && !row.loginInputRequired" class="hm-muted">正常</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="narrow ? 96 : usesOAuth ? 420 : 300" align="right" fixed="right">
        <template #default="{ row }">
          <div class="ops" :class="{ stacked: narrow }">
            <template v-if="row.oauth">
              <el-button
                link
                type="primary"
                data-test="oauth-connect"
                :loading="busy === row.name"
                @click="connect(row)"
              >
                {{ row.oauth.connected ? '重新连接' : '连接上游' }}
              </el-button>
              <el-button v-if="row.oauth.connected" link type="danger" data-test="oauth-disconnect" @click="disconnect(row)">
                断开
              </el-button>
            </template>
            <el-button link type="primary" :disabled="!credentials.length" @click="editCredentials(row)">填写凭证</el-button>
            <el-button
              v-if="!ruleDisabled"
              link
              type="primary"
              data-test="test-login"
              :loading="busy === row.name"
              @click="testLogin(row)"
            >测试登录</el-button>
            <el-button v-if="!row.default" link @click="makeDefault(row)">设为默认</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <el-dialog v-model="creating.open" title="新建账号" width="420px">
    <el-form label-position="top" @submit.prevent="createAccount">
      <el-form-item label="账号名">
        <el-input v-model="creating.name" placeholder="例如 zhangsan 或 team-ops" autofocus />
      </el-form-item>
      <p v-if="!accounts.length" class="hm-muted">这是第一个账号，会自动成为默认账号。</p>
    </el-form>
    <template #footer>
      <el-button @click="creating.open = false">取消</el-button>
      <el-button type="primary" :loading="creating.saving" @click="createAccount">创建</el-button>
    </template>
  </el-dialog>

  <CredentialDialog
    v-model:open="editing.open"
    :conch="conch"
    :account="editing.account"
    :fields="credentials"
    :unreadable="accounts.find((a) => a.name === editing.account)?.credentialsUnreadable ?? false"
    :identity-name="accounts.find((a) => a.name === editing.account)?.identity?.name ?? null"
    @saved="emit('changed')"
  />

  <el-dialog v-model="connecting.open" :title="`连接上游 · ${connecting.account}`" width="460px" data-test="oauth-dialog">
    <p>已在新窗口打开上游的登录页。在那里登录并同意授权，看到「已连接上游」后回到这里。</p>
    <p class="hm-muted">登录链接 10 分钟内有效，只能用一次。令牌保存在本机凭证库，页面不会显示令牌。</p>
    <template #footer>
      <el-button @click="connecting.open = false">稍后再说</el-button>
      <el-button type="primary" @click="finishConnect">我已完成授权</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="loginInput.open"
    :title="`需要验证码 · ${loginInput.account}`"
    width="480px"
    @closed="onLoginInputClosed"
  >
    <div data-test="login-input">
      <div v-if="loginInput.result" data-test="login-input-result">
        <el-result v-if="loginInput.result.success" icon="success" title="登录成功" sub-title="凭证可用，会话已缓存" />
        <el-result
          v-else
          icon="error"
          :title="failureTitle(loginInput.result.code)"
          :sub-title="failureDetail(loginInput.result.code, loginInput.result.message)"
        >
          <template #extra>
            <TrustJump :message="loginInput.result.message" />
            <template v-if="loginInput.result.requestId">
              <span class="hm-muted">请求编号</span>
              <span class="mono">{{ loginInput.result.requestId }}</span>
              <el-button
                link
                :icon="CopyDocument"
                aria-label="复制请求编号"
                @click="copyText(loginInput.result.requestId ?? '', '请求编号已复制')"
              />
              <span class="hm-muted">{{ loginInput.result.requestNote }}</span>
            </template>
          </template>
        </el-result>
      </div>
      <p v-else-if="loginInput.loading" class="hm-muted">正在读取验证码…</p>
      <p v-else-if="!loginInput.prompt" class="hm-muted">没有读到验证码，可以稍后再试。</p>
      <template v-else>
        <p class="login-input-note hm-muted">输入只用于接着这次登录，不会写入凭证。</p>
        <p class="strong">{{ loginInput.prompt.label }}</p>
        <p v-if="loginInput.prompt.hint" class="hm-muted login-input-note">{{ loginInput.prompt.hint }}</p>
        <img
          v-if="loginInput.imageUrl"
          :src="loginInput.imageUrl"
          alt="验证码图片"
          class="login-input-image"
          data-test="login-input-image"
        />
        <p v-else-if="loginInput.prompt.kind === 'image' && loginInput.imageFailed" class="hm-muted login-input-note">
          验证码图片没有加载出来，可以换一张。
        </p>
        <p v-else-if="loginInput.prompt.kind === 'image' && loginInput.prompt.hasImage" class="hm-muted login-input-note">
          正在读取图片…
        </p>
        <p v-else-if="loginInput.prompt.kind === 'image'" class="hm-muted login-input-note">没有验证码图片。</p>
        <p class="hm-muted login-input-note" data-test="login-input-expires">{{ expiryText }}</p>
        <el-input
          ref="codeInput"
          v-model="loginInput.value"
          autocomplete="off"
          placeholder="请输入"
          data-test="login-input-value"
          @keyup.enter="submitLoginInput"
        />
      </template>
    </div>
    <template #footer>
      <el-button v-if="loginInput.result" type="primary" data-test="login-input-dismiss" @click="loginInput.open = false">
        知道了
      </el-button>
      <template v-else>
        <el-button data-test="login-input-later" @click="loginInput.open = false">稍后再说</el-button>
        <el-button
          type="danger"
          plain
          data-test="login-input-abandon"
          :loading="loginInput.abandoning"
          @click="abandonLoginInput"
        >
          放弃
        </el-button>
        <el-button
          data-test="login-input-resend"
          :disabled="!resendReady"
          :loading="loginInput.resending"
          @click="resendLoginInput"
        >
          {{ resendLabel }}
        </el-button>
        <el-button
          type="primary"
          data-test="login-input-submit"
          :disabled="submitDisabled"
          :loading="loginInput.submitting"
          @click="submitLoginInput"
        >
          提交
        </el-button>
      </template>
    </template>
  </el-dialog>

  <el-dialog v-model="testResult.open" :title="`测试登录 · ${testResult.account}`" width="560px">
    <el-result v-if="testResult.success" icon="success" title="登录成功" sub-title="凭证可用，会话已缓存" />
    <el-result
      v-else
      icon="error"
      :title="failureTitle(testResult.code)"
      :sub-title="failureDetail(testResult.code, testResult.message)"
    >
      <template #extra>
        <TrustJump :message="testResult.message" />
        <template v-if="testResult.requestId">
          <span class="hm-muted">请求编号</span>
          <span class="mono">{{ testResult.requestId }}</span>
          <el-button link :icon="CopyDocument" aria-label="复制请求编号" @click="copyText(testResult.requestId, '请求编号已复制')" />
          <span class="hm-muted">{{ testResult.requestNote || '测试登录不记录调用追踪。' }}</span>
        </template>
      </template>
    </el-result>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.strong {
  font-weight: 600;
}

.tag {
  margin-left: 6px;
}

.missing {
  margin-left: 6px;
  color: var(--hm-warning);
}

.note {
  margin-left: 6px;
  font-size: 12px;
  line-height: 1.5;
}

div.note {
  margin-left: 0;
  margin-top: 4px;
}

.ops.stacked {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.ops.stacked .el-button + .el-button {
  margin-left: 0;
}

.login-input-note {
  margin: 0 0 12px;
  font-size: 13px;
}

.login-input-image {
  display: block;
  max-width: 100%;
  max-height: 200px;
  margin: 0 0 12px;
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  background: var(--hm-surface-2);
}
</style>
