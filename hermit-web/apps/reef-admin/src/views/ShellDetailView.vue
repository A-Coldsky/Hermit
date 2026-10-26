<script setup lang="ts">
import { CircleClose, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ShellAvatar from '@shared/components/ShellAvatar.vue'
import { ApiError } from '@shared/http'
import { formatTime, notifyError } from '@shared/format'
import { api, type ShellDetail, type ShellVersion } from '../api'
import FieldTable from '../components/FieldTable.vue'
import CapabilityList from '../components/CapabilityList.vue'
import VersionPanel from '../components/VersionPanel.vue'
import ShellStats from '../components/ShellStats.vue'

const props = defineProps<{ shell: string }>()
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detail = ref<ShellDetail | null>(null)
const versions = ref<ShellVersion[]>([])
const missing = ref(false)
const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'overview')

const credentialCount = computed(() => Object.keys(detail.value?.credentials ?? {}).length)
const configCount = computed(() => Object.keys(detail.value?.config ?? {}).length)
const capabilityCount = computed(() => Object.keys(detail.value?.actions ?? {}).length)
const unpublished = computed(() => {
  const current = versions.value.find((v) => v.current)
  return versions.value.filter((v) => !v.current && v.status !== 'PUBLISHED' && (!current || v.uploadedAt > current.uploadedAt))
})

watch(tab, (value) => void router.replace({ query: { ...route.query, tab: value } }))

async function load() {
  loading.value = true
  missing.value = false
  try {
    versions.value = await api.versions(props.shell)
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
    detail.value = await api.shell(props.shell)
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
      await api.disableShell(props.shell, reason)
      ElMessage.success('已停用，各 Crab 下次同步时卸下这个 Shell')
    } else {
      await api.enableShell(props.shell, reason)
      ElMessage.success('已启用，各 Crab 下次同步时重新安装当前版本')
    }
    availability.open = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    availability.saving = false
  }
}

watch(() => props.shell, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <PageHeader :title="detail?.title || shell" :description="detail?.description">
      <template #crumbs>
        <router-link to="/shells">Shell 目录</router-link>
        <span class="hm-muted"> / {{ shell }}</span>
      </template>
      <template #icon>
        <ShellAvatar :name="shell" :title="detail?.title" :size="48" />
      </template>
      <template #badge>
        <el-tag v-if="detail" type="success" effect="light" round class="mono">v{{ detail.version }}</el-tag>
        <el-tag v-if="detail?.disabled" type="danger" effect="light" round class="off">已停用</el-tag>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <template v-if="detail">
        <el-button v-if="detail.disabled" type="primary" plain :icon="VideoPlay" @click="openAvailability">启用</el-button>
        <el-button v-else type="danger" plain :icon="CircleClose" @click="openAvailability">停用</el-button>
      </template>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="Shell 不存在" :sub-title="`没有找到名为 ${shell} 的 Shell`">
      <template #extra>
        <el-button type="primary" @click="router.push('/shells')">返回目录</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-alert v-if="detail?.disabled" type="error" :closable="false" show-icon class="notice" title="这个 Shell 已停用">
        <template #default>
          <div>各 Crab 同步后会卸下它，调用返回「Shell 已停用」（SHELL_DISABLED）。停用期间可以上传新版本，但不能发布或回滚。</div>
          <div class="hm-muted">
            <template v-if="detail.disabledReason">原因：{{ detail.disabledReason }} · </template>
            {{ detail.disabledBy ?? '—' }} 于 {{ formatTime(detail.disabledAt) }} 停用
          </div>
        </template>
      </el-alert>

      <el-alert
        v-if="!loading && !detail && versions.length"
        type="info"
        :closable="false"
        show-icon
        title="这个 Shell 还没有发布版本，Crab 暂时拿不到它。在「版本」里发布一个已上传的版本。"
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
          <el-button link type="primary" class="jump" @click="tab = 'versions'">去发布</el-button>
        </template>
      </el-alert>

      <el-tabs v-model="tab" class="tabs">
        <el-tab-pane label="概况" name="overview" :disabled="!detail">
          <div v-if="detail" class="hm-grid cols-2">
            <div class="hm-card">
              <div class="hm-card-title"><span>当前发布版本</span></div>
              <dl class="hm-kv">
                <dt>名称</dt>
                <dd class="mono">{{ detail.shell }}</dd>
                <dt>版本</dt>
                <dd class="mono">v{{ detail.version }}</dd>
                <dt>驱动</dt>
                <dd>{{ detail.driver }}</dd>
                <dt>最低 Crab 版本</dt>
                <dd class="mono">{{ detail.minCrabVersion }}</dd>
                <dt>老系统地址</dt>
                <dd class="mono">{{ detail.baseUrl ?? '由 Crab 端配置' }}</dd>
                <dt>负责人</dt>
                <dd>{{ detail.owner ?? '—' }}</dd>
                <dt>标签</dt>
                <dd>
                  <el-tag v-for="tag in detail.tags" :key="tag" size="small" class="tag">{{ tag }}</el-tag>
                  <span v-if="!detail.tags.length">—</span>
                </dd>
              </dl>
            </div>
            <div class="hm-card">
              <div class="hm-card-title"><span>更新说明</span></div>
              <p v-if="detail.changelog" class="changelog">{{ detail.changelog }}</p>
              <p v-else class="hm-muted">这个版本没有写 changelog。</p>
              <div class="counts">
                <div><strong>{{ capabilityCount }}</strong><span class="hm-muted">能力</span></div>
                <div><strong>{{ credentialCount }}</strong><span class="hm-muted">凭证字段</span></div>
                <div><strong>{{ configCount }}</strong><span class="hm-muted">配置项</span></div>
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
              <span class="hm-muted small">在 Crab 端按 Shell 填写，所有账号共用</span>
            </div>
            <FieldTable :fields="detail.config" empty="没有配置项" with-default />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`能力（${capabilityCount}）`" name="capabilities" :disabled="!detail">
          <CapabilityList v-if="detail" :actions="detail.actions" />
        </el-tab-pane>

        <el-tab-pane :label="`版本（${versions.length}）`" name="versions">
          <VersionPanel :shell="shell" :versions="versions" :loading="loading" :disabled="detail?.disabled" @changed="load" />
        </el-tab-pane>

        <el-tab-pane label="运行统计" name="stats" lazy>
          <ShellStats :shell="shell" :versions="versions" />
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-dialog v-model="availability.open" :title="detail?.disabled ? `启用 ${shell}` : `停用 ${shell}`" width="480px">
      <p v-if="detail?.disabled" class="hm-muted">启用后，各 Crab 下次同步时重新验签并安装当前发布版本 v{{ detail.version }}。</p>
      <p v-else class="hm-muted">
        停用后，各 Crab 下次同步时卸下这个 Shell，调用会返回「Shell 已停用」。账号和凭证保留在 Crab 本地，启用后可继续使用。
      </p>
      <el-input
        v-model="availability.reason"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="原因，会记入审计日志"
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
