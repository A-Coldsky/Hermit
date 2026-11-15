<script setup lang="ts">
import { Delete, Download, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import TimeText from '@shared/components/TimeText.vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api, type Account, type ConfigValue, type ConchDetail, type DebugState } from '../api'
import AccountsPanel from '../components/AccountsPanel.vue'
import CapabilityToggles from '../components/CapabilityToggles.vue'
import ConfigPanel from '../components/ConfigPanel.vue'
import DeveloperPanel from '../components/DeveloperPanel.vue'
import { refreshPending } from '../status'
import { unsubscribeMessage } from '../copy'
import { canRollback, previousBlockedNote } from '../previous'
import { repairConch, updateConch } from '../update'
import { disabledReasonOnly } from '../disabled'

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
const needsAccount = computed(() => accounts.value.length === 0)
const disabledReason = computed(() => disabledReasonOnly(detail.value?.message))
const rollbackable = computed(() => (detail.value ? canRollback(detail.value) : false))
const previousNote = computed(() => (detail.value ? previousBlockedNote(detail.value) : null))
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

/** 停用包不在调用路径上，账号是否齐全都不能调用，横幅要先说这一点。 */
const RULE_DISABLED_BANNER = '这版已停用，所有账号暂时都不能调用'

const bannerTitle = computed(() => {
  if (detail.value?.ruleDisabled) return RULE_DISABLED_BANNER
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
  if (next.repairRequired) {
    accounts.value = []
    config.value = {}
    partsFailed.value = false
    detail.value = next
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

const local = computed(() => detail.value?.source === 'LOCAL')
const debug = ref<DebugState>({ enabled: false, expiresAt: null })
const working = ref(false)

async function loadDebug() {
  try {
    debug.value = await api.debug()
  } catch {
    debug.value = { enabled: false, expiresAt: null }
  }
}

async function exportYaml() {
  if (!detail.value) return
  working.value = true
  try {
    const text = await api.exportLocalConch(props.conch)
    const url = URL.createObjectURL(new Blob([text], { type: 'application/yaml' }))
    const link = document.createElement('a')
    link.href = url
    const version = detail.value.version
    link.download = version ? `${props.conch}-${version}.yaml` : `${props.conch}.yaml`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    notifyError(error)
  } finally {
    working.value = false
  }
}

async function confirmThen(message: string, title: string, confirm: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: confirm,
      cancelButtonText: '保留',
      type: 'warning',
      confirmButtonType: 'danger',
    })
    return true
  } catch {
    return false
  }
}

async function removeLocal() {
  const ok = await confirmThen(
    `删除后 ${props.conch} 的 YAML 文件会从本机移除，调用会失败。账号、凭证和配置会保留，再次导入同名规则包后可以直接使用。`,
    `删除本地规则包 ${props.conch}`,
    '删除',
  )
  if (!ok) return
  working.value = true
  try {
    await api.deleteLocalConch(props.conch)
    ElMessage.success(`已删除本地规则包 ${props.conch}`)
    void refreshPending()
    void router.push('/conches')
  } catch (error) {
    notifyError(error)
  } finally {
    working.value = false
  }
}

async function repair() {
  if (!detail.value?.repairRequired) return
  working.value = true
  try {
    if (await repairConch(props.conch, detail.value.repairReason ?? null)) {
      void refreshPending()
      await load()
    }
  } finally {
    working.value = false
  }
}

async function rollback() {
  if (!detail.value || !canRollback(detail.value)) return
  try {
    await ElMessageBox.confirm(
      `退回 v${detail.value.previousVersion}。当前版本会留作下一次可退回的版本。`,
      `退回 ${props.conch}`,
      { confirmButtonText: '退回', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  working.value = true
  try {
    await api.rollback(props.conch)
    ElMessage.success(`已退回 v${detail.value.previousVersion}`)
    void refreshPending()
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    working.value = false
  }
}

async function update() {
  if (!detail.value) return
  working.value = true
  try {
    const switched = await updateConch({
      conch: props.conch,
      installedVersion: detail.value.version,
      latestVersion: detail.value.latestVersion,
      newRequiredCredentials: detail.value.newRequiredCredentials,
    })
    if (switched) {
      void refreshPending()
      await load()
    }
  } finally {
    working.value = false
  }
}

async function unsubscribe() {
  const ok = await confirmThen(
    unsubscribeMessage(props.conch),
    `取消订阅 ${detail.value?.title || props.conch}`,
    '取消订阅',
  )
  if (!ok) return
  working.value = true
  try {
    await api.unsubscribe(props.conch)
    ElMessage.success(`已取消订阅 ${props.conch}`)
    void refreshPending()
    void router.push('/catalog')
  } catch (error) {
    notifyError(error)
  } finally {
    working.value = false
  }
}

watch(
  () => props.conch,
  () => {
    detail.value = null
    accounts.value = []
    config.value = {}
    void load()
    void loadDebug()
  },
  { immediate: true },
)
</script>

<template>
  <div v-loading="loading" class="detail">
    <PageHeader :title="detail?.title || conch" :description="detail?.description ?? undefined">
      <template #crumbs>
        <router-link to="/conches">规则包与账号</router-link>
        <span class="hm-muted"> / {{ conch }}</span>
      </template>
      <template #icon>
        <ConchAvatar :name="conch" :title="detail?.title" :size="48" />
      </template>
      <template #badge>
        <el-tag v-if="detail?.version && !detail.repairRequired" type="info" effect="light" round class="mono">v{{ detail.version }}</el-tag>
        <el-tag v-if="local" type="info" effect="plain" round>本地</el-tag>
        <el-tag v-if="detail?.ruleDisabled" type="danger" effect="plain" round>已停用</el-tag>
        <el-tag v-if="detail?.reefDisabled" type="warning" effect="plain" round>Reef 已停用</el-tag>
        <el-tag v-if="detail?.updateAvailable" type="warning" effect="plain" round class="mono">有新版本 v{{ detail.latestVersion }}</el-tag>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <template v-if="local">
        <el-button :icon="Download" :loading="working" @click="exportYaml">导出 YAML</el-button>
        <el-button type="danger" plain :icon="Delete" :disabled="working" @click="removeLocal">删除</el-button>
      </template>
      <el-button v-else-if="detail" type="danger" plain :disabled="working" @click="unsubscribe">取消订阅</el-button>
    </PageHeader>

    <el-result v-if="missing" icon="warning" title="规则包未安装" :sub-title="`本机没有名为 ${conch} 的规则包`">
      <template #extra>
        <el-button type="primary" @click="router.push('/conches')">返回列表</el-button>
      </template>
    </el-result>

    <template v-else-if="detail?.repairRequired">
      <el-alert
        type="error"
        show-icon
        :closable="false"
        :title="detail.message || ''"
        data-test="repair-message"
      >
        <template #default>
          <el-button type="primary" class="update-btn" :loading="working" data-test="repair" @click="repair">修复</el-button>
        </template>
      </el-alert>
    </template>

    <template v-else-if="detail">
      <el-alert
        v-if="detail.ruleDisabled"
        type="error"
        show-icon
        :closable="false"
        title="已停用。这版规则暂时不能调用。"
        data-test="rule-disabled"
      >
        <template #default>
          <div v-if="disabledReason">{{ disabledReason }}</div>
          <div v-if="detail.fixHint" data-test="fix-hint">{{ detail.fixHint }}</div>
          <div v-if="previousNote" data-test="previous-note">{{ previousNote }}</div>
          <el-button
            v-if="rollbackable"
            class="update-btn"
            data-test="rollback"
            :loading="working"
            @click="rollback"
          >退回 v{{ detail.previousVersion }}</el-button>
        </template>
      </el-alert>
      <el-alert
        v-if="detail.reefDisabled"
        type="warning"
        show-icon
        :closable="false"
        title="Reef 已停用这个规则包"
        data-test="reef-disabled"
      >
        <template #default>
          本机保留的 v{{ detail.version }} 仍可正常调用，但不能再更新。Reef 重新启用后，下次同步取消停用标记；有更高版本时可以在这里手动更新。
          <template v-if="detail.reefDisabledAt">本机于 <TimeText :value="detail.reefDisabledAt" /> 发现停用。</template>
        </template>
      </el-alert>
      <el-alert
        v-if="detail.updateAvailable"
        type="warning"
        show-icon
        :closable="false"
        :title="`Reef 有新版本 v${detail.latestVersion}，本机仍是 v${detail.version}`"
        data-test="update-available"
      >
        <template #default>
          <div v-if="detail.ruleDisabled">当前版本已经停用，不能继续调用。更新到能通过校验的版本后才能再用。</div>
          <div v-else>不更新也可以一直使用当前版本。更新后可以退回刚换下的那一版；再更新一次，可退回的就换成新换下的那一版。</div>
          <div v-if="detail.newRequiredCredentials.length">
            新版本需要这些凭证：<span class="mono">{{ detail.newRequiredCredentials.join('、') }}</span>。先在下方默认账号里填好，再点更新。
          </div>
          <el-button type="primary" class="update-btn" :loading="working" @click="update">更新到 v{{ detail.latestVersion }}</el-button>
        </template>
      </el-alert>
      <el-alert
        v-if="!detail.ruleDisabled && previousNote"
        type="info"
        show-icon
        :closable="false"
        :title="previousNote"
        data-test="previous-note"
      />
      <div v-if="!detail.ruleDisabled && rollbackable">
        <el-button class="update-btn" data-test="rollback" :loading="working" @click="rollback">退回 v{{ detail.previousVersion }}</el-button>
      </div>
      <el-alert v-if="local" type="info" show-icon :closable="false" title="本地规则包：未经 Reef 签名，只在本机可用">
        <template #default>
          全部权限的 Key 可以调用这个本地规则包。在范围里点名了它的 Key 也可以调用。调用不会上报 Reef。
          导出的 YAML 可以直接在 Reef「上传规则包」页面上传，校验签名后发布给其他 Crab。
        </template>
      </el-alert>
      <el-alert v-if="partsFailed" type="error" show-icon :closable="false" title="账号或配置没有加载出来，下面的状态可能不完整">
        <template #default>
          <el-button size="small" @click="reloadParts">重新加载</el-button>
        </template>
      </el-alert>
      <el-alert v-else-if="ready && !detail.ruleDisabled" type="success" show-icon :closable="false" title="配置完整" data-test="ready" />
      <el-alert
        v-else
        type="warning"
        show-icon
        :closable="false"
        :title="bannerTitle"
      >
        <template #default>
          <div v-if="needsAccount">先新建一个账号。</div>
          <div v-if="missingConfig.length">缺少必填配置：<span class="mono">{{ missingConfig.join('、') }}</span></div>
          <div v-if="unreadableAccounts.length">
            凭证无法解密，需全部重新填写：<span class="mono">{{ unreadableAccounts.join('、') }}</span>
          </div>
          <div v-if="incompleteAccounts.length">账号缺少凭证：<span class="mono">{{ incompleteAccounts.join('、') }}</span></div>
          <div v-for="a in pausedAccounts" :key="a.name">
            账号 <span class="mono">{{ a.name }}</span> 已暂停自动登录<template v-if="a.loginPauseReason">：{{ a.loginPauseReason }}</template>。重新填写凭证后在下方账号列表点「测试登录」，登录成功即恢复。
          </div>
        </template>
      </el-alert>

      <AccountsPanel
        :conch="conch"
        :accounts="accounts"
        :credentials="detail.credentials"
        :rule-disabled="detail.ruleDisabled === true"
        @changed="reloadParts"
      />
      <ConfigPanel
        :conch="conch"
        :fields="config"
        :empty-text="detail.ruleDisabled && detail.declarationsLoaded !== true && !detail.config.length ? '这版已停用，配置项先不展示' : '这个规则包没有配置项'"
        @changed="reloadParts"
      />
      <DeveloperPanel
        v-if="local"
        :conch="conch"
        :driver="detail.driver === 'mcp' ? 'mcp' : 'http'"
        :debug-enabled="debug.enabled"
        :capabilities="detail.capabilities"
        :accounts="accounts"
        @changed="load"
      />
      <CapabilityToggles
        :conch="conch"
        :capabilities="detail.capabilities"
        :rule-disabled="detail.ruleDisabled === true"
        :empty-text="detail.ruleDisabled && detail.declarationsLoaded !== true && !detail.capabilities.length ? '这版已停用，能力先不展示' : '没有能力'"
      />
    </template>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.update-btn {
  margin-top: 8px;
}

.detail :deep(.hm-card + .hm-card) {
  margin-top: 0;
}
</style>
