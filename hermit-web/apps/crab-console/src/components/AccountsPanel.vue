<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import JsonBlock from '@shared/components/JsonBlock.vue'
import { ApiError } from '@shared/http'
import { errorCodeLabels, errorText, formatTime, notifyError } from '@shared/format'
import { useNarrow } from '@shared/narrow'
import { api, type Account, type CredentialField } from '../api'
import CredentialDialog from './CredentialDialog.vue'

const props = defineProps<{ conch: string; accounts: Account[]; credentials: CredentialField[] }>()
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
const loginFailureTitle = computed(() => errorCodeLabels[testResult.code] ?? '登录失败')
const loginFailureDetail = computed(() => {
  const prefix = `${loginFailureTitle.value}：`
  return testResult.message.startsWith(prefix) ? testResult.message.slice(prefix.length) : testResult.message
})

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
  try {
    await ElMessageBox.confirm(
      `删除账号 ${account.name} 会同时删除它保存的凭证和登录会话，删除后不可恢复。${account.default ? '它是默认账号，删除后不带 X-Hermit-Account 的调用将无法选中账号。' : ''}`,
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
  busy.value = account.name
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
  } finally {
    busy.value = null
    if (account.loginPaused) {
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
onBeforeUnmount(() => window.removeEventListener('focus', refreshOnReturn))

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
  <div class="hm-card">
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
          <span v-else-if="row.credentialsUnreadable" class="hm-muted">无法登录：凭证无法解密</span>
          <span v-else-if="row.needsConfiguration" class="hm-muted">无法登录：缺少凭证</span>
          <span v-else class="hm-muted">正常</span>
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
            <el-button link type="primary" :loading="busy === row.name" @click="testLogin(row)">测试登录</el-button>
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

  <el-dialog v-model="testResult.open" :title="`测试登录 · ${testResult.account}`" width="560px">
    <el-result v-if="testResult.success" icon="success" title="登录成功" sub-title="凭证可用，会话已缓存">
      <template v-if="testResult.data" #extra>
        <JsonBlock :value="testResult.data" class="data" />
      </template>
    </el-result>
    <el-result
      v-else
      icon="error"
      :title="loginFailureTitle"
      :sub-title="loginFailureDetail"
    >
      <template v-if="testResult.requestId" #extra>
        <span class="hm-muted">{{ testResult.requestNote || '请求编号，不可查追踪' }}</span>
        <span class="mono">{{ testResult.requestId }}</span>
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

.data {
  text-align: left;
  max-width: 480px;
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
</style>
