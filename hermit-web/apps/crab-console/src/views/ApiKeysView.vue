<script setup lang="ts">
import { CopyDocument, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText, formatTime, notifyError, relativeTime } from '@shared/format'
import { api, type ApiKey, type CreatedApiKey, type ShellDetail } from '../api'

const loading = ref(true)
const keys = ref<ApiKey[]>([])
const showRevoked = ref(false)
const shellDetails = ref<ShellDetail[]>([])

const form = reactive({ open: false, saving: false, name: '', readOnly: true, shells: [] as string[], actions: [] as string[] })
const created = ref<CreatedApiKey | null>(null)

const visible = computed(() => keys.value.filter((k) => showRevoked.value || !k.revokedAt))
const actionOptions = computed(() =>
  shellDetails.value
    .filter((s) => !form.shells.length || form.shells.includes(s.shell))
    .map((s) => ({
      label: s.title || s.shell,
      options: s.capabilities
        .filter((c) => !form.readOnly || c.readOnly)
        .map((c) => ({ value: `${s.shell}.${c.action}`, label: `${s.shell}.${c.action}`, title: c.title })),
    }))
    .filter((group) => group.options.length),
)

async function load() {
  loading.value = true
  try {
    keys.value = (await api.apiKeys()).apiKeys
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function loadShells() {
  try {
    const list = (await api.shells()).shells
    shellDetails.value = await Promise.all(list.map((s) => api.shell(s.shell)))
  } catch (error) {
    notifyError(error)
  }
}

function openCreate() {
  Object.assign(form, { open: true, name: '', readOnly: true, shells: [], actions: [] })
  void loadShells()
}

async function create() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  form.saving = true
  try {
    const validActions = new Set(actionOptions.value.flatMap((g) => g.options.map((o) => o.value)))
    created.value = await api.createApiKey({
      name: form.name.trim(),
      readOnly: form.readOnly,
      shells: form.shells,
      actions: form.actions.filter((a) => validActions.has(a)),
    })
    form.open = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    form.saving = false
  }
}

async function revoke(key: ApiKey) {
  try {
    await ElMessageBox.confirm(`吊销后使用 ${key.prefix}… 的调用方会立即失败，且无法恢复。`, `吊销 ${key.name}`, {
      confirmButtonText: '吊销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api.revokeApiKey(key.id)
    ElMessage.success('已吊销')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

function scopeText(list: string[] | null, all: string): string {
  return list && list.length ? list.join(', ') : all
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="API Key" description="调用本机 OpenAPI 和 MCP 时用 Authorization: Bearer <key> 认证">
      <el-checkbox v-model="showRevoked">显示已吊销</el-checkbox>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">创建 API Key</el-button>
    </PageHeader>

    <div class="hm-card">
      <el-table v-loading="loading" :data="visible">
        <template #empty>{{ loading ? '' : '还没有 API Key' }}</template>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <el-tag v-if="row.revokedAt" type="info" size="small" class="tag">已吊销</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="前缀" width="140">
          <template #default="{ row }"><code>{{ row.prefix }}…</code></template>
        </el-table-column>
        <el-table-column label="范围" min-width="260">
          <template #default="{ row }">
            <div class="scope">
              <el-tag v-if="row.scope.readOnly" size="small" type="success" effect="plain">仅只读能力</el-tag>
              <span><span class="hm-muted">Shell：</span><span class="mono">{{ scopeText(row.scope.shells, '全部') }}</span></span>
              <span><span class="hm-muted">能力：</span><span class="mono">{{ scopeText(row.scope.actions, '全部') }}</span></span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最近使用" width="120">
          <template #default="{ row }">{{ row.lastUsedAt ? relativeTime(row.lastUsedAt) : '从未' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right">
          <template #default="{ row }">
            <el-button v-if="!row.revokedAt" link type="danger" @click="revoke(row)">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="form.open" title="创建 API Key" width="560px">
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="用途说明，例如 claude-desktop 或 ops-script" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.readOnly">只允许调用只读能力</el-checkbox>
        </el-form-item>
        <el-form-item label="允许的 Shell">
          <el-select v-model="form.shells" multiple clearable placeholder="不选表示全部 Shell" class="full">
            <el-option v-for="s in shellDetails" :key="s.shell" :label="s.title ? `${s.title}（${s.shell}）` : s.shell" :value="s.shell" />
          </el-select>
        </el-form-item>
        <el-form-item label="允许的能力">
          <el-select v-model="form.actions" multiple clearable filterable placeholder="不选表示所选 Shell 的全部能力" class="full">
            <el-option-group v-for="group in actionOptions" :key="group.label" :label="group.label">
              <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value">
                <span class="mono">{{ option.label }}</span>
                <span class="hm-muted option-title">{{ option.title }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.open = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!created" title="API Key 已创建" width="560px" :close-on-click-modal="false" @close="created = null">
      <el-alert type="warning" :closable="false" show-icon title="完整的 Key 只显示这一次，关闭后无法再查看。请立即复制保存。" />
      <div v-if="created" class="plaintext">
        <code>{{ created.plaintext }}</code>
        <el-button :icon="CopyDocument" @click="copyText(created.plaintext, 'API Key 已复制')">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="created = null">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag {
  margin-left: 6px;
}

.scope {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.full {
  width: 100%;
}

.option-title {
  margin-left: 8px;
  font-size: 12px;
}

.plaintext {
  margin-top: 16px;
  display: flex;
  gap: 12px;
  align-items: center;
}

.plaintext code {
  flex: 1;
  padding: 10px 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #a7f3d0;
  word-break: break-all;
}
</style>
