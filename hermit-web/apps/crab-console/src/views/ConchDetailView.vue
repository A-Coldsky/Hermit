<script setup lang="ts">
import { Delete, Download, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
import ShellSwapLoader from '../components/ShellSwapLoader.vue'
import { refreshPending } from '../status'
import { unsubscribeMessage } from '../copy'
import { canRollback, previousBlockedNote } from '../previous'
import { repairConch, updateConch } from '../update'
import { disabledReasonOnly } from '../disabled'
import { notifySameSite } from '../same-site'

const props = defineProps<{ conch: string }>()
const route = useRoute()
const router = useRouter()

type DetailTab = 'capabilities' | 'accounts' | 'config' | 'developer'

const tab = ref<DetailTab>('capabilities')
/** 详情和账号都到齐之后才把标签写进地址，避免先写上「能力」再被待处理账号改掉。 */
const tabsReady = ref(false)

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

const mcp = computed(() => detail.value?.driver === 'mcp')
const capabilityCount = computed(() => detail.value?.capabilities.length ?? 0)
const capabilityLabel = computed(() => `${mcp.value ? '工具' : '能力'}（${capabilityCount.value}）`)

/**
 * 账号标签上的红点，也是「没有指定标签时改开账号」的条件：
 * 声明了必填凭证但还没填（一个账号都没有也算）、有账号在等验证码，或自动登录已暂停。
 * 凭证解不开不算「尚未填写」。账号或配置没加载出来时不猜。
 */
const accountAttention = computed(() => {
  const current = detail.value
  if (!current || partsFailed.value || current.repairRequired) return false
  if (accounts.value.some((account) => account.loginPaused || account.loginInputRequired)) return true
  const required = new Set(current.credentials.filter((field) => field.required).map((field) => field.name))
  if (required.size === 0) return false
  if (accounts.value.length === 0) return true
  return accounts.value.some((account) => {
    if (account.credentialsUnreadable) return false
    if (account.needsConfiguration) return true
    return (account.missingFields ?? []).some((name) => required.has(name))
  })
})

function tabChoices(): DetailTab[] {
  const names: DetailTab[] = ['capabilities', 'accounts', 'config']
  if (detail.value?.source === 'LOCAL') names.push('developer')
  return names
}

function fallbackTab(): DetailTab {
  return accountAttention.value ? 'accounts' : 'capabilities'
}

function resolvedTab(): DetailTab {
  const raw = route.query.tab
  const requested = typeof raw === 'string' ? raw : ''
  return tabChoices().includes(requested as DetailTab) ? (requested as DetailTab) : fallbackTab()
}

watch(tab, (value) => {
  if (!tabsReady.value || route.query.tab === value) return
  void router.replace({ query: { ...route.query, tab: value } })
})

watch(
  () => route.query.tab,
  () => {
    if (!tabsReady.value || !detail.value || detail.value.repairRequired) return
    const next = resolvedTab()
    if (tab.value !== next) tab.value = next
  },
)

function settleTabs() {
  if (!detail.value || detail.value.repairRequired) return
  const next = resolvedTab()
  tabsReady.value = true
  if (tab.value !== next) tab.value = next
  else if (route.query.tab !== next) void router.replace({ query: { ...route.query, tab: next } })
}

function blockReason(account: Account): string | null {
  if (account.credentialsUnreadable) return '凭证无法解密'
  if (account.needsConfiguration) return '缺少凭证'
  if (account.loginPaused) return '已暂停自动登录'
  return null
}

const defaultAccount = computed(() => accounts.value.find((a) => a.default) ?? null)

/** 停用包不在调用路径上，账号是否齐全都不能调用，横幅要先说这一点。 */
const RULE_DISABLED_BANNER = '这版已停用，所有账号暂时都不能调用'

/** 整包停用时账号的状态只说明启用之后的事，不能读成现在可以调用。 */
const SWITCHED_OFF_BANNER = '规则包已停用。重新启用前，下面这些账号和配置还需要处理'

const bannerTitle = computed(() => {
  if (detail.value?.ruleDisabled) return RULE_DISABLED_BANNER
  if (detail.value?.switchedOff) return SWITCHED_OFF_BANNER
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
  settleTabs()
  loading.value = false
}

const local = computed(() => detail.value?.source === 'LOCAL')
const debug = ref<DebugState>({ enabled: false, expiresAt: null })
const working = ref(false)
/** 更新确认之后、安装进行中显示换壳动画。 */
const swap = ref<string | null>(null)

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

async function setEnabled(enabled: boolean) {
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        '停用后这个规则包的所有能力都不能调用，也不出现在 MCP 工具列表和 OpenAPI 里。账号、凭证和配置保持不变，可以随时重新启用。正在执行的调用不会被打断。',
        `停用 ${props.conch}`,
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  working.value = true
  try {
    const done = await api.setConchEnabled(props.conch, enabled)
    ElMessage.success(enabled ? '已启用规则包' : '已停用规则包')
    if (enabled) notifySameSite(props.conch, done.sameSite)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    working.value = false
  }
}

const twoSources = computed(() => !!detail.value?.localVersion && !!detail.value?.reefVersion)

/** 切到正式包的确认框。复制会覆盖正式包已有的值，所以默认不勾选。 */
const toReef = reactive({ open: false, copy: false })

async function switchSource(target: 'LOCAL' | 'REEF') {
  if (target === 'REEF') {
    Object.assign(toReef, { open: true, copy: false })
    return
  }
  try {
    await ElMessageBox.confirm(
      '切换后调用的是本机的本地包，包名、API Key 和工具名都不变。本地包使用它自己的账号、凭证和配置，正式包的不会带过来。',
      `切到本地包 ${props.conch}`,
      { confirmButtonText: '切换', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  await applySource('LOCAL', false)
}

async function confirmToReef() {
  toReef.open = false
  await applySource('REEF', toReef.copy)
}

async function applySource(target: 'LOCAL' | 'REEF', copy: boolean) {
  working.value = true
  try {
    const done = await api.setConchSource(props.conch, target, copy)
    ElMessage.success(
      target === 'LOCAL'
        ? '已切到本地包'
        : done.copied
          ? `已切到正式包，复制了 ${done.copiedAccounts} 个账号、${done.copiedConfigKeys} 项配置`
          : '已切到正式包，没有复制凭证和配置',
    )
    notifySameSite(props.conch, done.sameSite)
    void refreshPending()
    await load()
  } catch (error) {
    notifyError(error)
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
    const switched = await updateConch(
      {
        conch: props.conch,
        installedVersion: detail.value.version,
        latestVersion: detail.value.latestVersion,
        newRequiredCredentials: detail.value.newRequiredCredentials,
      },
      () => (swap.value = '正在换上新壳…'),
    )
    if (switched) {
      void refreshPending()
      await load()
    }
  } finally {
    swap.value = null
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
    tabsReady.value = false
    tab.value = 'capabilities'
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
        <el-tag v-if="detail?.switchedOff" type="danger" effect="plain" round data-test="switched-off-tag">本机已停用</el-tag>
        <el-tag v-if="detail?.reefDisabled" type="warning" effect="plain" round>Reef 已停用</el-tag>
        <el-tag v-if="detail?.updateAvailable" type="warning" effect="plain" round class="mono">有新版本 v{{ detail.latestVersion }}</el-tag>
      </template>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <template v-if="detail && detail.switchedOff !== undefined">
        <el-button v-if="detail.switchedOff" type="primary" :disabled="working" data-test="conch-enable" @click="setEnabled(true)">启用规则包</el-button>
        <el-button v-else type="warning" plain :disabled="working" data-test="conch-disable" @click="setEnabled(false)">停用规则包</el-button>
      </template>
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
            class="update-btn neutral"
            data-test="rollback"
            :loading="working"
            @click="rollback"
          >退回 v{{ detail.previousVersion }}</el-button>
        </template>
      </el-alert>
      <el-alert
        v-if="twoSources"
        type="info"
        show-icon
        :closable="false"
        :title="local ? '本机有同名的正式包，当前生效的是本地包' : '本机有同名的本地包，当前生效的是正式包'"
        data-test="two-sources"
      >
        <template #default>
          本地包 v{{ detail.localVersion }}，正式包 v{{ detail.reefVersion }}。两份都留在本机，同一时刻只有一份被调用；账号、凭证和配置各自保存。
          <el-button
            v-if="local"
            link
            type="primary"
            :disabled="working"
            data-test="source-to-reef"
            @click="switchSource('REEF')"
          >切到正式包</el-button>
          <el-button
            v-else
            link
            type="primary"
            :disabled="working"
            data-test="source-to-local"
            @click="switchSource('LOCAL')"
          >切到本地包</el-button>
        </template>
      </el-alert>
      <el-alert
        v-if="detail.sameSite?.length"
        :type="detail.contendingWith?.length ? 'warning' : 'info'"
        show-icon
        :closable="false"
        :title="detail.contendingWith?.length ? '可能在和同网站的规则包互相顶掉登录' : '本机还有规则包访问同一个网站'"
        data-test="same-site"
      >
        <template #default>
          <template v-for="(name, index) in detail.sameSite" :key="name">
            <template v-if="index > 0">、</template>
            <router-link :to="`/conches/${encodeURIComponent(name)}`" data-test="same-site-link">{{ name }}</router-link>
          </template>
          也访问 {{ detail.site }}。这些规则包各自登录、各存各的 Cookie，互不共用。
          <template v-if="detail.contendingWith?.length">
            最近 {{ detail.contendingWith.join('、') }} 和这个包轮流被对方的登录顶掉、又重新登录。同一个网站账号只允许一个会话时会这样：可以让两个包使用不同的网站账号，或只保留其中一个包。Crab 分不清两边是不是同一个人，所以这只是可能。
          </template>
          <template v-else>
            如果网站同一账号只允许一个会话，轮流调用时会互相顶掉登录，表现为调用变慢或返回「会话已失效」。
          </template>
        </template>
      </el-alert>
      <el-alert
        v-if="detail.switchedOff"
        type="error"
        show-icon
        :closable="false"
        title="这个规则包已在本机停用"
        data-test="switched-off"
      >
        <template #default>
          所有能力都不能调用，也不出现在 MCP 工具列表和 OpenAPI 里，有权限的 API Key 也一样。账号、凭证和配置保持不变，点「启用规则包」即可恢复。
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
          <div v-else>可以暂不更新，当前版本照常使用。更新后本机会留着现在这一版，不合适可以退回；只保留最近换下的一版。</div>
          <div v-if="detail.newRequiredCredentials.length">
            新版本需要这些凭证：<span class="mono">{{ detail.newRequiredCredentials.join('、') }}</span>。先在下方默认账号里填好，再点更新。
          </div>
          <div class="update-row">
            <el-button type="primary" class="update-btn" :loading="working" @click="update">更新到 v{{ detail.latestVersion }}</el-button>
            <ShellSwapLoader v-if="swap" compact :label="swap" class="update-swap" data-test="version-swap" />
          </div>
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
          <template v-if="!detail.switchedOff">全部权限的 Key 可以调用这个本地规则包。</template><template v-else>规则包启用时，全部权限的 Key 可以调用这个本地规则包。</template>在范围里点名了它的 Key 也可以调用。调用不会上报 Reef。
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
        data-test="setup-banner"
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

      <div class="tab-block">
        <el-tabs v-model="tab" class="tabs" :class="{ 'resident-open': tab === 'accounts' }" data-test="detail-tabs">
          <el-tab-pane :label="capabilityLabel" name="capabilities">
            <CapabilityToggles
              :conch="conch"
              :capabilities="detail.capabilities"
              :groups="detail.groups"
              :rule-disabled="detail.ruleDisabled === true"
              :empty-text="detail.ruleDisabled && detail.declarationsLoaded !== true && !detail.capabilities.length ? '这版已停用，能力先不展示' : '没有能力'"
            />
          </el-tab-pane>
          <el-tab-pane name="accounts">
            <template #label>
              <span class="tab-label" data-test="tab-accounts">
                账号
                <span v-if="accountAttention" class="attention-dot" data-test="accounts-dot" role="img" aria-label="需要处理" />
              </span>
            </template>
          </el-tab-pane>
          <el-tab-pane label="配置" name="config">
            <ConfigPanel
              :conch="conch"
              :fields="config"
              :empty-text="detail.ruleDisabled && detail.declarationsLoaded !== true && !detail.config.length ? '这版已停用，配置项先不展示' : '这个规则包没有配置项'"
              @changed="reloadParts"
            />
          </el-tab-pane>
          <el-tab-pane v-if="local" label="开发者" name="developer">
            <DeveloperPanel
              :conch="conch"
              :driver="detail.driver === 'mcp' ? 'mcp' : 'http'"
              :debug-enabled="debug.enabled"
              :capabilities="detail.capabilities"
              :groups="detail.groups"
              :accounts="accounts"
              @changed="load"
            />
          </el-tab-pane>
        </el-tabs>
        <!-- 账号面板不放进标签页：未选中的页是 display:none，会把验证码弹窗一起藏起来。卡片自己按标签显隐，弹窗一直挂着。 -->
        <AccountsPanel
          :conch="conch"
          :accounts="accounts"
          :credentials="detail.credentials"
          :rule-disabled="detail.ruleDisabled === true"
          :shown="tab === 'accounts'"
          @changed="reloadParts"
        />
      </div>
    </template>
    <el-dialog v-model="toReef.open" :title="`切到正式包 ${props.conch}`" width="min(520px, 94vw)" data-test="to-reef-dialog">
      <p class="to-reef-text">
        切换后调用的是从 Reef 订阅的正式包，包名、API Key 和工具名都不变。正式包的账号、凭证和配置与本地包分开保存。
      </p>
      <el-checkbox v-model="toReef.copy" data-test="to-reef-copy">同时把本地包的账号、凭证和配置复制到正式包</el-checkbox>
      <p class="to-reef-note">
        只复制正式包声明了的字段。<strong>正式包已有的同名值会被覆盖</strong>，OAuth 连接不复制，需要在正式包上重新连接。
      </p>
      <template #footer>
        <el-button data-test="to-reef-cancel" @click="toReef.open = false">取消</el-button>
        <el-button type="primary" data-test="to-reef-confirm" @click="confirmToReef">{{ toReef.copy ? '复制并切换' : '切换' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.to-reef-text {
  margin: 0 0 14px;
  line-height: 1.6;
}
.to-reef-note {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--hm-muted);
}
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

/* 标签条跟着内容区变窄，放不下时由 Element Plus 在条内横向滚动，不把页面撑出去。 */
.tabs {
  min-width: 0;
  max-width: 100%;
}

.tabs :deep(.el-tabs__header),
.tabs :deep(.el-tabs__nav-wrap) {
  min-width: 0;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

/* 账号卡片在标签页外面。选中账号时收起空的页内容，间距和其它标签对齐。 */
.tab-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tabs.resident-open :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.tabs.resident-open :deep(.el-tabs__content) {
  display: none;
}

.tab-block > :deep(.hm-card) {
  margin-top: 16px;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.attention-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--hm-danger);
  flex: none;
}

.update-btn {
  margin-top: 8px;
}

/* 换壳动画排在按钮右边、不高过按钮，出现和消失都不推动下方内容。 */
.update-row {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 32px;
  margin-top: 8px;
}

.update-row .update-btn {
  margin-top: 0;
}

/* 宽度记为 0 再撑开，窄屏时动画不会把提示条撑宽、改变文字换行。 */
.update-swap {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  white-space: nowrap;
}

.update-swap :deep(.label) {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 红条里的「退回」不用灰字灰框：跟随正文色和边框色，在红底上也看得清。 */
.update-btn.neutral {
  --el-button-text-color: var(--hm-text);
  --el-button-border-color: var(--hm-border);
  --el-button-bg-color: var(--hm-surface);
}

.detail :deep(.hm-card + .hm-card) {
  margin-top: 0;
}
</style>
