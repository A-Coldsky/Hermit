<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { CopyDocument, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText, notifyError, relativeTime } from '@shared/format'
import { api, type ApiKey, type CreatedApiKey, type ConchDetail } from '../api'

const loading = ref(true)
const keys = ref<ApiKey[]>([])
const showRevoked = ref(false)
const conchDetails = ref<ConchDetail[]>([])

const form = reactive({ open: false, saving: false, name: '', readOnly: true, all: false, conches: [] as string[], actions: [] as string[] })
const created = ref<CreatedApiKey | null>(null)

const localNames = computed(() => conchDetails.value.filter((s) => s.source === 'LOCAL').map((s) => s.conch))
const endpoint = computed(() => `${window.location.origin}/mcp`)
/** HTTP 调用示例。能力清单和参数在 /openapi.json。 */
const openapiExample = computed(() => {
  const origin = window.location.origin
  return [
    `curl -H "Authorization: Bearer <API Key>" \\`,
    `  "${origin}/api/<规则包>/<能力>?参数=值"`,
    '',
    `# 全部能力和参数：${origin}/openapi.json`,
  ].join('\n')
})
const clientExample = computed(() => `{
  "mcpServers": {
    "hermit": {
      "url": "${endpoint.value}",
      "headers": { "Authorization": "Bearer <API Key>" }
    }
  }
}`)

/** 发给 Agent 的接入说明。只讲怎么接入和怎么找能力；具体能力的说明在 MCP 里。不含 Key 明文。 */
const connectPrompt = computed(() =>
  [
    '请接入 Hermit Crab 的 MCP 服务，用它提供的工具访问内部系统。',
    '',
    `接入地址是 ${endpoint.value}（Streamable HTTP）。请求头用 Authorization: Bearer <API Key> 或 X-Hermit-Key。Key 由 Crab 本机签发，我会自己配置到 MCP 客户端里。不要把 Key 明文写进对话，也不要向我索要。`,
    '',
    '接入后：',
    '1. 先看 tools/list。工具名形如 规则包__能力，说明里写了用途、注意事项和相关能力，参数在 inputSchema 里。',
    '2. 如果只看到 list_systems、search_capabilities、describe_capability、call_capability，说明能力较多：用 list_systems 看有哪些规则包，用 search_capabilities 找能力，用 describe_capability 看参数和示例，再用 call_capability 调用。',
    '3. 需要了解某个规则包的全貌时，读资源 hermit://conches/<规则包>/guide。',
    '4. 列表里没有的能力就是这把 Key 不能用或已停用，不要猜工具名。调用失败时按返回里的 code 和 hint 处理。',
    '5. 一个规则包有多个账号时，用 account 参数指定账号。',
  ].join('\n'),
)

function conchLabel(s: ConchDetail): string {
  return s.title ? `${s.title}（${s.conch}）` : s.conch
}

