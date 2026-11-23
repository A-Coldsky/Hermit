<script setup lang="ts">
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { useNarrow } from '@shared/narrow'
import { api, type AdminRole, type AdminUser } from '../api'
import { session } from '../auth'
import { roleLabels, userErrorText } from '../labels'

/** 手机上不固定操作列：固定列会盖住大半张表，前面几列读不到。 */
const narrow = useNarrow()
const loading = ref(true)
const loadFailed = ref(false)
const users = ref<AdminUser[]>([])
const busy = ref<string | null>(null)

const createRef = ref<FormInstance>()
const create = reactive({ open: false, saving: false, username: '', password: '', role: 'USER' as AdminRole })
const resetRef = ref<FormInstance>()
const reset = reactive({ open: false, saving: false, username: '', password: '' })

const passwordRule = [
  { required: true, message: '请输入密码', trigger: 'blur' },
  { min: 8, message: '密码至少 8 个字符', trigger: 'blur' },
]
const createRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: passwordRule,
}
const resetRules: FormRules = { password: passwordRule }

function fail(error: unknown) {
  const text = error instanceof ApiError ? userErrorText(error.message) : null
  if (text) ElMessage.error(text)
  else notifyError(error)
}

async function load() {
  loading.value = true
  try {
    users.value = await api.users()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function isSelf(user: AdminUser) {
  return user.username === session.user
}

function openCreate() {
  Object.assign(create, { open: true, username: '', password: '', role: 'USER' })
  createRef.value?.clearValidate()
}

async function submitCreate() {
  if (!(await createRef.value?.validate().catch(() => false))) return
  create.saving = true
  try {
    await api.createUser(create.username.trim(), create.password, create.role)
    ElMessage.success(`已新建 ${roleLabels[create.role]} ${create.username.trim()}`)
    create.open = false
    await load()
  } catch (error) {
    fail(error)
  } finally {
    create.saving = false
  }
}

async function changeRole(user: AdminUser, role: AdminRole) {
  if (role === user.role) return
  const message =
    role === 'ADMIN'
      ? `${user.username} 将可以上传、发布、停用规则包，管理用户和查看审计。`
      : `${user.username} 将只能浏览，并管理自己的令牌。`
  try {
    await ElMessageBox.confirm(message, `设为${roleLabels[role]}`, { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await update(user, { role }, `${user.username} 已设为${roleLabels[role]}`)
}

async function toggleDisabled(user: AdminUser) {
  if (user.disabled) {
    await update(user, { disabled: false }, `已启用 ${user.username}。禁用时吊销的令牌不会恢复，需要重新生成`)
    return
  }
  const ownsConfig = await ownsConfigToken(user.username)
  const config = ownsConfig
    ? '配置令牌 HERMIT_REEF_AGENT_TOKEN 也归他，会一起作废，只有在 Reef 配置里换一串新值才能恢复。'
    : ''
  try {
    await ElMessageBox.confirm(
      `禁用后 ${user.username} 不能登录，名下 ${user.tokenCount} 把令牌会被吊销，使用这些令牌的 Crab 会停止同步和上报。${config}重新启用账号也不会恢复这些令牌，需要重新生成并在 Crab 上重新导入。`,
      `禁用 ${user.username}`,
      { confirmButtonText: '禁用并吊销令牌', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  await update(user, { disabled: true }, `已禁用 ${user.username}，名下令牌已吊销`)
}

async function ownsConfigToken(username: string): Promise<boolean> {
  try {
    return (await api.tokens(true)).some((t) => t.source === 'CONFIG' && t.owner === username && !t.revokedAt)
  } catch {
    return false
  }
}

async function update(user: AdminUser, body: { role?: AdminRole; disabled?: boolean }, success: string) {
  busy.value = user.username
  try {
    await api.updateUser(user.username, body)
    ElMessage.success(success)
    await load()
  } catch (error) {
    fail(error)
  } finally {
    busy.value = null
  }
}

function openReset(user: AdminUser) {
  Object.assign(reset, { open: true, username: user.username, password: '' })
  resetRef.value?.clearValidate()
}

async function submitReset() {
  if (!(await resetRef.value?.validate().catch(() => false))) return
  reset.saving = true
  try {
    await api.resetPassword(reset.username, reset.password)
    ElMessage.success(`已重置 ${reset.username} 的密码，请把新密码告诉对方`)
    reset.open = false
  } catch (error) {
    fail(error)
  } finally {
    reset.saving = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="用户管理" description="管理员可以管理后台；普通用户只能浏览，并生成自己的 Agent 令牌">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" data-test="create-user" @click="openCreate">新建用户</el-button>
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="load-failed"
      :title="users.length ? '用户列表刷新失败，下面是上一次读到的内容' : '用户列表读取失败'"
      data-test="users-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div class="hm-card">
      <p class="hm-scroll-hint">表格可以左右滑动，操作在最右侧。</p>
      <el-table v-loading="loading" :data="users">
        <template #empty>{{ loading || loadFailed ? '' : '没有用户' }}</template>
        <el-table-column label="用户名" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="isSelf(row)" size="small" effect="plain" class="tag">我</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="150">
          <template #default="{ row }">
            <el-select
              :model-value="row.role"
              size="small"
              :disabled="isSelf(row) || busy === row.username"
              :aria-label="`${row.username} 的角色`"
              @update:model-value="(role: AdminRole) => changeRole(row, role)"
            >
              <el-option label="管理员" value="ADMIN" />
              <el-option label="普通用户" value="USER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.disabled" type="info" size="small" effect="plain">已禁用</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tokenCount" label="有效令牌" width="100" align="right" />
        <el-table-column label="创建" min-width="170">
          <template #default="{ row }"><TimeText :value="row.createdAt" /></template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="right" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <template v-if="!isSelf(row)">
              <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
              <el-button
                link
                :type="row.disabled ? 'primary' : 'danger'"
                :loading="busy === row.username"
                @click="toggleDisabled(row)"
              >
                {{ row.disabled ? '启用' : '禁用' }}
              </el-button>
            </template>
            <span v-else class="hm-muted">右上角改自己的密码</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="create.open" title="新建用户" width="440px">
      <el-form ref="createRef" :model="create" :rules="createRules" label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="create.username" maxlength="128" autocomplete="off" data-test="new-username" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="create.password" type="password" show-password autocomplete="new-password" data-test="new-password" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="create.role">
            <el-radio value="USER">普通用户：只能浏览，管理自己的令牌</el-radio>
            <el-radio value="ADMIN">管理员：可以管理后台</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.open = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" data-test="submit-user" @click="submitCreate">新建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reset.open" :title="`重置 ${reset.username} 的密码`" width="420px">
      <el-form ref="resetRef" :model="reset" :rules="resetRules" label-position="top" @submit.prevent="submitReset">
        <el-form-item label="新密码" prop="password">
          <el-input v-model="reset.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reset.open = false">取消</el-button>
        <el-button type="primary" :loading="reset.saving" @click="submitReset">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.load-failed {
  margin-bottom: 16px;
}

.tag {
  margin-left: 6px;
}

.el-radio-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
</style>
