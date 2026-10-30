<script setup lang="ts">
import { ArrowRight, CircleCheckFilled, FolderOpened, Refresh, Upload, User, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type ConchSummary, type LocalFileResult, type ValidationIssue } from '../api'
import { refreshPending } from '../status'

const router = useRouter()
const loading = ref(true)
const conches = ref<ConchSummary[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const reloading = ref(false)

const report = reactive({
  open: false,
  title: '',
  summary: '',
  errors: [] as ValidationIssue[],
  warnings: [] as ValidationIssue[],
  files: [] as LocalFileResult[],
})

function showReport(next: Partial<Omit<typeof report, 'open'>>) {
  Object.assign(report, { title: '', summary: '', errors: [], warnings: [], files: [] }, next, { open: true })
}

function issuesOf(data: unknown): { errors: ValidationIssue[]; warnings: ValidationIssue[] } | null {
  if (!data || typeof data !== 'object') return null
  const { errors, warnings } = data as { errors?: ValidationIssue[]; warnings?: ValidationIssue[] }
  if (!Array.isArray(errors) && !Array.isArray(warnings)) return null
  return { errors: errors ?? [], warnings: warnings ?? [] }
}

function pickFile() {
  fileInput.value?.click()
}

async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const result = await api.importLocalConch(file)
    const verb = result.replaced ? '已覆盖' : '已导入'
    if (result.warnings.length) {
      showReport({
        title: `${verb}本地规则包 ${result.conch}`,
        summary: `v${result.version} 已可调用，有 ${result.warnings.length} 条警告。`,
        warnings: result.warnings,
      })
    } else {
      ElMessage.success(`${verb}本地规则包 ${result.conch} v${result.version}`)
    }
    await load()
  } catch (error) {
    const issues = error instanceof ApiError ? issuesOf(error.data) : null
    if (issues) {
      showReport({ title: '导入失败', summary: error instanceof Error ? error.message : '', ...issues })
    } else {
      notifyError(error)
    }
  } finally {
    importing.value = false
  }
}

async function reloadLocal() {
  reloading.value = true
  try {
    const { results } = await api.reloadLocalConches()
    const failed = results.filter((r) => !r.ok).length
    if (failed) {
      showReport({ title: '重新加载本地规则包', summary: `${results.length} 个文件，${failed} 个没有加载。`, files: results })
    } else {
      ElMessage.success(results.length ? `已重新加载 ${results.length} 个本地规则包` : '本地规则包目录是空的')
    }
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    reloading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    conches.value = (await api.conches()).conches
    void refreshPending()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function open(row: ConchSummary) {
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="规则包与账号" description="已安装到本机的规则包。为每个规则包建账号、填凭证和配置后即可调用">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="FolderOpened" :loading="reloading" @click="reloadLocal">重新加载本地目录</el-button>
      <el-button type="primary" :icon="Upload" :loading="importing" @click="pickFile">导入本地规则包</el-button>
      <input ref="fileInput" type="file" accept=".yaml,.yml" class="file" data-test="local-file" @change="importFile" />
    </PageHeader>

    <div v-if="loading && !conches.length" class="cards">
      <div v-for="n in 2" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
    </div>
    <div v-else-if="conches.length" class="cards">
      <div
        v-for="row in conches"
        :key="row.conch"
        class="hm-card conch clickable"
        :class="{ warn: row.needsConfiguration }"
        role="link"
        tabindex="0"
        @click="open(row)"
        @keyup.enter="open(row)"
      >
        <div class="top">
          <ConchAvatar :name="row.conch" :title="row.title" />
          <div class="name">
            <strong>
              {{ row.title || row.conch }}
              <el-tag v-if="row.source === 'LOCAL'" type="warning" effect="plain" size="small" class="local">本地</el-tag>
              <el-tag v-if="row.reefDisabled" type="danger" effect="plain" size="small" class="local reef-off">已失效</el-tag>
            </strong>
            <span class="mono hm-muted">{{ row.conch }} · v{{ row.version }}</span>
          </div>
        </div>
        <div class="status" :class="row.needsConfiguration ? 'warn' : row.accountCount ? 'ok' : 'idle'">
          <el-icon><component :is="row.needsConfiguration ? WarningFilled : row.accountCount ? CircleCheckFilled : User" /></el-icon>
          <span v-if="row.loginPaused">已暂停：有账号自动登录已暂停，进入详情查看恢复方法</span>
          <span v-else-if="row.needsConfiguration">待配置：还缺凭证或配置项</span>
          <span v-else-if="!row.accountCount">未建账号：先新建一个账号</span>
          <span v-else>可用：可以通过 OpenAPI 和 MCP 调用</span>
        </div>
        <p v-if="row.reefDisabled" class="reef-off-note">Reef 已停用：本机版本仍可调用，但不会再更新</p>
        <div class="foot hm-muted">
          <span><b class="num">{{ row.capabilityCount }}</b> 个能力</span>
          <span><b class="num">{{ row.accountCount }}</b> 个账号</span>
          <span class="spacer" />
          <span class="manage">管理<el-icon><ArrowRight /></el-icon></span>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="hm-card empty">
      还没有规则包。到<router-link to="/catalog">可订阅规则包</router-link>订阅 Reef 上的规则包，或导入本地规则包
    </div>

    <el-dialog v-model="report.open" :title="report.title" width="600px">
      <p v-if="report.summary" class="summary">{{ report.summary }}</p>
      <ul v-if="report.errors.length" class="issues bad">
        <li v-for="(issue, i) in report.errors" :key="`e${i}`">
          <span class="mono">{{ issue.path || '/' }}</span> {{ issue.message }}
        </li>
      </ul>
      <ul v-if="report.warnings.length" class="issues warn">
        <li v-for="(issue, i) in report.warnings" :key="`w${i}`">
          <span class="mono">{{ issue.path || '/' }}</span> {{ issue.message }}
        </li>
      </ul>
      <el-table v-if="report.files.length" :data="report.files" size="small">
        <el-table-column label="文件" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.file }}</span></template>
        </el-table-column>
        <el-table-column label="结果" min-width="260">
          <template #default="{ row }">
            <span v-if="row.ok" class="ok">已加载 {{ row.conch }} v{{ row.version }}</span>
            <span v-else class="err">{{ row.issues.map((i: ValidationIssue) => i.message).join('；') || '没有加载' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="report.open = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.conch {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.name strong {
  font-size: 15px;
}

.name .mono {
  font-size: 12px;
}

.status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13px;
}

.status.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
}

.status.warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}

.status.idle {
  background: var(--hm-subtle);
  color: var(--hm-muted);
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12.5px;
}

.foot b {
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.manage {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--el-color-primary);
  font-weight: 500;
}

.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--hm-muted);
}

.file {
  display: none;
}

.local {
  margin-left: 6px;
  vertical-align: 2px;
}

.reef-off-note {
  margin: -4px 0 0;
  font-size: 12.5px;
  color: var(--hm-danger);
}

.summary {
  margin: 0 0 12px;
}

.issues {
  margin: 0 0 12px;
  padding-left: 18px;
  line-height: 1.7;
}

.issues.bad,
.err {
  color: var(--hm-danger);
}

.issues.warn {
  color: var(--el-color-warning-dark-2);
}

.ok {
  color: var(--el-color-success-dark-2);
}
</style>
