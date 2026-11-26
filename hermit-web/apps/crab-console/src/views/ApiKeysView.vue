<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { CopyDocument, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText, notifyError, relativeTime } from '@shared/format'
import { buildGroupTree, flattenGroups, groupMatches } from '@shared/groups'
import { api, type ApiKey, type CreatedApiKey, type ConchDetail } from '../api'
import { connectPrompt } from '../connect'

const loading = ref(true)
/** 读取失败和「一把都没有」要分开：失败时不能让人以为 Key 没了。 */
const loadFailed = ref(false)
const promptOpen = ref(false)
const conchesLoaded = ref(false)
const keys = ref<ApiKey[]>([])
const showRevoked = ref(false)
const conchDetails = ref<ConchDetail[]>([])
let conchesSeq = 0

const form = reactive({
  open: false,
  saving: false,
  name: '',
  readOnly: true,
  all: false,
  devTools: false,
  conches: [] as string[],
  groups: [] as string[],
  actions: [] as string[],
})
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

const connectText = computed(() => connectPrompt(endpoint.value))

function conchLabel(s: ConchDetail): string {
  return s.title ? `${s.title}（${s.conch}）` : s.conch
}

const visible = computed(() => keys.value.filter((k) => showRevoked.value || !k.revokedAt))

const selectedConches = computed(() =>
  conchDetails.value.filter((s) => !form.conches.length || form.conches.includes(s.conch)),
)

function parentCovered(value: string): boolean {
  const colon = value.indexOf(':')
  if (colon <= 0) return false
  const conch = value.slice(0, colon)
  const path = value.slice(colon + 1)
  const slash = path.indexOf('/')
  if (slash <= 0) return false
  const parent = `${conch}:${path.slice(0, slash)}`
  return form.groups.includes(parent)
}

function actionCoveredByGroup(conch: string, group: string | null | undefined): boolean {
  if (!group) return false
  const prefix = `${conch}:`
  return form.groups.some((entry) => entry.startsWith(prefix) && groupMatches(group, entry.slice(prefix.length)))
}

const groupOptions = computed(() =>
  selectedConches.value
    .filter((s) => s.groups && s.groups.length)
    .map((s) => ({
      key: s.conch,
      label: s.title || s.conch,
      options: flattenGroups(s.groups)
        .map((g) => {
          const count = s.capabilities.filter(
            (c) => (!form.readOnly || c.readOnly) && groupMatches(c.group, g.path),
          ).length
          const value = `${s.conch}:${g.path}`
          return {
            value,
            label: `${s.conch}:${g.path}`,
            title: g.title,
            fullTitle: g.fullTitle,
            depth: g.depth,
            count,
            covered: parentCovered(value),
          }
        })
        .filter((opt) => opt.count > 0),
    }))
    .filter((section) => section.options.length),
)

const hasAnyGroups = computed(() => groupOptions.value.length > 0)

const actionOptions = computed(() => {
  const sections: {
    key: string
    label: string
    options: { value: string; label: string; title: string; covered: boolean }[]
  }[] = []
  for (const s of selectedConches.value) {
    const eligible = s.capabilities.filter((c) => !form.readOnly || c.readOnly)
    if (!eligible.length) continue
    const conchTitle = s.title || s.conch
    const tree = buildGroupTree(s.groups, eligible, (c) => c.group)
    const mapCap = (c: (typeof eligible)[number]) => ({
      value: `${s.conch}.${c.action}`,
      label: `${s.conch}.${c.action}`,
      title: c.title,
      covered: actionCoveredByGroup(s.conch, c.group),
    })
    if (!tree.hasGroups) {
      sections.push({ key: s.conch, label: conchTitle, options: eligible.map(mapCap) })
      continue
    }
    if (tree.topLevel.length) {
      sections.push({ key: `${s.conch}::__top__`, label: `${conchTitle} · 顶层能力`, options: tree.topLevel.map(mapCap) })
    }
    for (const g of tree.groups) {
      if (g.direct.length) {
        sections.push({ key: `${s.conch}::${g.path}`, label: `${conchTitle} / ${g.title}`, options: g.direct.map(mapCap) })
      }
      for (const c of g.children) {
        if (c.all.length) {
          sections.push({ key: `${s.conch}::${c.path}`, label: `${conchTitle} / ${g.title} / ${c.title}`, options: c.all.map(mapCap) })
        }
      }
    }
  }
  return sections
})

