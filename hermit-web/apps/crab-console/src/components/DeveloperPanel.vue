<script setup lang="ts">
import { CopyDocument } from '@element-plus/icons-vue'
import { computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import JsonBlock from '@shared/components/JsonBlock.vue'
import { ApiError, type Envelope } from '@shared/http'
import { copyText, errorCodeLabels, errorText, notifyError } from '@shared/format'
import { buildGroupTree, type GroupDef } from '@shared/groups'
import { api, type Account, type CapabilityState, type McpRefreshResult } from '../api'
import ShellPicker from './ShellPicker.vue'
import TrustJump from './TrustJump.vue'

const props = defineProps<{
  conch: string
  driver: 'http' | 'mcp'
  debugEnabled: boolean
  capabilities: CapabilityState[]
  groups?: GroupDef[]
  accounts: Account[]
}>()

const emit = defineEmits<{ changed: [] }>()

const runnable = computed(() => props.capabilities.filter((c) => !c.disabled))
const groupedRunnable = computed(() => buildGroupTree(props.groups, runnable.value, (c) => c.group))
const runnableOptionGroups = computed(() => {
  const tree = groupedRunnable.value
  const sections: { label: string; items: CapabilityState[] }[] = []
  for (const g of tree.groups) {
    if (g.direct.length) {
      sections.push({ label: g.title, items: g.direct })
    }
    for (const c of g.children) {
      if (c.all.length) {
        sections.push({ label: `${g.title} / ${c.title}`, items: c.all })
      }
    }
  }
  return sections
})
const defaultAccount = computed(() => props.accounts.find((a) => a.default)?.name ?? props.accounts[0]?.name ?? '')
const isMcp = computed(() => props.driver === 'mcp')

const trial = reactive({
  action: '',
  account: '',
  input: '{}',
  running: false,
})
const trialResult = reactive({
  open: false,
  success: false,
  data: null as unknown,
  message: '',
  code: '',
  traceId: '' as string | null,
  requestId: '' as string | null,
  requestNote: '' as string | null,
})

const refresh = reactive({
  account: '',
  running: false,
  open: false,
  result: null as McpRefreshResult | null,
})

function parseInput(): Record<string, unknown> | null {
  const raw = trial.input.trim() || '{}'
  try {
    const value = JSON.parse(raw) as unknown
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      ElMessage.warning('入参必须是 JSON 对象')
      return null
    }
    return value as Record<string, unknown>
  } catch {
    ElMessage.warning('入参不是合法 JSON')
    return null
  }
}

async function run() {
  const action = trial.action || runnable.value[0]?.action
  if (!action) {
    ElMessage.warning('没有可试跑的能力')
    return
  }
  const input = parseInput()
  if (!input) return
  trial.running = true
  try {
    const body: { account?: string; input?: Record<string, unknown> } = { input }
    const account = trial.account || defaultAccount.value
    if (account) body.account = account
    const envelope: Envelope<Record<string, unknown>> = await api.runLocal(props.conch, action, body)
    Object.assign(trialResult, {
      open: true,
      success: true,
      data: envelope.data,
      message: '',
      code: '',
      traceId: envelope.traceId,
      requestId: null,
      requestNote: null,
    })
  } catch (error) {
    Object.assign(trialResult, {
      open: true,
      success: false,
      data: error instanceof ApiError ? error.data : null,
      message: errorText(error),
      code: error instanceof ApiError ? error.code : '',
      traceId: error instanceof ApiError ? error.traceId : null,
      requestId: error instanceof ApiError ? error.requestId : null,
      requestNote: error instanceof ApiError ? error.requestNote : null,
    })
  } finally {
    trial.running = false
  }
}

async function refreshTools() {
  refresh.running = true
  try {
    refresh.result = await api.refreshLocalMcp(props.conch, refresh.account || defaultAccount.value || undefined)
    refresh.open = true
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    refresh.running = false
  }
}
</script>

