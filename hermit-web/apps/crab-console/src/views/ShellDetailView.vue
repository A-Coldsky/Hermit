<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type Account, type ConfigValue, type ShellDetail } from '../api'
import AccountsPanel from '../components/AccountsPanel.vue'
import CapabilityToggles from '../components/CapabilityToggles.vue'
import ConfigPanel from '../components/ConfigPanel.vue'
import { refreshPending } from '../status'

const props = defineProps<{ shell: string }>()
const router = useRouter()

const loading = ref(false)
const missing = ref(false)
const detail = ref<ShellDetail | null>(null)
const accounts = ref<Account[]>([])
const config = ref<Record<string, ConfigValue>>({})

const missingConfig = computed(() => Object.entries(config.value).filter(([, f]) => f.missing).map(([name]) => name))
const incompleteAccounts = computed(() => accounts.value.filter((a) => a.needsConfiguration).map((a) => a.name))
const ready = computed(
  () => !missingConfig.value.length && !incompleteAccounts.value.length && (accounts.value.length > 0 || !detail.value?.credentials.length),
)

async function loadParts() {
  const [a, c] = await Promise.all([api.accounts(props.shell), api.config(props.shell)])
  accounts.value = a.accounts
  config.value = c.fields
  void refreshPending()
}

async function load() {
  loading.value = true
  missing.value = false
  try {
    detail.value = await api.shell(props.shell)
    await loadParts()
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      missing.value = true
    } else {
      notifyError(error)
    }
  } finally {
    loading.value = false
  }
}

async function reloadParts() {
  try {
    await loadParts()
  } catch (error) {
    notifyError(error)
  }
}

watch(() => props.shell, load, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="detail">
    <PageHeader :title="detail?.title || shell" :description="detail?.description">
      <template #crumbs>
        <router-link to="/shells">Shell 与账号</router-link>
        <span class="hm-muted"> / {{ shell }}</span>
        <span v-if="detail" class="mono hm-muted"> v{{ detail.version }}</span>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="Shell 未安装" :sub-title="`本机没有名为 ${shell} 的 Shell`">
      <template #extra>
        <el-button type="primary" @click="router.push('/shells')">返回列表</el-button>
      </template>
    </el-result>

    <template v-else-if="detail">
      <el-alert v-if="ready" type="success" show-icon :closable="false" title="配置完整，可以调用" />
      <el-alert v-else type="warning" show-icon :closable="false" title="还不能调用，调用会返回「待配置」">
        <template #default>
          <div v-if="detail.credentials.length && !accounts.length">先新建一个账号并填写凭证。</div>
          <div v-if="incompleteAccounts.length">账号缺少凭证：<span class="mono">{{ incompleteAccounts.join(', ') }}</span></div>
          <div v-if="missingConfig.length">缺少必填配置：<span class="mono">{{ missingConfig.join(', ') }}</span></div>
        </template>
      </el-alert>

      <AccountsPanel :shell="shell" :accounts="accounts" :credentials="detail.credentials" @changed="reloadParts" />
      <ConfigPanel :shell="shell" :fields="config" @changed="reloadParts" />
      <CapabilityToggles :shell="shell" :capabilities="detail.capabilities" />
    </template>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail :deep(.hm-card + .hm-card) {
  margin-top: 0;
}
</style>