const validGroupValues = computed(() => new Set(groupOptions.value.flatMap((g) => g.options.map((o) => o.value))))
const validActionValues = computed(() => new Set(actionOptions.value.flatMap((g) => g.options.map((o) => o.value))))

// 切换只读、收窄规则包或重新加载规则包后，及时清掉已经不在可选项里的分组和能力，避免残留项误触发本地包补齐而悄悄扩大范围。
watch(
  () => [form.readOnly, form.conches.join('\0'), conchesLoaded.value, conchDetails.value] as const,
  () => {
    if (!conchesLoaded.value) return
    const validGroups = validGroupValues.value
    const nextGroups = form.groups.filter((g) => validGroups.has(g))
    if (nextGroups.length !== form.groups.length) {
      form.groups = nextGroups
    }
    const validActions = validActionValues.value
    const nextActions = form.actions.filter((a) => validActions.has(a))
    if (nextActions.length !== form.actions.length) {
      form.actions = nextActions
    }
  },
)

const referencedConches = computed(() => {
  const referenced = new Set<string>()
  const validGroups = validGroupValues.value
  const validActions = validActionValues.value
  for (const g of form.groups) {
    if (conchesLoaded.value && !validGroups.has(g)) continue
    const colon = g.indexOf(':')
    if (colon > 0) referenced.add(g.slice(0, colon))
  }
  for (const a of form.actions) {
    if (conchesLoaded.value && !validActions.has(a)) continue
    const dot = a.indexOf('.')
    if (dot > 0) referenced.add(a.slice(0, dot))
  }
  return [...referenced]
})

/** 选了本地包的分组或能力、但没有在「允许的规则包」点名该本地包时，提示并支持一键补齐。 */
const missingLocalConches = computed(() => {
  if (form.all || !localNames.value.length) return []
  const localSet = new Set(localNames.value)
  return referencedConches.value.filter((name) => localSet.has(name) && !form.conches.includes(name))
})

function includeMissingLocals() {
  // 若「允许的规则包」原本留空，补齐本地包的同时也补上已选分组/能力涉及的正式包，避免非空 conches 意外把它们排除。
  const toAdd = form.conches.length ? missingLocalConches.value : referencedConches.value
  form.conches = [...new Set([...form.conches, ...toAdd])]
}

