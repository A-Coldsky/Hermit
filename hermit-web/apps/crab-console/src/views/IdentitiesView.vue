<script setup lang="ts">
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { notifyError } from '@shared/format'
import { api, type Account, type AuthenticatorView, type ConchSummary, type Identity, type IdentityCheck, type IdentityMember } from '../api'
import { originLabels } from '../labels'

const loading = ref(true)
/** 读取失败和「一个身份都没有」要分开：失败时不能让人以为身份丢了。 */
const loadFailed = ref(false)
const identities = ref<Identity[]>([])
const conches = ref<ConchSummary[]>([])
/** 本机生效的授权器。读不到时不显示这一块，不影响身份。 */
const authenticators = ref<AuthenticatorView[]>([])
const creating = ref(false)
const mutating = ref(false)
const newName = ref('')

let unmounted = false
let loadSeq = 0
let pickSeq = 0
let checkSeq = 0

onBeforeUnmount(() => {
  unmounted = true
})

/** 「添加账号」对话框。先选包和账号，检查通过并看过提示后才能确认。 */
const adding = reactive({
  open: false,
  identity: null as Identity | null,
  conch: '',
  account: '',
  accounts: [] as Account[],
  check: null as IdentityCheck | null,
  checking: false,
  saving: false,
  confirmedDifferent: false,
})

watch(
  () => adding.open,
  (open) => {
    if (!open) {
      pickSeq += 1
      checkSeq += 1
      adding.checking = false
    }
  },
)

/**
 * 能绑定的包：driver: http、未因校验停用，并且生效的这一份不是已经在这个身份里的包。
 * 同名的正式包和本地包是两份，账号各算各的：一份在身份里，切到另一份后还能把另一份绑进来。
 */
const candidates = computed(() => {
  const taken = new Set((adding.identity?.members ?? []).map((member) => `${member.origin}/${member.conch}`))
  return conches.value.filter(
    (conch) =>
      conch.driver !== 'mcp' &&
      conch.usesAuthenticator !== true &&
      conch.ruleDisabled !== true &&
      !taken.has(`${conch.source === 'LOCAL' ? 'LOCAL' : 'OFFICIAL'}/${conch.conch}`),
  )
})

function candidateLabel(conch: ConchSummary): string {
  return conch.title ? `${conch.conch}（${conch.title}）` : conch.conch
}

const credentialText: Record<string, { text: string; type: 'success' | 'warning' | 'info' }> = {
  SAME: { text: '登录名看起来相同', type: 'success' },
  DIFFERENT: { text: '登录名不一样', type: 'warning' },
  UNKNOWN: { text: '比不出登录名是否相同', type: 'info' },
}

const hasDifferentCredentials = computed(() =>
  Boolean(adding.check?.allowed && adding.check.peers.some((peer) => peer.credentials === 'DIFFERENT')),
)

const checkWarnings = computed(() => {
  const check = adding.check
  if (!check?.allowed) return []
  const list = [...check.warnings]
  const selected = conches.value.find((c) => c.conch === adding.conch)
  if (selected && check.peers.length) {
    const selectedOrigin = selected.source === 'LOCAL' ? 'LOCAL' : 'OFFICIAL'
    const mixed = check.peers.some((peer) => peer.origin !== selectedOrigin)
    const alreadyWarned = list.some(
      (warning) => warning.includes('本地') && (warning.includes('正式') || warning.includes('官方') || warning.includes('未签名')),
    )
    if (mixed && !alreadyWarned) {
      list.push('身份里将同时包含本地规则包（未签名）和正式规则包，它们会共用这份 Cookie；请确认本地规则包可信。')
    }
  }
  return list
})

const canConfirmAdd = computed(
  () =>
    !adding.checking &&
    !adding.saving &&
    Boolean(adding.identity && adding.check?.allowed) &&
    (!hasDifferentCredentials.value || adding.confirmedDifferent),
)

