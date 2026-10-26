<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ShellAvatar from '@shared/components/ShellAvatar.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type Account, type ConfigValue, type ShellDetail } from '../api'
import AccountsPanel from '../components/AccountsPanel.vue'
import CapabilityToggles from '../components/CapabilityToggles.vue'
import ConfigPanel from '../components/ConfigPanel.vue'
import { refreshPending } from '../status'

const props = defineProps<{ shell: string }>()
const router = useRouter()

const loading = ref(true)
const missing = ref(false)
const detail = ref<ShellDetail | null>(null)
const accounts = ref<Account[]>([])
const config = ref<Record<string, ConfigValue>>({})
const partsFailed = ref(false)

const missingConfig = computed(() => Object.entries(config.value).filter(([, f]) => f.missing).map(([name]) => name))
const incompleteAccounts = computed(() =>
  accounts.value.filter((a) => a.needsConfiguration && !a.credentialsUnreadable).map((a) => a.name),
)
const unreadableAccounts = computed(() => accounts.value.filter((a) => a.credentialsUnreadable).map((a) => a.name))
const pausedAccounts = computed(() => accounts.value.filter((a) => a.loginPaused))
const ready = computed(
  () =>
    !missingConfig.value.length &&
    !incompleteAccounts.value.length &&
    !unreadableAccounts.value.length &&
    !pausedAccounts.value.length &&
    (accounts.value.length > 0 || !detail.value?.credentials.length),
)

async function loadParts() {
  const [a, c] = await Promise.all([api.accounts(props.shell), api.config(props.shell)])
  accounts.value = a.accounts
  config.value = c.fields
  partsFailed.value = false
  void refreshPending()
}

async function reloadParts() {
  try {
    await loadParts()
  } catch (error) {
    partsFailed.value = true
    notifyError(error)
  }
}

async function load() {
  loading.value = true
  missing.value = false
  try {
    detail.value = await api.shell(props.shell)
  } catch (error) {
    detail.value = null
    if (error instanceof ApiError && error.status === 404) {
      missing.value = true
    } else {
      notifyError(error)
    }
    loading.value = false
    return
  }
  await reloadParts()
  loading.value = false
}

watch(() => props.shell, load, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="detail">
    <PageHeader :title="detail?.title || shell" :description="detail?.description">
      <template #crumbs>
        <router-link to="/shells">Shell 与账号</router-link>
        <span class="hm-muted"> / {{ shell }}</span>
      </template>
      <template #icon>
        <ShellAvatar :name="shell" :title="detail?.title" :size="48" />
      </template>
      <template #badge>
        <el-tag v-if="detail" type="info" effect="light" round class="mono">v{{ detail.version }}</el-tag>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="Shell 未安装" :sub-title="`本机没有名为 ${shell} 的 Shell`">
      <template #extra>
        <el-button type="primary" @click="router.push('/shells')">返回列表</el-button>
      </template>
    </el-result>

    <template v-else-if="detail">
      <el-alert v-if="partsFailed" type="error" show-icon :closable="false" title="账号或配置没有加载出来，下面的状态可能不完整">
        <template #default>
          <el-button size="small" @click="reloadParts">重新加载</el-button>
        </template>
      </el-alert>
      <el-alert v-else-if="ready" type="success" show-icon :closable="false" title="配置完整，可以调用" />
      <el-alert
        v-else
        type="warning"
        show-icon
        :closable="false"
        :title="pausedAccounts.length && pausedAccounts.length === accounts.length && !missingConfig.length ? '自动登录已暂停，修正后才能调用' : '还不能调用，调用会返回「待配置」'"
      >
        <template #default>
          <div v-if="detail.credentials.length && !accounts.length">先新建一个账号并填写凭证。</div>
          <div v-for="a in pausedAccounts" :key="a.name">
            账号 <span class="mono">{{ a.name }}</span> 已暂停自动登录<template v-if="a.loginPauseReason">：{{ a.loginPauseReason }}</template>。修正凭证后在下方账号列表点「测试登录」，登录成功即恢复。
          </div>
          <div v-if="unreadableAccounts.length">
            凭证无法解密，需重新填写：<span class="mono">{{ unreadableAccounts.join(', ') }}</span>
          </div>
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