async function load() {
  loading.value = true
  try {
    keys.value = (await api.apiKeys()).apiKeys
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function loadConches() {
  const seq = ++conchesSeq
  try {
    const list = (await api.conches()).conches.filter((s) => s.ruleDisabled !== true)
    const results = await Promise.allSettled(list.map((s) => api.conch(s.conch)))
    if (seq !== conchesSeq) return
    const loaded: ConchDetail[] = []
    let firstError: unknown = null
    for (const result of results) {
      if (result.status === 'fulfilled') {
        if (result.value.ruleDisabled !== true) {
          loaded.push(result.value)
        }
      } else if (firstError === null) {
        firstError = result.reason
      }
    }
    conchDetails.value = loaded
    conchesLoaded.value = true
    if (firstError !== null) {
      notifyError(firstError)
    }
  } catch (error) {
    if (seq !== conchesSeq) return
    notifyError(error)
  }
}

function openCreate() {
  Object.assign(form, { open: true, name: '', readOnly: true, all: false, devTools: false, conches: [], groups: [], actions: [] })
  void loadConches()
}

async function create() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  const validGroups = validGroupValues.value
  const rawGroups = form.all ? [] : form.groups.filter((g) => validGroups.has(g))
  // 一级分组已经覆盖其二级子组，去掉冗余子组保持范围简洁。
  const groups = rawGroups.filter((g) => {
    const colon = g.indexOf(':')
    const path = g.slice(colon + 1)
    const slash = path.indexOf('/')
    return slash < 0 || !rawGroups.includes(`${g.slice(0, colon)}:${path.slice(0, slash)}`)
  })
  const validActions = validActionValues.value
  const actions = form.all ? [] : form.actions.filter((a) => validActions.has(a))
  const conches = form.all ? [] : form.conches
  if (!form.all && !conches.length && !groups.length && !actions.length) {
    ElMessage.warning(
      hasAnyGroups.value
        ? '请选择允许的规则包、分组或能力，或改选「全部权限」'
        : '请选择允许的规则包或能力，或改选「全部权限」',
    )
    return
  }
  form.saving = true
  try {
    const base = groups.length
      ? { name: form.name.trim(), readOnly: form.readOnly, all: form.all, conches, groups, actions }
      : { name: form.name.trim(), readOnly: form.readOnly, all: form.all, conches, actions }
    created.value = await api.createApiKey(form.devTools ? { ...base, devTools: true } : base)
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

/** 明文只显示一次：除了「我已保存」，其它关闭方式都先问一句。 */
async function confirmCloseCreated(done: () => void) {
  try {
    await ElMessageBox.confirm('关闭后无法再查看完整的 Key。没保存的话只能吊销这把、重新创建。', '已经保存 API Key 了吗？', {
      confirmButtonText: '已保存，关闭',
      cancelButtonText: '返回复制',
      type: 'warning',
    })
  } catch {
    return
  }
  done()
}

function scopeText(list: string[] | null | undefined, all: string): string {
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
        <el-button :icon="CopyDocument" data-test="copy-connect" @click="copyText(connectText, '已复制接入提示词')">复制</el-button>
        <el-button link type="primary" data-test="toggle-connect" @click="promptOpen = !promptOpen">{{ promptOpen ? '收起全文' : '查看全文' }}</el-button>
        <p class="hm-muted">把下面这段发给 AI，它会自己把 Crab 加进 MCP 配置，并告诉你 API Key 该填在哪里。里面不含 API Key；在本页创建 Key 后，按它说的位置填进去。</p>
      </div>
      <pre v-show="promptOpen" class="prompt">{{ connectText }}</pre>
    </div>

    <div class="hm-card">
      <el-alert
        v-if="loadFailed"
        type="error"
        show-icon
        :closable="false"
        class="load-failed"
        :title="keys.length ? 'API Key 列表刷新失败，下面是上一次读到的内容' : 'API Key 列表读取失败'"
        data-test="keys-load-failed"
      >
        <el-button size="small" :loading="loading" @click="load">重试</el-button>
      </el-alert>
      <p class="hm-scroll-hint">表格可以左右滑动，「吊销」在最右侧。</p>
      <el-table v-loading="loading" :data="visible">
        <template #empty>{{ loading ? '' : loadFailed ? '没有读到 API Key 列表' : '还没有 API Key' }}</template>
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
              <el-tag v-if="row.scope.devTools" size="small" type="warning" effect="plain" data-test="scope-dev">开发者工具</el-tag>
              <template v-if="row.scope.all">
                <el-tag size="small" type="danger" effect="plain" data-test="scope-all">全部权限</el-tag>
                <span class="hm-muted">全部规则包的全部能力，包括本地规则包</span>
              </template>
              <template v-else>
                <span><span class="hm-muted">规则包：</span><span class="mono">{{ scopeText(row.scope.conches, '不限') }}</span></span>
                <span v-if="row.scope.groups?.length" data-test="scope-groups">
                  <span class="hm-muted">分组：</span><span class="mono">{{ row.scope.groups.join('、') }}</span>
                </span>
                <span>
                  <span class="hm-muted">能力：</span>
                  <span class="mono">{{ scopeText(row.scope.actions, row.scope.groups?.length ? '按上述分组' : '所选规则包的全部能力') }}</span>
                </span>
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

    <el-dialog v-model="form.open" title="创建 API Key" width="600px">
      <el-alert type="info" :closable="false" show-icon title="创建后完整的 Key 只显示一次，请准备好立即复制保存。" class="once" />
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="用途说明，例如 claude-desktop 或 ops-script" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.readOnly">只允许调用只读能力</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.devTools" data-test="scope-dev-tools">开发者工具（写规则包用）</el-checkbox>
          <div class="hm-muted dev-hint">
            勾选后，这把 Key 在本机开发者模式打开时能看到内置的 <span class="mono">hermit_dev</span> 工具包（校验、保存本地规则包、试跑、读开发追踪）。
            「全部权限」不包含它；只用来调用能力的 Key 不要勾选。
          </div>
          <div v-if="form.devTools && form.readOnly" class="hm-muted dev-hint" data-test="scope-dev-readonly-hint">
            同时勾选只读时，只能读编写指南、读 YAML、校验语法和试跑只读能力（全部权限时还可读抓包快照），<strong>不能保存本地规则包，也不能读取控制台触发的开发追踪</strong>。
          </div>
        </el-form-item>
        <el-form-item label="范围" required>
          <el-radio-group v-model="form.all" data-test="scope-mode">
            <el-radio :value="false">指定规则包、分组或能力</el-radio>
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
          <el-alert
            v-if="conchesLoaded && !conchDetails.length"
            type="warning"
            show-icon
            :closable="false"
            class="no-conches"
            title="本机还没有规则包，现在没有可选的范围"
            data-test="no-conches"
          >
            先<router-link to="/catalog">订阅规则包</router-link>，或在<router-link to="/conches">规则包</router-link>页导入本地规则包，再回来创建 Key。不建议为了跳过这一步改选「全部权限」。
          </el-alert>
          <el-select v-model="form.conches" multiple clearable placeholder="不选表示按下面的分组或能力限定" class="full" data-test="select-conches">
            <el-option v-for="s in conchDetails" :key="s.conch" :label="conchLabel(s)" :value="s.conch">
              <span>{{ conchLabel(s) }}</span>
              <el-tag v-if="s.groups?.length" size="small" effect="plain" class="tag">{{ s.groups.length }} 个分组</el-tag>
              <el-tag v-if="s.source === 'LOCAL'" type="info" effect="plain" size="small" class="tag">本地</el-tag>
            </el-option>
          </el-select>
          <p v-if="localNames.length && !form.conches.length" class="hint" data-test="local-hint">
            本地规则包（{{ localNames.join('、') }}）要在这里明确选中才能调用。
          </p>
          <el-alert
            v-if="missingLocalConches.length"
            type="warning"
            show-icon
            :closable="false"
            class="missing-local"
            data-test="missing-local-alert"
            :title="`已选分组或能力涉及本地规则包（${missingLocalConches.join('、')}），需同时在「允许的规则包」中选中才能调用`"
          >
            <el-button size="small" type="warning" plain data-test="add-missing-locals" @click="includeMissingLocals">
              加入允许的规则包
            </el-button>
          </el-alert>
        </el-form-item>
        <el-form-item v-if="!form.all && hasAnyGroups" label="允许的分组" data-test="groups-field">
          <el-select
            v-model="form.groups"
            multiple
            clearable
            filterable
            placeholder="可选。按一级或二级分组授权（选中一级分组自动包含其二级子组）"
            class="full"
            data-test="select-groups"
          >
            <el-option-group v-for="section in groupOptions" :key="section.key" :label="section.label">
              <el-option
                v-for="option in section.options"
                :key="option.value"
                :label="`${option.label} · ${option.fullTitle}`"
                :value="option.value"
                :disabled="option.covered"
              >
                <span :class="{ 'sub-group': option.depth === 2 }">
                  <span v-if="option.depth === 2" class="hm-muted">↳ </span>
                  <span class="mono">{{ option.label }}</span>
                  <span class="hm-muted option-title">{{ option.title }}（{{ option.count }} 个能力）</span>
                </span>
                <el-tag v-if="option.covered" size="small" type="info" effect="plain" class="tag">已由上级分组包含</el-tag>
              </el-option>
            </el-option-group>
          </el-select>
          <p class="field-tip">按分组授权时，以后该分组新增的能力会自动包含；也可在下方额外点选顶层未分组能力或其他单个能力。</p>
        </el-form-item>
        <el-form-item v-if="!form.all" label="允许的能力">
          <el-select
            v-model="form.actions"
            multiple
            clearable
            filterable
            :placeholder="form.groups.length ? '可选。在已选分组之外，额外点选单个能力（如顶层未分组能力）' : '不选表示所选规则包的全部能力'"
            class="full"
            data-test="select-actions"
          >
            <el-option-group v-for="group in actionOptions" :key="group.key" :label="group.label">
              <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value">
                <span class="mono">{{ option.label }}</span>
                <span class="hm-muted option-title">{{ option.title }}</span>
                <el-tag v-if="option.covered" size="small" type="info" effect="plain" class="tag">已由分组包含</el-tag>
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

    <el-dialog :model-value="!!created" title="API Key 已创建" width="560px" :close-on-click-modal="false" :before-close="confirmCloseCreated" data-test="created-dialog" @close="created = null">
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
.dev-hint {
  line-height: 1.5;
  font-size: 12px;
}
.connect {
  margin-bottom: 16px;
}

.connect-bar {
  display: flex;
  flex-wrap: wrap;
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

.load-failed,
.no-conches {
  margin-bottom: 12px;
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

.field-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--hm-muted);
}

.missing-local {
  margin-top: 8px;
}

.sub-group {
  padding-left: 12px;
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
