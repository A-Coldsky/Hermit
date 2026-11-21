<script setup lang="ts">
import { useNarrow } from '@shared/narrow'
import { CopyDocument, Download, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { copyText, notifyError } from '@shared/format'
import { api, type AgentToken, type CrabInstance, type IssuedAgentToken } from '../api'
import { isAdmin } from '../auth'
import { bootstrapFileName, buildBootstrap, downloadJson, normalizeReefUrl } from '../bootstrap'

/** 窄屏只留辨认令牌要用的列，吊销按钮不再固定盖住前缀。 */
const narrow = useNarrow()
const loading = ref(true)
const showAll = ref(false)
const tokens = ref<AgentToken[]>([])
const crabs = ref<CrabInstance[]>([])
const revoking = ref<number | null>(null)
const voidingConfig = ref(false)

const create = reactive({ open: false, name: '', saving: false })
const issued = reactive({
  open: false,
  result: null as IssuedAgentToken | null,
  url: window.location.origin,
  downloaded: false,
})

const urlValid = computed(() => normalizeReefUrl(issued.url) !== null)
const bootstrap = computed(() => {
  const url = normalizeReefUrl(issued.url)
  return issued.result && url ? buildBootstrap(issued.result, url) : null
})

async function load() {
  loading.value = true
  try {
    const [t, c] = await Promise.all([api.tokens(isAdmin.value && showAll.value), api.crabs()])
    tokens.value = t
    crabs.value = c
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

watch(showAll, load)

function openCreate() {
  create.name = ''
  create.open = true
}

async function submitCreate() {
  const name = create.name.trim()
  if (!name) {
    ElMessage.warning('请给令牌起个名字，比如这台 Crab 所在的电脑')
    return
  }
  create.saving = true
  try {
    issued.result = await api.createToken(name)
    issued.url = window.location.origin
    issued.downloaded = false
    create.open = false
    issued.open = true
    void load()
  } catch (error) {
    notifyError(error)
  } finally {
    create.saving = false
  }
}

function download() {
  if (!bootstrap.value) return
  downloadJson(bootstrapFileName(bootstrap.value), bootstrap.value)
  issued.downloaded = true
}

function copySecret() {
  if (issued.result) void copyText(issued.result.secret, '令牌已复制')
}

async function copyJson() {
  if (!bootstrap.value) return
  await copyText(JSON.stringify(bootstrap.value, null, 2), '初始化 JSON 已复制')
  issued.downloaded = true
}

async function closeIssued(done: () => void) {
  if (!issued.downloaded) {
    try {
      await ElMessageBox.confirm('还没有下载或复制初始化 JSON。关闭后令牌不能再查看，只能吊销后重新生成。', '确定关闭？', {
        confirmButtonText: '仍然关闭',
        cancelButtonText: '返回',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  issued.result = null
  done()
}

async function revoke(token: AgentToken) {
  if (token.id === null) return
  try {
    await ElMessageBox.confirm(
      `吊销后，使用「${token.name}」的 Crab 会立即无法同步和上报，且无法恢复。需要时可以重新生成令牌再导入。`,
      `吊销 ${token.name}`,
      { confirmButtonText: '吊销', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  revoking.value = token.id
  try {
    await api.revokeToken(token.id)
    ElMessage.success(`已吊销 ${token.name}`)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    revoking.value = null
  }
}

async function voidConfig(token: AgentToken) {
  try {
    await ElMessageBox.confirm(
      `作废后，使用 ${token.name} 的 Crab 会立即无法同步和上报。要恢复，只能在 Reef 配置里把 HERMIT_REEF_AGENT_TOKEN 换成一串新值并重启 Reef，再把新值导入 Crab。`,
      '作废配置令牌',
      { confirmButtonText: '作废', cancelButtonText: '保留', type: 'warning', confirmButtonType: 'danger' },
    )
  } catch {
    return
  }
  voidingConfig.value = true
  try {
    await api.voidConfigToken()
    ElMessage.success('配置令牌已作废')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    voidingConfig.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="我的令牌" description="每台 Crab 用一把 Agent 令牌连接 Reef。生成后下载初始化 JSON，在 Crab 控制台「连接 Reef」里导入">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" data-test="create-token" @click="openCreate">生成令牌</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-card-title">
        <span>Agent 令牌</span>
        <el-switch v-if="isAdmin" v-model="showAll" active-text="显示所有用户" data-test="show-all" />
      </div>
      <p class="hm-scroll-hint">窄屏只显示名称、前缀、状态和操作；创建和最近使用时间在宽屏上查看。</p>
      <el-table v-loading="loading" :data="tokens">
        <template #empty>{{ loading ? '' : '还没有令牌。点「生成令牌」为一台 Crab 生成' }}</template>
        <el-table-column label="名称" :min-width="narrow ? 120 : 180">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <el-tag v-if="row.source === 'CONFIG'" size="small" type="info" effect="plain" class="tag">配置令牌</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="showAll" prop="owner" label="所属用户" min-width="120" />
        <el-table-column label="前缀" :min-width="narrow ? 110 : 140">
          <template #default="{ row }"><span class="mono">{{ row.prefix ? `${row.prefix}…` : '—' }}</span></template>
        </el-table-column>
        <el-table-column v-if="!narrow" label="创建" min-width="170">
          <template #default="{ row }"><TimeText v-if="row.createdAt" :value="row.createdAt" /><span v-else>—</span></template>
        </el-table-column>
        <el-table-column v-if="!narrow" label="最近使用" min-width="170">
          <template #default="{ row }"><TimeText v-if="row.lastUsedAt" :value="row.lastUsedAt" /><span v-else class="hm-muted">从未使用</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.revokedAt" type="info" size="small" effect="plain">{{ row.source === 'CONFIG' ? '已作废' : '已吊销' }}</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">有效</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="narrow ? 72 : 100" align="right" :fixed="narrow ? false : 'right'">
          <template #default="{ row }">
            <el-button
              v-if="row.source === 'CONFIG' && !row.revokedAt && isAdmin"
              link
              type="danger"
              :loading="voidingConfig"
              data-test="void-config"
              @click="voidConfig(row)"
            >
              作废
            </el-button>
            <el-button
              v-else-if="row.source !== 'CONFIG' && !row.revokedAt"
              link
              type="danger"
              :loading="revoking === row.id"
              @click="revoke(row)"
            >
              吊销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="hm-card">
      <div class="hm-card-title"><span>{{ isAdmin ? '已连接的 Crab' : '我的 Crab' }}</span></div>
      <p class="hm-scroll-hint">表格可以左右滑动。</p>
      <el-table v-loading="loading" :data="crabs">
        <template #empty>{{ loading ? '' : '还没有 Crab 用令牌连接过 Reef' }}</template>
        <el-table-column label="Crab" min-width="280">
          <template #default="{ row }">
            <span>{{ row.owner }}</span><span class="hm-muted"> · </span><span class="mono">{{ row.instanceId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="令牌" min-width="140">
          <template #default="{ row }">{{ row.tokenName ?? '配置令牌' }}</template>
        </el-table-column>
        <el-table-column label="Crab 版本" width="110">
          <template #default="{ row }"><span class="mono">{{ row.crabVersion ?? '—' }}</span></template>
        </el-table-column>
        <el-table-column label="首次连接" min-width="170">
          <template #default="{ row }"><TimeText :value="row.firstSeenAt" /></template>
        </el-table-column>
        <el-table-column label="最近连接" min-width="170">
          <template #default="{ row }"><TimeText :value="row.lastSeenAt" /></template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="create.open" title="生成令牌" width="440px">
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="名称">
          <el-input v-model="create.name" maxlength="64" show-word-limit placeholder="例如：财务部办公室电脑" data-test="token-name" />
        </el-form-item>
      </el-form>
      <p class="hm-muted hint">令牌只在生成后显示一次。Reef 只保存它的摘要，忘了只能吊销重来。</p>
      <template #footer>
        <el-button @click="create.open = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" data-test="submit-token" @click="submitCreate">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="issued.open"
      title="令牌已生成"
      width="620px"
      :close-on-click-modal="false"
      :before-close="closeIssued"
    >
      <template v-if="issued.result">
        <el-alert type="warning" show-icon :closable="false" title="这是唯一一次能看到这把令牌">
          <template #default>下载或复制下面的初始化 JSON，在 Crab 控制台「连接 Reef」里整份导入。关闭后无法再次查看。</template>
        </el-alert>
        <el-form label-position="top" class="issued">
          <el-form-item label="Crab 访问 Reef 用的地址" :error="urlValid ? '' : '需要 http:// 或 https:// 开头的地址'">
            <el-input v-model="issued.url" data-test="reef-url" />
            <div class="hm-muted hint">默认是你现在打开管理页的地址。Crab 经由别的域名或端口访问 Reef 时改这里。</div>
          </el-form-item>
          <el-form-item label="令牌">
            <div class="secret">
              <code class="mono" data-test="secret">{{ issued.result?.secret }}</code>
              <el-button link :icon="CopyDocument" aria-label="复制令牌" @click="copySecret" />
            </div>
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button :icon="CopyDocument" :disabled="!urlValid" @click="copyJson">复制 JSON</el-button>
        <el-button type="primary" :icon="Download" :disabled="!urlValid" data-test="download-json" @click="download">下载初始化 JSON</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hm-card + .hm-card {
  margin-top: 16px;
}

.tag {
  margin-left: 6px;
}

.hint {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
}

.issued {
  margin-top: 16px;
}

.secret {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.secret code {
  flex: 1;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  word-break: break-all;
}
</style>
