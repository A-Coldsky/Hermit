<script setup lang="ts">
import { isAdmin } from '../auth'
import { CircleClose, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import { ApiError } from '@shared/http'
import { formatTime, notifyError } from '@shared/format'
import { api, type ConchDetail, type ConchVersion } from '../api'
import FieldTable from '../components/FieldTable.vue'
import CapabilityList from '../components/CapabilityList.vue'
import VersionPanel from '../components/VersionPanel.vue'
import ConchStats from '../components/ConchStats.vue'
import DriverBadge from '../components/DriverBadge.vue'
import McpToolTable from '../components/McpToolTable.vue'
import { isMcp, mcpAuthLabels, pullCountHint, transportLabels } from '../labels'

const props = defineProps<{ conch: string }>()
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detail = ref<ConchDetail | null>(null)
const versions = ref<ConchVersion[]>([])
const missing = ref(false)
const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'overview')

const credentialCount = computed(() => Object.keys(detail.value?.credentials ?? {}).length)
const configCount = computed(() => Object.keys(detail.value?.config ?? {}).length)
const capabilityCount = computed(() => Object.keys(detail.value?.actions ?? {}).length)
const mcp = computed(() => (detail.value ? isMcp(detail.value) : false))
const toolCount = computed(() => detail.value?.tools?.length ?? 0)
const authLabel = computed(() => {
  const type = detail.value?.upstream?.auth?.type
  return type ? (mcpAuthLabels[type] ?? type) : '未声明'
})
const neverPublished = computed(() => detail.value?.published === false)
const unpublished = computed(() => {
  if (neverPublished.value) {
    return []
  }
  const current = versions.value.find((v) => v.current)
  return versions.value.filter((v) => !v.current && v.status !== 'PUBLISHED' && (!current || v.uploadedAt > current.uploadedAt))
})

watch(tab, (value) => void router.replace({ query: { ...route.query, tab: value } }))

async function load() {
  loading.value = true
  missing.value = false
  try {
    versions.value = await api.versions(props.conch)
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      missing.value = true
    } else {
      notifyError(error)
    }
    loading.value = false
    return
  }
  try {
    detail.value = await api.conch(props.conch)
  } catch (error) {
    detail.value = null
    if (!(error instanceof ApiError && error.status === 404)) {
      notifyError(error)
    }
    if (tab.value !== 'stats') {
      tab.value = 'versions'
    }
  } finally {
    loading.value = false
  }
}

const availability = reactive({ open: false, reason: '', saving: false })

function openAvailability() {
  availability.reason = ''
  availability.open = true
}

async function saveAvailability() {
  const reason = availability.reason.trim()
  if (!reason) {
    ElMessage.warning('请填写原因，会记入审计日志')
    return
  }
  const disabling = !detail.value?.disabled
  availability.saving = true
  try {
    if (disabling) {
      await api.disableConch(props.conch, reason)
      ElMessage.success('已停用。各 Crab 下次同步时标记为「Reef 已停用」，已安装的版本仍可调用，但不会再更新')
    } else {
      await api.enableConch(props.conch, reason)
      ElMessage.success('已启用。各 Crab 下次同步时取消停用标记；有更高版本时由使用者手动更新')
    }
    availability.open = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    availability.saving = false
  }
}