<template>
  <div v-if="!debugEnabled" class="hm-card" data-test="developer-hint">
    <div class="hm-card-title"><span>开发者模式</span></div>
    <p class="hm-muted hint">
      先到<router-link to="/">概览</router-link>打开开发者模式，才能试跑本地规则包<template v-if="isMcp">或刷新工具列表</template>。正式包没有这些操作。
    </p>
  </div>
  <div v-else class="hm-card" data-test="developer-panel">
    <div class="hm-card-title"><span>开发者模式</span><ShellPicker /></div>
    <p class="hm-muted hint">
      试跑不需要 API Key，也不上报 Tide。追踪写在调用详情里，关掉开发者模式会立刻删除。
    </p>

    <div class="block" data-test="trial">
      <h4>试跑</h4>
      <el-form label-position="top" class="form" @submit.prevent="run">
        <el-form-item label="能力">
          <el-select v-model="trial.action" filterable placeholder="选择能力" data-test="trial-action">
            <template v-if="groupedRunnable.hasGroups">
              <el-option
                v-for="cap in groupedRunnable.topLevel"
                :key="cap.action"
                :label="`${cap.action} · ${cap.title}`"
                :value="cap.action"
              />
              <el-option-group v-for="section in runnableOptionGroups" :key="section.label" :label="section.label">
                <el-option
                  v-for="cap in section.items"
                  :key="cap.action"
                  :label="`${cap.action} · ${cap.title}`"
                  :value="cap.action"
                />
              </el-option-group>
            </template>
            <template v-else>
              <el-option v-for="cap in runnable" :key="cap.action" :label="`${cap.action} · ${cap.title}`" :value="cap.action" />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item v-if="accounts.length" label="账号">
          <el-select v-model="trial.account" clearable :placeholder="defaultAccount ? `默认 ${defaultAccount}` : '选择账号'">
            <el-option v-for="account in accounts" :key="account.name" :label="account.name" :value="account.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="入参 JSON">
          <el-input v-model="trial.input" type="textarea" :rows="6" class="mono" data-test="trial-input" />
        </el-form-item>
        <el-button type="primary" :loading="trial.running" :disabled="!runnable.length" data-test="trial-run" @click="run">
          试跑
        </el-button>
      </el-form>
    </div>

    <div v-if="isMcp" class="block" data-test="refresh">
      <h4>刷新工具列表</h4>
      <p class="hm-muted hint">从上游 tools/list 重写这个本地规则包的工具快照，版本号不变。写回 YAML 会丢掉注释。</p>
      <div class="row">
        <el-select
          v-if="accounts.length"
          v-model="refresh.account"
          clearable
          :placeholder="defaultAccount ? `默认 ${defaultAccount}` : '选择账号'"
          class="account"
        >
          <el-option v-for="account in accounts" :key="account.name" :label="account.name" :value="account.name" />
        </el-select>
        <el-button type="primary" plain :loading="refresh.running" data-test="refresh-run" @click="refreshTools">
          刷新工具列表
        </el-button>
      </div>
    </div>
  </div>

  <el-dialog v-model="trialResult.open" title="试跑结果" width="560px" data-test="trial-result">
    <el-result v-if="trialResult.success" icon="success" title="试跑成功">
      <template v-if="trialResult.data" #extra>
        <JsonBlock :value="trialResult.data" class="data" />
      </template>
    </el-result>
    <el-result v-else icon="error" :title="errorCodeLabels[trialResult.code] ?? '试跑失败'" :sub-title="trialResult.message">
      <template #extra>
        <TrustJump :message="trialResult.message" />
        <JsonBlock v-if="trialResult.data" :value="trialResult.data" class="data" />
      </template>
    </el-result>
    <p v-if="trialResult.traceId" class="hm-muted trace">
      追踪编号：
      <router-link :to="{ path: '/calls', query: { trace: trialResult.traceId } }" class="mono">{{ trialResult.traceId }}</router-link>
    </p>
    <p v-else-if="trialResult.requestId" class="hm-muted trace" data-test="trial-request">
      请求编号 <span class="mono">{{ trialResult.requestId }}</span>
      <el-button link :icon="CopyDocument" aria-label="复制请求编号" @click="copyText(trialResult.requestId, '请求编号已复制')" />
      执行前就被拒绝了，没有追踪可查。
    </p>
  </el-dialog>

  <el-dialog v-model="refresh.open" title="工具列表已刷新" width="520px" data-test="refresh-result">
    <template v-if="refresh.result">
      <p>版本仍是 <span class="mono">v{{ refresh.result.version }}</span>。</p>
      <ul class="diff">
        <li v-for="name in refresh.result.added" :key="`+${name}`" class="add">+ {{ name }}</li>
        <li v-for="name in refresh.result.removed" :key="`-${name}`" class="rm">− {{ name }}</li>
        <li v-for="name in refresh.result.changed" :key="`~${name}`" class="ch">~ {{ name }}</li>
        <li v-if="!refresh.result.added.length && !refresh.result.removed.length && !refresh.result.changed.length">
          工具没有变化。
        </li>
      </ul>
      <p v-if="refresh.result.traceId" class="hm-muted">
        JSON-RPC 追踪 {{ refresh.result.traceId }} 已记下，刷新没有对应的调用记录。
      </p>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.block + .block {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--hm-border);
}

h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.form {
  max-width: 560px;
}

.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.account {
  width: 220px;
}

.data {
  text-align: left;
  max-width: 480px;
}

.trace {
  margin-top: 8px;
}

.diff {
  list-style: none;
  margin: 8px 0;
  padding: 0;
  font-family: var(--el-font-family);
}

.diff li {
  padding: 2px 0;
}

.add {
  color: var(--hm-success);
}

.rm {
  color: var(--hm-danger);
}

.ch {
  color: var(--hm-warning);
}
</style>
