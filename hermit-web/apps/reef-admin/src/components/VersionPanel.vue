<script setup lang="ts">
import { isAdmin } from '../auth'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { copyText, formatTime, notifyError } from '@shared/format'
import { api, type ConchDiff, type ConchVersion } from '../api'
import { versionStatusLabels } from '../labels'
import DiffSummary from './DiffSummary.vue'
import IssueList from './IssueList.vue'

const props = defineProps<{ conch: string; versions: ConchVersion[]; loading?: boolean; disabled?: boolean }>()
const emit = defineEmits<{ changed: [] }>()

const busy = ref<string | null>(null)
const current = computed(() => props.versions.find((v) => v.current) ?? null)

const yamlDialog = reactive({ open: false, version: '', loading: false, text: '' })
const warningDialog = reactive({ open: false, row: null as ConchVersion | null })
const diffDialog = reactive({
  open: false,
  from: '',
  to: '',
  loading: false,
  result: null as ConchDiff | null,
})

async function confirm(message: string, title: string, button: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { confirmButtonText: button, cancelButtonText: '取消', type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function newCredentials(row: ConchVersion): Promise<string> {
  if (!current.value) return ''
  try {
    const diff = await api.diff(props.conch, current.value.version, row.version)
    const added = diff.newRequiredCredentials ?? []
    return added.length ? `这个版本新增必填凭证：${added.join('、')}。Crab 使用者要先填好这些凭证才能更新。` : ''
  } catch {
    return '没能读取与当前版本的差异，发布前请先点「对比」确认是否新增了必填凭证。'
  }
}

async function publish(row: ConchVersion) {
  busy.value = row.version
  const credentials = await newCredentials(row)
  busy.value = null
  const message = current.value
    ? `当前发布版本是 v${current.value.version}。发布后，已订阅的 Crab 需要手动更新才会安装 v${row.version}。${credentials}`
    : `发布后，Crab 第一次订阅时会安装 v${row.version}。之后的更高版本需要手动更新。`
  if (!(await confirm(message, `发布 v${row.version}`, '发布'))) {
    return
  }
  busy.value = row.version
  try {
    await api.publish(props.conch, row.version)
    ElMessage.success(`v${row.version} 已发布`)
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = null
  }
}

function showWarnings(row: ConchVersion) {
  warningDialog.row = row
  warningDialog.open = true
}

async function showYaml(row: ConchVersion) {
  Object.assign(yamlDialog, { open: true, version: row.version, loading: true, text: '' })
  try {
    yamlDialog.text = await api.yaml(props.conch, row.version)
  } catch (error) {
    notifyError(error)
  } finally {
    yamlDialog.loading = false
  }
}

function openDiff(row: ConchVersion) {
  const index = props.versions.indexOf(row)
  const base = current.value && current.value.version !== row.version ? current.value : props.versions[index + 1]
  diffDialog.from = base?.version ?? row.version
  diffDialog.to = row.version
  diffDialog.result = null
  diffDialog.open = true
  void runDiff()
}

async function runDiff() {
  if (!diffDialog.from || !diffDialog.to) {
    return
  }
  diffDialog.loading = true
  try {
    diffDialog.result = await api.diff(props.conch, diffDialog.from, diffDialog.to)
  } catch (error) {
    diffDialog.result = null
    notifyError(error)
  } finally {
    diffDialog.loading = false
  }
}
</script>

<template>
  <div class="hm-card">
    <p class="hm-scroll-hint">表格可以左右滑动查看上传和发布记录。</p>
    <el-table :data="versions">
      <template #empty>{{ loading ? '' : '还没有版本' }}</template>
      <el-table-column label="版本" width="150">
        <template #default="{ row }">
          <span class="mono strong">v{{ row.version }}</span>
          <el-tag v-if="row.current" type="success" size="small" class="current">当前</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain" size="small">
            {{ versionStatusLabels[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="警告" width="80" align="right">
        <template #default="{ row }">
          <el-button
            v-if="row.warningCount"
            link
            class="warn"
            :aria-label="`查看 v${row.version} 的 ${row.warningCount} 条警告`"
            @click="showWarnings(row)"
          >
            {{ row.warningCount }}
          </el-button>
          <span v-else class="hm-muted">0</span>
        </template>
      </el-table-column>
      <el-table-column prop="pullCount" label="拉取" width="80" align="right" />
      <el-table-column label="操作" :width="isAdmin ? 280 : 160">
        <template #default="{ row }">
          <el-button link type="primary" @click="showYaml(row)">YAML</el-button>
          <el-button link type="primary" :disabled="versions.length < 2" @click="openDiff(row)">对比</el-button>
          <el-tooltip v-if="disabled && row.status === 'UPLOADED'" content="规则包已停用，先启用再发布">
            <span class="off">发布</span>
          </el-tooltip>
          <el-button v-else-if="row.status === 'UPLOADED' && isAdmin" link type="primary" :loading="busy === row.version" @click="publish(row)">
            发布
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="上传" min-width="180">
        <template #default="{ row }">
          <div>{{ row.uploadedBy }}</div>
          <div class="hm-muted small">{{ formatTime(row.uploadedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="发布" min-width="180">
        <template #default="{ row }">
          <template v-if="row.publishedAt">
            <div>{{ row.publishedBy }}</div>
            <div class="hm-muted small">{{ formatTime(row.publishedAt) }}</div>
          </template>
          <span v-else class="hm-muted">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <el-dialog v-model="yamlDialog.open" :title="`${conch} v${yamlDialog.version}`" width="860px" top="6vh">
    <div v-loading="yamlDialog.loading">
      <pre class="hm-pre yaml">{{ yamlDialog.text }}</pre>
    </div>
    <template #footer>
      <el-button :icon="CopyDocument" :disabled="!yamlDialog.text" @click="copyText(yamlDialog.text, 'YAML 已复制')">复制</el-button>
      <el-button type="primary" @click="yamlDialog.open = false">关闭</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="warningDialog.open" :title="`v${warningDialog.row?.version ?? ''} 的校验警告`" width="640px">
    <p class="hm-muted hint">警告不影响发布，但说明这个版本的描述还不完整。</p>
    <IssueList v-if="warningDialog.row" :issues="warningDialog.row.warnings" tone="warning" />
  </el-dialog>

  <el-dialog v-model="diffDialog.open" title="版本对比" width="720px">
    <div class="hm-toolbar">
      <el-select v-model="diffDialog.from" placeholder="旧版本" class="pick" @change="runDiff">
        <el-option v-for="v in versions" :key="v.version" :label="`v${v.version}`" :value="v.version" />
      </el-select>
      <span class="hm-muted">→</span>
      <el-select v-model="diffDialog.to" placeholder="新版本" class="pick" @change="runDiff">
        <el-option v-for="v in versions" :key="v.version" :label="`v${v.version}`" :value="v.version" />
      </el-select>
    </div>
    <div v-loading="diffDialog.loading" class="diff-body">
      <DiffSummary v-if="diffDialog.result" :diff="diffDialog.result" />
    </div>
  </el-dialog>
</template>

<style scoped>
.strong {
  font-weight: 600;
}

.current {
  margin-left: 6px;
}

.small {
  font-size: 12px;
}

.warn {
  color: var(--hm-warning);
  font-weight: 600;
}

.hint {
  margin: 0 0 12px;
}

.yaml {
  max-height: 68vh;
  overflow: auto;
}

.pick {
  width: 160px;
}

.diff-body {
  min-height: 80px;
  margin-top: 8px;
}

.off {
  margin-left: 12px;
  font-size: 14px;
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
</style>
