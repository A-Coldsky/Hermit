<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type Account, type ConfigValue, type ConchDetail } from '../api'
import AccountsPanel from '../components/AccountsPanel.vue'
import CapabilityToggles from '../components/CapabilityToggles.vue'
import ConfigPanel from '../components/ConfigPanel.vue'
import { refreshPending } from '../status'

const props = defineProps<{ conch: string }>()
const router = useRouter()

const loading = ref(true)
const missing = ref(false)
const detail = ref<ConchDetail | null>(null)
const accounts = ref<Account[]>([])
const config = ref<Record<string, ConfigValue>>({})
const partsFailed = ref(false)

const missingConfig = computed(() => Object.entries(config.value).filter(([, f]) => f.missing).map(([name]) => name))
const incompleteAccounts = computed(() =>
  accounts.value.filter((a) => a.needsConfiguration && !a.credentialsUnreadable).map((a) => a.name),
)
const unreadableAccounts = computed(() => accounts.value.filter((a) => a.credentialsUnreadable).map((a) => a.name))
const pausedAccounts = computed(() => accounts.value.filter((a) => a.loginPaused))
const needsAccount = computed(() => !!detail.value?.credentials.length && !accounts.value.length)
const ready = computed(
  () =>
    !missingConfig.value.length &&
    !incompleteAccounts.value.length &&
    !unreadableAccounts.value.length &&
    !pausedAccounts.value.length &&
    !needsAccount.value,
)

function blockReason(account: Account): string | null {
  if (account.credentialsUnreadable) return '凭证无法解密'
  if (account.needsConfiguration) return '缺少凭证'
  if (account.loginPaused) return '已暂停自动登录'
  return null
}

const defaultAccount = computed(() => accounts.value.find((a) => a.default) ?? null)

const bannerTitle = computed(() => {
  if (missingConfig.value.length) return '缺少必填配置，调用会返回「待配置」'
  if (needsAccount.value) return '还没有账号，调用会返回「待配置」'
  const account = defaultAccount.value
  const reason = account && blockReason(account)
  if (account && reason) return `默认账号 ${account.name} ${reason}，不带 X-Hermit-Account 的调用会失败`
  return '默认账号可以调用，部分账号需要处理'
})

async function fetchParts(): Promise<[Account[], Record<string, ConfigValue>]> {
  const [a, c] = await Promise.all([api.accounts(props.conch), api.config(props.conch)])
  return [a.accounts, c.fields]
}

async function reloadParts() {
  try {
    ;[accounts.value, config.value] = await fetchParts()
    partsFailed.value = false
    void refreshPending()
  } catch (error) {
    partsFailed.value = true
    notifyError(error)
  }
}

async function load() {
  loading.value = true
  missing.value = false
  let next: ConchDetail
  try {
    next = await api.conch(props.conch)
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
  try {
    ;[accounts.value, config.value] = await fetchParts()
    partsFailed.value = false
    void refreshPending()
  } catch (error) {
    partsFailed.value = true
    notifyError(error)
  }
  detail.value = next
  loading.value = false
}

watch(
  () => props.conch,
  () => {
    detail.value = null
    accounts.value = []
    config.value = {}
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div v-loading="loading" class="detail">
    <PageHeader :title="detail?.title || conch" :description="detail?.description">
      <template #crumbs>
        <router-link to="/conches">规则包与账号</router-link>
        <span class="hm-muted"> / {{ conch }}</span>
      </template>
      <template #icon>
        <ConchAvatar :name="conch" :title="detail?.title" :size="48" />
      </template>
      <template #badge>
        <el-tag v-if="detail" type="info" effect="light" round class="mono">v{{ detail.version }}</el-tag>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="规则包未安装" :sub-title="`本机没有名为 ${conch} 的规则包`">
      <template #extra>
        <el-button type="primary" @click="router.push('/conches')">返回列表</el-button>
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
        :title="bannerTitle"
      >
        <template #default>
          <div v-if="needsAccount">先新建一个账号并填写凭证。</div>
          <div v-if="missingConfig.length">缺少必填配置：<span class="mono">{{ missingConfig.join(', ') }}</span></div>
          <div v-if="unreadableAccounts.length">
            凭证无法解密，需全部重新填写：<span class="mono">{{ unreadableAccounts.join(', ') }}</span>
          </div>
          <div v-if="incompleteAccounts.length">账号缺少凭证：<span class="mono">{{ incompleteAccounts.join(', ') }}</span></div>
          <div v-for="a in pausedAccounts" :key="a.name">
            账号 <span class="mono">{{ a.name }}</span> 已暂停自动登录<template v-if="a.loginPauseReason">：{{ a.loginPauseReason }}</template>。重新填写凭证后在下方账号列表点「测试登录」，登录成功即恢复。
          </div>
        </template>
      </el-alert>

      <AccountsPanel :conch="conch" :accounts="accounts" :credentials="detail.credentials" @changed="reloadParts" />
      <ConfigPanel :conch="conch" :fields="config" @changed="reloadParts" />
      <CapabilityToggles :conch="conch" :capabilities="detail.capabilities" />
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
