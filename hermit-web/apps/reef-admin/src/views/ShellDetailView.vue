<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type ShellDetail, type ShellVersion } from '../api'
import FieldTable from '../components/FieldTable.vue'
import CapabilityList from '../components/CapabilityList.vue'
import VersionPanel from '../components/VersionPanel.vue'
import ShellStats from '../components/ShellStats.vue'

const props = defineProps<{ shell: string }>()
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const detail = ref<ShellDetail | null>(null)
const versions = ref<ShellVersion[]>([])
const missing = ref(false)
const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'overview')

const credentialCount = computed(() => Object.keys(detail.value?.credentials ?? {}).length)
const configCount = computed(() => Object.keys(detail.value?.config ?? {}).length)
const capabilityCount = computed(() => Object.keys(detail.value?.actions ?? {}).length)

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

watch(() => props.shell, load, { immediate: true })
</script>

<template>
  <div v-loading="loading">
    <PageHeader :title="detail?.title || shell" :description="detail?.description">
      <template #crumbs>
        <router-link to="/shells">Shell 目录</router-link>
        <span class="hm-muted"> / {{ shell }}</span>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="Shell 不存在" :sub-title="`没有找到名为 ${shell} 的 Shell`">
      <template #extra>
        <el-button type="primary" @click="router.push('/shells')">返回目录</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-alert
        v-if="!loading && !detail && versions.length"
        type="info"
        :closable="false"
        show-icon
        title="这个 Shell 还没有发布版本，Crab 暂时拿不到它。在「版本」里发布一个已上传的版本。"
        class="notice"
      />

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
          <VersionPanel :shell="shell" :versions="versions" @changed="load" />
        </el-tab-pane>

        <el-tab-pane label="运行统计" name="stats" lazy>
          <ShellStats :shell="shell" :versions="versions" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.tag {
  margin-right: 4px;
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