const visible = computed(() => keys.value.filter((k) => showRevoked.value || !k.revokedAt))
const actionOptions = computed(() =>
  conchDetails.value
    .filter((s) => !form.conches.length || form.conches.includes(s.conch))
    .map((s) => ({
      label: s.title || s.conch,
      options: s.capabilities
        .filter((c) => !form.readOnly || c.readOnly)
        .map((c) => ({ value: `${s.conch}.${c.action}`, label: `${s.conch}.${c.action}`, title: c.title })),
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

async function loadConches() {
  try {
    const list = (await api.conches()).conches
    conchDetails.value = await Promise.all(list.map((s) => api.conch(s.conch)))
  } catch (error) {
    notifyError(error)
  }
}

function openCreate() {
  Object.assign(form, { open: true, name: '', readOnly: true, all: false, conches: [], actions: [] })
  void loadConches()
}

async function create() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  const validActions = new Set(actionOptions.value.flatMap((g) => g.options.map((o) => o.value)))
  const actions = form.all ? [] : form.actions.filter((a) => validActions.has(a))
  const conches = form.all ? [] : form.conches
  if (!form.all && !conches.length && !actions.length) {
    ElMessage.warning('请选择允许的规则包或能力，或改选「全部权限」')
    return
  }
  form.saving = true
  try {
    created.value = await api.createApiKey({ name: form.name.trim(), readOnly: form.readOnly, all: form.all, conches, actions })
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
      cancelButtonText: '保留',
      type: 'warning',
      confirmButtonType: 'danger',
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
  return list && list.length ? list.join('、') : all
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

    <div class="hm-card connect" data-test="connect-prompt">
      <div class="hm-card-title">Agent 接入提示词</div>
      <div class="connect-bar">
        <el-button :icon="CopyDocument" data-test="copy-connect" @click="copyText(connectPrompt, '已复制接入提示词')">复制</el-button>
        <p class="hm-muted">把下面这段发给 AI，它就知道怎么接入 Crab 和查找能力。里面不含 API Key，Key 请自己配置到 MCP 客户端。</p>
      </div>
      <pre class="prompt">{{ connectPrompt }}</pre>
    </div>

    <div class="hm-card">
      <p class="hm-scroll-hint">表格可以左右滑动，「吊销」在最右侧。</p>
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
              <template v-if="row.scope.all">
                <el-tag size="small" type="danger" effect="plain" data-test="scope-all">全部权限</el-tag>
                <span class="hm-muted">全部规则包的全部能力，包括本地规则包</span>
              </template>
              <template v-else>
                <span><span class="hm-muted">规则包：</span><span class="mono">{{ scopeText(row.scope.conches, '不限') }}</span></span>
                <span><span class="hm-muted">能力：</span><span class="mono">{{ scopeText(row.scope.actions, '所选规则包的全部能力') }}</span></span>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建" width="220">
          <template #default="{ row }"><TimeText :value="row.createdAt" /></template>
        </el-table-column>
        <el-table-column label="最近使用" width="120">
          <template #default="{ row }">{{ row.lastUsedAt ? relativeTime(row.lastUsedAt) : '从未' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.revokedAt" link type="danger" @click="revoke(row)">吊销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="form.open" title="创建 API Key" width="560px">
      <el-alert type="info" :closable="false" show-icon title="创建后完整的 Key 只显示一次，请准备好立即复制保存。" class="once" />
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="用途说明，例如 claude-desktop 或 ops-script" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.readOnly">只允许调用只读能力</el-checkbox>
        </el-form-item>
        <el-form-item label="范围" required>
          <el-radio-group v-model="form.all" data-test="scope-mode">
            <el-radio :value="false">指定规则包或能力</el-radio>
            <el-radio :value="true">全部权限</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert v-if="form.all" type="error" :closable="false" show-icon title="全部权限风险较高" class="risk" data-test="scope-all-risk">
          <div>这把 Key 可以调用本机全部规则包的全部能力，包括本地规则包；以后新订阅的规则包和新导入的本地规则包也自动包含。</div>
          <div v-if="form.readOnly"><strong>这把 Key 不能保存本地规则包。</strong></div>
          <div v-else><strong>没有勾选「只允许调用只读能力」，会修改数据的能力也能调用。</strong></div>
          <div>Key 泄露时影响面最大。只给信任的调用方，能指定范围时尽量指定。</div>
        </el-alert>
        <el-form-item v-if="!form.all" label="允许的规则包">
          <el-select v-model="form.conches" multiple clearable placeholder="不选表示按下面的能力限定" class="full">
            <el-option v-for="s in conchDetails" :key="s.conch" :label="conchLabel(s)" :value="s.conch">
              <span>{{ conchLabel(s) }}</span>
              <el-tag v-if="s.source === 'LOCAL'" type="info" effect="plain" size="small" class="tag">本地</el-tag>
            </el-option>
          </el-select>
          <p v-if="localNames.length && !form.conches.length" class="hint" data-test="local-hint">
            本地规则包（{{ localNames.join('、') }}）要在这里明确选中才能调用。
          </p>
        </el-form-item>
        <el-form-item v-if="!form.all" label="允许的能力">
          <el-select v-model="form.actions" multiple clearable filterable placeholder="不选表示所选规则包的全部能力" class="full">
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
      <p class="hint">MCP 客户端配置（接入地址 <span class="mono">{{ endpoint }}</span>）</p>
      <pre class="example" data-test="key-example">{{ clientExample }}</pre>
      <p class="hint">OpenAPI 调用</p>
      <pre class="example" data-test="key-openapi-example">{{ openapiExample }}</pre>
      <template #footer>
        <el-button type="primary" @click="created = null">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.connect {
  margin-bottom: 16px;
}

.connect-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.connect-bar p {
  margin: 0;
}

.prompt {
  margin: 8px 0 0;
  padding: 12px;
  white-space: pre-wrap;
  line-height: 1.6;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

/* 示例完整显示：换行而不是截断，带底色和等宽字。 */
.example {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--hm-code-bg);
  color: var(--hm-code-text);
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  overflow-x: auto;
}

.once {
  margin-bottom: 16px;
}

.tag {
  margin-left: 6px;
}

.risk {
  margin-bottom: 18px;
  line-height: 1.6;
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

.hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning-dark-2);
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
  background: var(--hm-code-bg);
  color: #a7f3d0;
  border: 1px solid var(--hm-border);
  word-break: break-all;
}
</style>
