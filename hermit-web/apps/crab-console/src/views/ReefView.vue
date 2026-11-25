<script setup lang="ts">
import { CircleCheckFilled, CircleCloseFilled, FolderOpened, Refresh, Upload, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import HermitDuet from '@shared/components/HermitDuet.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type ReefConnection } from '../api'
import { reefImportErrorText } from '../labels'
import { refreshPending } from '../status'

const loading = ref(true)
const importing = ref(false)
const connection = ref<ReefConnection | null>(null)
/** 最近一次同步被 Reef 拒绝令牌时的 message。引导文件还在，但这条连接已经不能用。 */
const tokenRejected = ref<string | null>(null)
const text = ref('')
const fileInput = ref<HTMLInputElement>()

const duetStatus = computed<'connected' | 'disconnected' | 'error'>(() => {
  if (tokenRejected.value) return 'error'
  return connection.value?.configured ? 'connected' : 'disconnected'
})

const parsed = computed<{ value: unknown; error: string | null }>(() => {
  const raw = text.value.trim()
  if (!raw) return { value: null, error: null }
  try {
    const value = JSON.parse(raw)
    if (!value || typeof value !== 'object' || value.kind !== 'hermit-crab-bootstrap') {
      return { value: null, error: '这不是 Reef「我的令牌」生成的初始化 JSON' }
    }
    return { value, error: null }
  } catch {
    return { value: null, error: '不是有效的 JSON，请整份粘贴，不要删改' }
  }
})

const preview = computed(() => {
  const value = parsed.value.value as { reef?: { url?: string }; owner?: string; tokenName?: string } | null
  return value ? { url: value.reef?.url ?? '—', owner: value.owner ?? '—', tokenName: value.tokenName ?? '—' } : null
})

async function load() {
  loading.value = true
  try {
    const [reef, status] = await Promise.all([api.reef(), api.syncStatus().catch(() => null)])
    connection.value = reef
    const error = status?.report?.error
    tokenRejected.value = reef.configured && error?.code === 'AUTH_FAILED' ? error.message : null
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 64 * 1024) {
    ElMessage.error('文件太大，初始化 JSON 不会超过 64 KB')
    return
  }
  text.value = await file.text()
}

async function submit() {
  if (!parsed.value.value) return
  const current = connection.value
  if (current?.configured) {
    const from = current.source === 'CONFIG' ? 'config.yml 里的连接' : `当前连接（${current.url ?? '—'}）`
    try {
      await ElMessageBox.confirm(`导入后将替换${from}，立即生效，不用重启 Crab。`, '替换 Reef 连接', {
        confirmButtonText: '导入',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  importing.value = true
  try {
    connection.value = await api.importReefBootstrap(parsed.value.value)
    tokenRejected.value = null
    text.value = ''
    ElMessage.success('已连接 Reef，正在同步规则包目录')
    void refreshPending()
  } catch (error) {
    const friendly = error instanceof ApiError ? reefImportErrorText(error.message) : null
    if (friendly) ElMessage.error({ message: friendly, duration: 8000 })
    else notifyError(error)
  } finally {
    importing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <PageHeader title="连接 Reef" description="在 Reef 管理页「我的令牌」生成令牌并下载初始化 JSON，然后在这里整份导入">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-card-title"><span>当前连接</span></div>
      <div v-if="connection" class="conn-layout">
        <div class="conn-body">
          <template v-if="connection.configured">
            <div v-if="tokenRejected" class="state bad" data-test="token-rejected">
              <el-icon :size="22"><CircleCloseFilled /></el-icon>
              <div>
                <strong>{{ tokenRejected }}</strong>
                <div class="hm-muted">
                  最近一次同步被 Reef 拒绝，令牌可能已被吊销、作废或所属用户已被禁用。请在 Reef「我的令牌」重新生成令牌，在下面重新导入初始化 JSON。
                </div>
              </div>
            </div>
            <div v-else class="state ok">
              <el-icon :size="22"><CircleCheckFilled /></el-icon>
              <strong>已连接 Reef</strong>
              <el-tag size="small" effect="plain" type="info">{{ connection.source === 'BOOTSTRAP' ? '初始化 JSON 导入' : 'config.yml 配置' }}</el-tag>
            </div>
            <dl class="hm-kv" data-test="connection">
              <dt>Reef 地址</dt>
              <dd class="mono">{{ connection.url ?? '—' }}</dd>
              <template v-if="connection.source === 'BOOTSTRAP'">
                <dt>令牌所属用户</dt>
                <dd>{{ connection.owner ?? '—' }}</dd>
                <dt>令牌名称</dt>
                <dd>{{ connection.tokenName ?? '—' }}</dd>
              </template>
              <dt>令牌前缀</dt>
              <dd class="mono">{{ connection.tokenPrefix ? `${connection.tokenPrefix}…` : '—' }}</dd>
              <dt>签名公钥指纹</dt>
              <dd class="mono fp">{{ connection.publicKeyFingerprint ?? '—' }}</dd>
              <dt>本机实例号</dt>
              <dd class="mono">{{ connection.instanceId }}</dd>
              <template v-if="connection.importedAt">
                <dt>导入时间</dt>
                <dd><TimeText :value="connection.importedAt" /></dd>
              </template>
            </dl>
            <p class="hm-muted hint">Reef 用「令牌所属用户 + 本机实例号」识别这台 Crab。</p>
          </template>
          <div v-else class="state warn">
            <el-icon :size="22"><WarningFilled /></el-icon>
            <div>
              <strong>还没有连接 Reef</strong>
              <div class="hm-muted">本地规则包照常可用；导入初始化 JSON 后才能订阅 Reef 上的规则包和上报。本机实例号 <span class="mono">{{ connection.instanceId }}</span></div>
            </div>
          </div>
        </div>
        <div class="conn-art">
          <HermitDuet variant="scene" :size="92" :status="duetStatus" intro />
        </div>
      </div>
    </div>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>导入初始化 JSON</span>
        <el-button :icon="FolderOpened" @click="pickFile">选择文件</el-button>
      </div>
      <input ref="fileInput" type="file" accept=".json,application/json" class="file" data-test="bootstrap-file" @change="onFile" />
      <el-input
        v-model="text"
        type="textarea"
        :rows="8"
        placeholder="选择下载的 JSON 文件，或把内容整份粘贴到这里"
        class="mono"
        data-test="bootstrap-text"
      />
      <p v-if="parsed.error" class="bad" data-test="bootstrap-error">{{ parsed.error }}</p>
      <dl v-else-if="preview" class="hm-kv preview" data-test="bootstrap-preview">
        <dt>Reef 地址</dt>
        <dd class="mono">{{ preview.url }}</dd>
        <dt>令牌所属用户</dt>
        <dd>{{ preview.owner }}</dd>
        <dt>令牌名称</dt>
        <dd>{{ preview.tokenName }}</dd>
      </dl>
      <div class="actions">
        <span class="hm-muted">导入时 Crab 会先用这把令牌连一次 Reef，验证通过才保存。</span>
        <el-button type="primary" :icon="Upload" :disabled="!parsed.value" :loading="importing" data-test="bootstrap-submit" @click="submit">
          导入并连接
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hm-card + .hm-card {
  margin-top: 16px;
}

.conn-layout {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.conn-body {
  flex: 1 1 360px;
  min-width: 0;
}

.conn-art {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 8px;
}

.state {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.state.ok .el-icon {
  color: var(--el-color-success);
}

.state.warn .el-icon {
  color: var(--el-color-warning);
}

.state.bad .el-icon {
  color: var(--el-color-danger);
}

.fp {
  word-break: break-all;
}

.hint {
  margin: 8px 0 0;
  font-size: 12.5px;
}

.file {
  display: none;
}

.bad {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}

.preview {
  margin-top: 12px;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}
</style>