async function load() {
  const seq = ++loadSeq
  loading.value = true
  const [listedResult, packagesResult, authResult] = await Promise.allSettled([
    api.identities(),
    api.conches(),
    api.authenticators(),
  ])
  if (unmounted || seq !== loadSeq) return
  if (listedResult.status === 'fulfilled') {
    identities.value = listedResult.value.identities ?? []
    loadFailed.value = false
  } else {
    loadFailed.value = true
    notifyError(listedResult.reason)
  }
  if (packagesResult.status === 'fulfilled') {
    conches.value = packagesResult.value.conches ?? []
  } else if (listedResult.status === 'fulfilled') {
    notifyError(packagesResult.reason)
  }
  if (authResult.status === 'fulfilled') {
    authenticators.value = authResult.value.authenticators ?? []
  }
  loading.value = false
}

const NAME_MAX = 64

/** 名字为什么不能用；能用时是空串。和 Crab 的检查一样按去掉首尾空白之后的长度算。 */
function nameProblem(value: string | null | undefined): string {
  const name = (value ?? '').trim()
  if (!name) return '名字不能为空'
  if (name.length > NAME_MAX) return `名字最多 ${NAME_MAX} 个字符，现在是 ${name.length} 个`
  return ''
}

// 没输入时不提示，只是不能点「新建」。
const newNameProblem = computed(() => (newName.value.trim() ? nameProblem(newName.value) : ''))

async function create() {
  const name = newName.value.trim()
  if (creating.value || nameProblem(name)) return
  creating.value = true
  try {
    await api.createIdentity(name)
    newName.value = ''
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    creating.value = false
  }
}

async function rename(identity: Identity) {
  if (mutating.value) return
  mutating.value = true
  try {
    let name: string
    try {
      const answer = await ElMessageBox.prompt('只是显示用的名字，改名不影响已绑定的账号。', '身份改名', {
        inputValue: identity.name,
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (value) => nameProblem(value) || true,
      })
      name = String(answer.value).trim()
    } catch {
      return
    }
    await api.renameIdentity(identity.id, name)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    mutating.value = false
  }
}