watch(() => props.conch, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <PageHeader :title="detail?.title || conch" :description="detail?.description">
      <template #crumbs>
        <router-link to="/conches">规则包目录</router-link>
        <span class="hm-muted"> / {{ conch }}</span>
      </template>
      <template #icon>
        <ConchAvatar :name="conch" :title="detail?.title" :size="48" />
      </template>
      <template #badge>
        <el-tag v-if="neverPublished" type="info" effect="light" round data-test="unpublished-badge">未发布</el-tag>
        <el-tag v-else-if="detail" :type="detail.disabled ? 'info' : 'success'" effect="light" round class="mono">v{{ detail.version }}</el-tag>
        <el-tag v-if="detail?.disabled" type="warning" effect="light" round class="off">已停用</el-tag>
        <DriverBadge v-if="detail" :driver="detail.driver" size="default" class="off" />
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <template v-if="detail && isAdmin">
        <el-button v-if="detail.disabled" type="primary" plain :icon="VideoPlay" @click="openAvailability">启用</el-button>
        <el-button v-else type="danger" plain :icon="CircleClose" @click="openAvailability">停用</el-button>
      </template>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="规则包不存在" :sub-title="`没有找到名为 ${conch} 的规则包`">
      <template #extra>
        <el-button type="primary" @click="router.push('/conches')">返回目录</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-alert v-if="detail?.disabled" type="warning" :closable="false" show-icon class="notice" title="这个规则包已停用">
        <template #default>
          <div>各 Crab 同步后把它标记为「Reef 已停用」：已安装的版本仍可调用，但不会再下载或更新。启用后，Crab 下次同步时取消停用标记，有更高版本时由使用者手动更新。停用期间可以上传新版本，但不能发布。</div>
          <div class="reason">
            <template v-if="detail.disabledReason">原因：{{ detail.disabledReason }} · </template>
            {{ detail.disabledBy ?? '—' }} 于 {{ formatTime(detail.disabledAt) }} 停用
          </div>
        </template>
      </el-alert>

      <el-alert
        v-if="!loading && neverPublished"
        type="info"
        :closable="false"
        show-icon
        class="notice"
        data-test="unpublished-notice"
      >
        <template #title>
          这个规则包还未发布。下面是最高的已上传版本
          <span class="mono">v{{ detail?.version }}</span>，Crab 暂时拿不到它。
          <el-button v-if="isAdmin" link type="primary" class="jump" data-test="go-publish" @click="tab = 'versions'">去发布</el-button>
          <template v-else>等管理员发布。</template>
        </template>
      </el-alert>

      <el-alert
        v-if="!loading && !detail && versions.length"
        type="info"
        :closable="false"
        show-icon
        :title="isAdmin ? '这个规则包还没有发布版本，Crab 暂时拿不到它。在「版本」里发布一个已上传的版本。' : '这个规则包还没有发布版本，Crab 暂时拿不到它，等管理员发布。'"
        class="notice"
      />

      <el-alert
        v-if="!loading && detail && unpublished.length"
        type="warning"
        :closable="false"
        show-icon
        class="notice"
      >
        <template #title>
          有 {{ unpublished.length }} 个新上传的版本还没发布：
          <span class="mono">{{ unpublished.map((v) => `v${v.version}`).join('、') }}</span>
          <el-button v-if="isAdmin" link type="primary" class="jump" data-test="go-publish" @click="tab = 'versions'">去发布</el-button>
        </template>
      </el-alert>

      <el-alert v-if="mcp" type="info" :closable="false" show-icon class="notice" data-test="mcp-notice" title="这是源站提供的原生 MCP">
        <template #default>
          工具、参数和说明都来自源站的 MCP 服务，Hermit 只做权限管理和转发。下面的工具表是写进这个版本的快照，Reef 不会去连上游；源站新增或改了工具，要更新规则包里的工具列表，再上传新版本。
        </template>
      </el-alert>

      <el-tabs v-model="tab" class="tabs">
        <el-tab-pane label="概况" name="overview" :disabled="!detail">
          <div v-if="detail" class="hm-grid cols-2">
            <div class="hm-card">
              <div class="hm-card-title"><span>{{ neverPublished ? '尚未发布' : '当前发布版本' }}</span></div>
              <dl class="hm-kv">
                <dt>名称</dt>
                <dd class="mono">{{ detail.conch }}</dd>
                <dt>版本</dt>
                <dd class="mono">v{{ detail.version }}<span v-if="neverPublished" class="hm-muted"> · 最高已上传版本</span></dd>
                <dt>类型</dt>
                <dd><DriverBadge :driver="detail.driver" /></dd>
                <dt>最低 Crab 版本</dt>
                <dd class="mono">{{ detail.minCrabVersion }}</dd>
                <template v-if="!mcp">
                  <dt>老系统地址</dt>
                  <dd class="mono">{{ detail.baseUrl ?? '由 Crab 端配置' }}</dd>
                </template>
                <dt>负责人</dt>
                <dd>{{ detail.owner ?? '—' }}</dd>
                <dt>标签</dt>
                <dd>
                  <el-tag v-for="tag in detail.tags" :key="tag" size="small" class="tag">{{ tag }}</el-tag>
                  <span v-if="!detail.tags.length">—</span>
                </dd>
              </dl>
            </div>
            <div v-if="mcp" class="hm-card" data-test="mcp-upstream">
              <div class="hm-card-title"><span>上游 MCP</span></div>
              <dl class="hm-kv">
                <dt>上游地址</dt>
                <dd class="mono">{{ detail.upstream?.url ?? '—' }}</dd>
                <dt>连接方式</dt>
                <dd>{{ transportLabels[detail.upstream?.transport ?? ''] ?? detail.upstream?.transport ?? '—' }}</dd>
                <dt>认证方式</dt>
                <dd>{{ authLabel }}</dd>
                <dt>允许的主机</dt>
                <dd class="mono">{{ detail.upstream?.allowedHosts.length ? detail.upstream.allowedHosts.join('、') : '—' }}</dd>
                <dt>上游服务</dt>
                <dd>
                  <template v-if="detail.snapshot?.server?.name">
                    {{ detail.snapshot.server.name }}<span v-if="detail.snapshot.server.version" class="mono hm-muted"> v{{ detail.snapshot.server.version }}</span>
                  </template>
                  <template v-else>—</template>
                </dd>
                <dt>协议版本</dt>
                <dd class="mono">{{ detail.snapshot?.protocolVersion ?? '—' }}</dd>
                <dt>工具快照</dt>
                <dd>
                  <template v-if="detail.snapshot?.fetchedAt">
                    {{ formatTime(detail.snapshot.fetchedAt) }}<span class="hm-muted">（写进规则包的时间）</span>
                  </template>
                  <template v-else>包里没有记录</template>
                </dd>
                <dt>工具数</dt>
                <dd>{{ toolCount }}<span v-if="detail.snapshot?.toolsSha256" class="mono hm-muted" :title="detail.snapshot.toolsSha256"> · {{ detail.snapshot.toolsSha256.slice(0, 12) }}</span></dd>
              </dl>
            </div>
            <div class="hm-card">
              <div class="hm-card-title"><span>更新说明</span></div>
              <p v-if="detail.changelog" class="changelog">{{ detail.changelog }}</p>
              <p v-else class="hm-muted">这个版本没有写 changelog。</p>
              <div class="counts">
                <div><strong>{{ capabilityCount }}</strong><span class="hm-muted">{{ mcp ? '工具' : '能力' }}</span></div>
                <div><strong>{{ credentialCount }}</strong><span class="hm-muted">凭证字段</span></div>
                <div><strong>{{ configCount }}</strong><span class="hm-muted">配置项</span></div>
                <div :title="`${pullCountHint}，各版本合计`">
                  <strong>{{ detail.pullCount }}</strong><span class="hm-muted">拉取次数</span>
                </div>
              </div>
            </div>
          </div>
          <div v-if="detail" class="hm-card">
            <div class="hm-card-title">
              <span>凭证字段</span>
              <span class="hm-muted small">由每个 Crab 账号各自填写，只保存在 Crab 本地</span>
            </div>
            <FieldTable :fields="detail.credentials" empty="不需要凭证" />
          </div>
          <div v-if="detail" class="hm-card">
            <div class="hm-card-title">
              <span>配置项</span>
              <span class="hm-muted small">在 Crab 端按规则包填写，所有账号共用</span>
            </div>
            <FieldTable :fields="detail.config" empty="没有配置项" with-default />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="mcp ? `工具（${toolCount}）` : `能力（${capabilityCount}）`" name="capabilities" :disabled="!detail">
          <div v-if="detail && mcp" class="hm-card">
            <McpToolTable :tools="detail.tools ?? []" />
          </div>
          <CapabilityList v-else-if="detail" :actions="detail.actions" />
        </el-tab-pane>

        <el-tab-pane :label="`版本（${versions.length}）`" name="versions">
          <VersionPanel :conch="conch" :versions="versions" :loading="loading" :disabled="detail?.disabled" @changed="load" />
        </el-tab-pane>

        <el-tab-pane label="运行统计" name="stats" lazy>
          <ConchStats :conch="conch" :versions="versions" />
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="availability.open" :title="detail?.disabled ? `启用 ${conch}` : `停用 ${conch}`" width="480px">
      <p v-if="detail?.disabled" class="hm-muted">启用后，各 Crab 下次同步时取消停用标记。本机版本低于当前发布版本 v{{ detail.version }} 时，Crab 上显示「有新版本」，由使用者手动更新。</p>
      <p v-else class="hm-muted">
        停用后，各 Crab 下次同步时把它标记为「Reef 已停用」。已安装的版本仍可调用，但 Crab 不会再下载新版本；还没安装的 Crab 也装不上。启用后恢复正常更新。
      </p>
      <div class="required-label">原因 <span class="star">*</span><span class="hm-muted">必填，会记入审计日志</span></div>
      <el-input
        v-model="availability.reason"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="例如：上游系统维护到周五" aria-required="true"
      />
      <template #footer>
        <el-button @click="availability.open = false">取消</el-button>
        <el-button :type="detail?.disabled ? 'primary' : 'danger'" :loading="availability.saving" @click="saveAvailability">
          {{ detail?.disabled ? '启用' : '停用' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.reason {
  margin-top: 4px;
  color: var(--hm-text);
}

.required-label {
  display: flex;
  gap: 6px;
  align-items: baseline;
  margin: 12px 0 6px;
  font-weight: 500;
}

.required-label .star {
  color: var(--hm-danger);
}

.required-label .hm-muted {
  font-size: 12px;
  font-weight: 400;
}

.off {
  margin-left: 6px;
}

.notice {
  margin-bottom: 12px;
}

.tag {
  margin-right: 4px;
}

.jump {
  margin-left: 8px;
  vertical-align: baseline;
}

.changelog {
  white-space: pre-wrap;
  line-height: 1.7;
  margin: 0;
}

.counts {
  display: flex;
  gap: 32px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--hm-border);
}

.counts div {
  display: flex;
  flex-direction: column;
}

.counts strong {
  font-size: 24px;
}

.small {
  font-size: 12px;
  font-weight: 400;
}

.hm-grid + .hm-card {
  margin-top: 16px;
}
</style>
