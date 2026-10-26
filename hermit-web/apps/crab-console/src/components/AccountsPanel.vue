<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reactive, ref } from 'vue'
import JsonBlock from '@shared/components/JsonBlock.vue'
import { ApiError } from '@shared/http'
import { errorCodeLabels, errorText, notifyError } from '@shared/format'
import { api, type Account, type CredentialField } from '../api'
import CredentialDialog from './CredentialDialog.vue'

const props = defineProps<{ shell: string; accounts: Account[]; credentials: CredentialField[] }>()
const emit = defineEmits<{ changed: [] }>()

const creating = reactive({ open: false, name: '', saving: false })
const editing = reactive({ open: false, account: '' })
const busy = ref<string | null>(null)
const testResult = reactive({
  open: false,
  account: '',
  success: false,
  data: null as unknown,
  message: '',
  code: '',
  traceId: '' as string | null,
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
    const result = await api.createAccount(props.shell, name)
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
    await api.makeDefault(props.shell, account.name)
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
      `删除账号 ${account.name} 会同时删除它保存的凭证和登录会话。${account.default ? '它是默认账号，删除后不带 X-Hermit-Account 的调用将无法选中账号。' : ''}`,
      '删除账号',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = account.name
  try {
    await api.deleteAccount(props.shell, account.name)
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
    const data = await api.testLogin(props.shell, account.name)
    Object.assign(testResult, { open: true, account: account.name, success: true, data, message: '', code: '', traceId: null })
  } catch (error) {
    Object.assign(testResult, {
      open: true,
      account: account.name,
      success: false,
      data: null,
      message: errorText(error),
      code: error instanceof ApiError ? error.code : '',
      traceId: error instanceof ApiError ? error.traceId : null,
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
    <el-table :data="accounts" empty-text="还没有账号，新建一个后填写凭证">
      <el-table-column label="账号" min-width="180">
        <template #default="{ row }">
          <span class="mono strong">{{ row.name }}</span>
          <el-tag v-if="row.default" size="small" class="tag">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="凭证" min-width="220">
        <template #default="{ row }">
          <template v-if="row.credentialsUnreadable">
            <el-tag type="danger" effect="plain" size="small">无法解密</el-tag>
            <span class="note">{{ row.credentialMessage ?? '凭证无法解密，需重新配置' }}</span>
          </template>
          <el-tag v-else-if="!row.needsConfiguration" type="success" effect="plain" size="small">已填写</el-tag>
          <template v-else>
            <el-tag type="warning" effect="plain" size="small">缺少</el-tag>
            <span class="mono missing">{{ row.missingFields.join(', ') }}</span>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="自动登录" min-width="220">
        <template #default="{ row }">
          <template v-if="row.loginPaused">
            <el-tag type="danger" effect="plain" size="small">已暂停</el-tag>
            <div v-if="row.loginPauseReason" class="note">{{ row.loginPauseReason }}</div>
            <div class="hm-muted note">修正凭证后点「测试登录」，登录成功即恢复。</div>
          </template>
          <span v-else class="hm-muted">正常</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="330" align="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!credentials.length" @click="editCredentials(row)">填写凭证</el-button>
          <el-button link type="primary" :loading="busy === row.name" @click="testLogin(row)">测试登录</el-button>
          <el-button v-if="!row.default" link @click="makeDefault(row)">设为默认</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
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

  <CredentialDialog v-model:open="editing.open" :shell="shell" :account="editing.account" :fields="credentials" @saved="emit('changed')" />

  <el-dialog v-model="testResult.open" :title="`测试登录 · ${testResult.account}`" width="560px">
    <el-result v-if="testResult.success" icon="success" title="登录成功" sub-title="凭证可用，会话已缓存">
      <template v-if="testResult.data" #extra>
        <JsonBlock :value="testResult.data" class="data" />
      </template>
    </el-result>
    <el-result
      v-else
      icon="error"
      :title="errorCodeLabels[testResult.code] ?? '登录失败'"
      :sub-title="testResult.message"
    >
      <template v-if="testResult.traceId" #extra>
        <span class="hm-muted">traceId：</span>
        <router-link :to="{ path: '/calls', query: { trace: testResult.traceId } }" class="mono">{{ testResult.traceId }}</router-link>
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
  color: var(--hm-accent);
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
</style>