async function remove(identity: Identity) {
  if (mutating.value) return
  mutating.value = true
  try {
    try {
      await ElMessageBox.confirm(
        identity.members.length
          ? `里面的 ${identity.members.length} 个账号会回到各自独立的会话，下次调用各自重新登录。`
          : '这个身份里没有账号。',
        `删除身份「${identity.name}」？`,
        { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    await api.deleteIdentity(identity.id)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    mutating.value = false
  }
}

async function unbind(identity: Identity, member: IdentityMember) {
  if (mutating.value) return
  mutating.value = true
  try {
    try {
      await ElMessageBox.confirm(
        `${member.conch} 的账号 ${member.account} 会回到自己独立的会话，下次调用重新登录。身份里的 Cookie 留给其余账号。`,
        '解除绑定？',
        { confirmButtonText: '解除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    await api.unbindIdentityMember(identity.id, member)
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    mutating.value = false
  }
}

function openAdd(identity: Identity) {
  pickSeq += 1
  checkSeq += 1
  adding.identity = identity
  adding.conch = ''
  adding.account = ''
  adding.accounts = []
  adding.check = null
  adding.checking = false
  adding.confirmedDifferent = false
  adding.open = true
}

async function pickConch() {
  const seq = ++pickSeq
  checkSeq += 1
  adding.account = ''
  adding.accounts = []
  adding.check = null
  adding.checking = false
  adding.confirmedDifferent = false
  const conch = adding.conch
  if (!conch) return
  try {
    const listed = (await api.accounts(conch)).accounts ?? []
    if (unmounted || seq !== pickSeq || !adding.open || adding.conch !== conch) return
    adding.accounts = listed
    if (adding.accounts.length === 1) {
      adding.account = adding.accounts[0].name
      await runCheck()
    }
  } catch (error) {
    if (unmounted || seq !== pickSeq || !adding.open) return
    notifyError(error)
  }
}

async function runCheck() {
  const seq = ++checkSeq
  adding.check = null
  adding.confirmedDifferent = false
  const identityId = adding.identity?.id
  const conch = adding.conch
  const account = adding.account
  if (!identityId || !conch || !account) {
    adding.checking = false
    return
  }
  adding.checking = true
  try {
    const result = await api.checkIdentityMember(identityId, conch, account)
    if (unmounted || seq !== checkSeq || !adding.open) return
    adding.check = result
  } catch (error) {
    if (unmounted || seq !== checkSeq || !adding.open) return
    notifyError(error)
  } finally {
    if (seq === checkSeq) {
      adding.checking = false
    }
  }
}

async function confirmAdd() {
  if (!canConfirmAdd.value || !adding.identity) return
  adding.saving = true
  try {
    await api.bindIdentityMember(adding.identity.id, adding.conch, adding.account)
    ElMessage.success('已绑定，下一次调用开始共用这个身份的 Cookie')
    adding.open = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    adding.saving = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="identities">
    <PageHeader
      title="授权与身份"
      description="两个规则包访问同一个网站或同一个认证中心、用的又是同一个人的登录时，把它们的账号绑到一个身份：共用一份 Cookie，只登录一次，不再互相顶掉。不绑定的账号仍各用各的会话"
    >
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div v-if="authenticators.length" class="hm-card" data-test="authenticators">
      <div class="hm-card-title"><span>授权器</span></div>
      <p class="hm-muted hint">
        授权器是只负责登录的规则包：密码只在授权器账号上填一次，引用它的业务包把账号绑上来，共用这份登录。绑定在各规则包的「账号」里做。绑了授权器的账号不能再加入身份。
      </p>
      <el-table :data="authenticators" empty-text="没有授权器">
        <el-table-column label="授权器" min-width="200">
          <template #default="{ row }">
            <router-link :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono" data-test="authenticator-link">{{ row.conch }}</router-link>
            <el-tag size="small" effect="plain" type="info" class="auth-tag">{{ row.source === 'LOCAL' ? '本地包' : '正式包' }}</el-tag>
            <div class="hm-muted">{{ row.title }} · {{ row.version }}</div>
          </template>
        </el-table-column>
        <el-table-column label="认证地址" min-width="220">
          <template #default="{ row }">
            <div v-for="origin in row.origins" :key="origin" class="mono">{{ origin }}</div>
            <div v-if="row.allowHosts.length" class="hm-muted">只允许用在：{{ row.allowHosts.join('、') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="账号和使用它的规则包" min-width="280">
          <template #default="{ row }">
            <div v-for="account in row.accounts" :key="account.name" data-test="authenticator-account">
              <span class="mono">{{ account.name }}</span>：
              <template v-if="account.consumers.length">
                <template v-for="(c, index) in account.consumers" :key="`${c.origin}/${c.conch}/${c.account}`">
                  <template v-if="index">、</template>
                  <router-link :to="`/conches/${encodeURIComponent(c.conch)}`" class="mono">{{ c.conch }} / {{ c.account }}</router-link>
                </template>
              </template>
              <span v-else class="hm-muted">还没有规则包绑定</span>
            </div>
            <span v-if="!row.accounts.length" class="hm-muted">还没有账号</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="hm-card">
      <div class="hm-card-title"><span>新建身份</span></div>
      <div class="create-row">
        <el-input
          v-model="newName"
          placeholder="例如：OA · 张三"
          aria-label="身份名字"
          :aria-invalid="newNameProblem ? 'true' : undefined"
          data-test="identity-name"
          @keyup.enter="create"
        />
        <el-button type="primary" :icon="Plus" :loading="creating" :disabled="creating || !!nameProblem(newName)" data-test="identity-create" @click="create">
          新建
        </el-button>
      </div>
      <p v-if="newNameProblem" class="name-problem" role="alert" data-test="identity-name-problem">{{ newNameProblem }}</p>
      <ul class="hm-muted hint">
        <li>一个身份代表一个人。两个人的账号不要绑到同一个身份，否则会用错的人的登录去操作。</li>
        <li>每个包仍用自己填的凭证和自己的登录步骤；凭证、登录返回的值、验证码各包分开保存。</li>
        <li>同一个身份里的调用排队执行。修改或测试其中一个账号的凭证会清空这个身份的 Cookie，各账号下次调用重新登录。</li>
      </ul>
    </div>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      :title="identities.length ? '身份列表刷新失败，下面是上一次读到的内容' : '身份列表读取失败'"
      data-test="identities-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <p v-if="!loading && !loadFailed && !identities.length" class="hm-muted empty" data-test="identity-empty">还没有身份。</p>

    <div v-for="identity in identities" :key="identity.id" class="hm-card" data-test="identity-card">
      <div class="hm-card-title">
        <span class="name">{{ identity.name }}</span>
        <span class="actions">
          <el-button link :icon="Edit" :disabled="mutating" :aria-label="`${identity.name} 改名`" @click="rename(identity)">改名</el-button>
          <el-button link type="primary" :icon="Plus" :disabled="mutating" data-test="identity-add" @click="openAdd(identity)">添加账号</el-button>
          <el-button link type="danger" :icon="Delete" :disabled="mutating" data-test="identity-delete" @click="remove(identity)">删除</el-button>
        </span>
      </div>
      <el-alert
        v-if="identity.lastCleared"
        type="warning"
        :closable="false"
        show-icon
        class="cleared"
        data-test="identity-cleared"
      >
        <template #title>
          <TimeText :value="identity.lastCleared.at" />：{{ identity.lastCleared.conch }} 的账号
          {{ identity.lastCleared.account }} 带着已有的 Cookie 登录没有成功，或登录后马上又被判为会话失效，这个身份的 Cookie 被清空，各账号重新登录了一次。
          经常出现说明这个包的登录步骤只能从空白状态跑通，或者它把业务错误当成了会话失效，共用的好处有限。
        </template>
      </el-alert>
      <el-table :data="identity.members" empty-text="还没有账号。添加两个以上才有共用的效果">
        <el-table-column label="规则包" min-width="200">
          <template #default="{ row }">
            <router-link v-if="row.installed" :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono">{{ row.conch }}</router-link>
            <span v-else class="mono">{{ row.conch }}</span>
            <el-tag v-if="row.origin === 'LOCAL'" size="small" effect="plain" class="tag">{{ originLabels.LOCAL ?? '本地' }}</el-tag>
            <el-tag v-if="!row.installed" size="small" type="info" effect="plain" class="tag">不在本机</el-tag>
            <el-tag v-else-if="!row.active" size="small" type="info" effect="plain" class="tag">未生效</el-tag>
            <div v-if="row.title" class="hm-muted note">{{ row.title }}</div>
          </template>
        </el-table-column>
        <el-table-column label="账号" min-width="120">
          <template #default="{ row }"><span class="mono">{{ row.account }}</span></template>
        </el-table-column>
        <el-table-column label="访问的地址" min-width="260">
          <template #default="{ row }">
            <div v-for="address in row.addresses" :key="address" class="mono address">{{ address }}</div>
            <span v-if="!row.addresses.length" class="hm-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="90" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="mutating" data-test="identity-unbind" @click="unbind(identity, row)">解除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="adding.open" :title="`添加账号到「${adding.identity?.name ?? ''}」`" width="560px">
      <div class="add-form">
        <label class="field">
          <span class="hm-muted">规则包</span>
          <el-select v-model="adding.conch" filterable placeholder="选择规则包" data-test="identity-conch" @change="pickConch">
            <el-option
              v-for="conch in candidates"
              :key="`${conch.source}/${conch.conch}`"
              :value="conch.conch"
              :label="candidateLabel(conch)"
            >
              <span>{{ candidateLabel(conch) }}</span>
              <el-tag v-if="conch.source === 'LOCAL'" size="small" effect="plain" class="tag" data-test="candidate-local-tag">
                {{ originLabels.LOCAL ?? '本地' }}
              </el-tag>
            </el-option>
          </el-select>
        </label>
        <label class="field">
          <span class="hm-muted">账号</span>
          <el-select v-model="adding.account" :disabled="!adding.conch" placeholder="选择账号" data-test="identity-account" @change="runCheck">
            <el-option v-for="account in adding.accounts" :key="account.name" :value="account.name" :label="account.name" />
          </el-select>
        </label>
      </div>
      <div v-loading="adding.checking" class="check" data-test="identity-check">
        <template v-if="adding.check">
          <el-alert v-if="!adding.check.allowed" type="error" :closable="false" show-icon :title="adding.check.problem ?? '不能绑定'" />
          <template v-else>
            <p v-if="!adding.check.peers.length" class="hm-muted">这是这个身份的第一个账号。</p>
            <div v-for="peer in adding.check.peers" :key="`${peer.origin}/${peer.conch}/${peer.account}`" class="peer" data-test="identity-peer">
              <span class="mono">{{ peer.conch }} / {{ peer.account }}</span>
              <el-tag v-if="peer.origin === 'LOCAL'" size="small" effect="plain" class="tag" data-test="peer-local-tag">
                {{ originLabels.LOCAL ?? '本地' }}
              </el-tag>
              <el-tag size="small" effect="plain" :type="credentialText[peer.credentials].type" class="tag">
                {{ credentialText[peer.credentials].text }}
              </el-tag>
              <div class="hm-muted note">
                {{ peer.addresses.length ? `相同的地址：${peer.addresses.join('、')}` : '通过身份里的其他成员间接关联' }}
              </div>
            </div>
            <el-alert
              v-for="warning in checkWarnings"
              :key="warning"
              type="warning"
              :closable="false"
              show-icon
              :title="warning"
              class="warning"
            />
            <el-checkbox
              v-if="hasDifferentCredentials"
              v-model="adding.confirmedDifferent"
              class="confirm-different"
              data-test="identity-confirm-different"
            >
              我已确认两边确实是同一个人的账号，不同登录名不会导致串号
            </el-checkbox>
            <p class="hm-muted note">只比较文本类型的凭证（登录名通常是这类），不比较也不显示密码。请自己确认两边是同一个人。</p>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="adding.open = false">取消</el-button>
        <el-button
          :type="hasDifferentCredentials ? 'warning' : 'primary'"
          :loading="adding.saving"
          :disabled="!canConfirmAdd"
          data-test="identity-confirm"
          @click="confirmAdd"
        >
          确认是同一个人，绑定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.identities {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.identities .hm-card + .hm-card {
  margin-top: 0;
}

.create-row {
  display: flex;
  gap: 12px;
  max-width: 480px;
}

.name-problem {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--el-color-danger);
}

/* 名字可以是 64 个不带空格的字符：在卡片里折行，不把页面撑宽。 */
.name {
  min-width: 0;
  overflow-wrap: anywhere;
}

.hint {
  margin: 12px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.7;
}

.empty {
  margin: 0;
}

.actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.cleared {
  margin-bottom: 12px;
}

.tag {
  margin-left: 6px;
}

.note {
  font-size: 12.5px;
  margin-top: 2px;
}

.address {
  word-break: break-all;
}

.add-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
}

.check {
  min-height: 48px;
  margin-top: 16px;
}

.peer {
  margin-bottom: 10px;
}

.warning {
  margin-bottom: 8px;
}

.confirm-different {
  display: flex;
  height: auto;
  white-space: normal;
  line-height: 1.5;
  margin: 10px 0 6px;
}

@media (max-width: 640px) {
  .add-form {
    grid-template-columns: 1fr;
  }
}

.auth-tag {
  margin-left: 6px;
}
</style>
