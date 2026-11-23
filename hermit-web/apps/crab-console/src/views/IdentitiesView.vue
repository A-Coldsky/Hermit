<script setup lang="ts">
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { notifyError } from '@shared/format'
import { api, type Account, type ConchSummary, type Identity, type IdentityCheck, type IdentityMember } from '../api'
import { originLabels } from '../labels'

const loading = ref(true)
const identities = ref<Identity[]>([])
const conches = ref<ConchSummary[]>([])
const creating = ref(false)
const newName = ref('')

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
})

/** 能绑定的包：driver: http，并且不是已经在这个身份里的包。 */
const candidates = computed(() => {
  const taken = new Set((adding.identity?.members ?? []).map((member) => member.conch))
  return conches.value.filter((conch) => conch.driver !== 'mcp' && !taken.has(conch.conch))
})

const credentialText: Record<string, { text: string; type: 'success' | 'warning' | 'info' }> = {
  SAME: { text: '登录名看起来相同', type: 'success' },
  DIFFERENT: { text: '登录名不一样', type: 'warning' },
  UNKNOWN: { text: '比不出登录名是否相同', type: 'info' },
}

async function load() {
  loading.value = true
  try {
    const [listed, packages] = await Promise.all([api.identities(), api.conches()])
    identities.value = listed.identities ?? []
    conches.value = packages.conches ?? []
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = newName.value.trim()
  if (!name) return
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
  let name: string
  try {
    const answer = await ElMessageBox.prompt('只是显示用的名字，改名不影响已绑定的账号。', '身份改名', {
      inputValue: identity.name,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (value) => (value && value.trim() ? true : '名字不能为空'),
    })
    name = String(answer.value).trim()
  } catch {
    return
  }
  try {
    await api.renameIdentity(identity.id, name)
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function remove(identity: Identity) {
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
  try {
    await api.deleteIdentity(identity.id)
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function unbind(identity: Identity, member: IdentityMember) {
  try {
    await ElMessageBox.confirm(
      `${member.conch} 的账号 ${member.account} 会回到自己独立的会话，下次调用重新登录。身份里的 Cookie 留给其余账号。`,
      '解除绑定？',
      { confirmButtonText: '解除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await api.unbindIdentityMember(identity.id, member)
    await load()
  } catch (error) {
    notifyError(error)
  }
}

function openAdd(identity: Identity) {
  adding.identity = identity
  adding.conch = ''
  adding.account = ''
  adding.accounts = []
  adding.check = null
  adding.open = true
}

async function pickConch() {
  adding.account = ''
  adding.accounts = []
  adding.check = null
  if (!adding.conch) return
  try {
    adding.accounts = (await api.accounts(adding.conch)).accounts ?? []
    if (adding.accounts.length === 1) {
      adding.account = adding.accounts[0].name
      await runCheck()
    }
  } catch (error) {
    notifyError(error)
  }
}

async function runCheck() {
  adding.check = null
  if (!adding.identity || !adding.conch || !adding.account) return
  adding.checking = true
  try {
    adding.check = await api.checkIdentityMember(adding.identity.id, adding.conch, adding.account)
  } catch (error) {
    notifyError(error)
  } finally {
    adding.checking = false
  }
}

async function confirmAdd() {
  if (!adding.identity || !adding.check?.allowed) return
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
      title="身份"
      description="两个规则包访问同一个网站或同一个认证中心、用的又是同一个人的登录时，把它们的账号绑到一个身份：共用一份 Cookie，只登录一次，不再互相顶掉。不绑定的账号仍各用各的会话"
    >
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-card-title"><span>新建身份</span></div>
      <div class="create-row">
        <el-input
          v-model="newName"
          maxlength="64"
          placeholder="例如：OA · 张三"
          data-test="identity-name"
          @keyup.enter="create"
        />
        <el-button type="primary" :icon="Plus" :loading="creating" :disabled="!newName.trim()" data-test="identity-create" @click="create">
          新建
        </el-button>
      </div>
      <ul class="hm-muted hint">
        <li>一个身份代表一个人。两个人的账号不要绑到同一个身份，否则会用错的人的登录去操作。</li>
        <li>每个包仍用自己填的凭证和自己的登录步骤；凭证、登录返回的值、验证码各包分开保存。</li>
        <li>同一个身份里的调用排队执行。修改或测试其中一个账号的凭证会清空这个身份的 Cookie，各账号下次调用重新登录。</li>
      </ul>
    </div>

    <p v-if="!loading && !identities.length" class="hm-muted empty" data-test="identity-empty">还没有身份。</p>

    <div v-for="identity in identities" :key="identity.id" class="hm-card" data-test="identity-card">
      <div class="hm-card-title">
        <span>{{ identity.name }}</span>
        <span class="actions">
          <el-button link :icon="Edit" :aria-label="`${identity.name} 改名`" @click="rename(identity)">改名</el-button>
          <el-button link type="primary" :icon="Plus" data-test="identity-add" @click="openAdd(identity)">添加账号</el-button>
          <el-button link type="danger" :icon="Delete" data-test="identity-delete" @click="remove(identity)">删除</el-button>
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
          {{ identity.lastCleared.account }} 带着已有的 Cookie 登录没有成功，这个身份的 Cookie 被清空，各账号重新登录了一次。
          经常出现说明这个包的登录步骤只能从空白状态跑通，共用的好处有限。
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
            <el-button link type="danger" data-test="identity-unbind" @click="unbind(identity, row)">解除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="adding.open" :title="`添加账号到「${adding.identity?.name ?? ''}」`" width="560px">
      <div class="add-form">
        <label class="field">
          <span class="hm-muted">规则包</span>
          <el-select v-model="adding.conch" filterable placeholder="选择规则包" data-test="identity-conch" @change="pickConch">
            <el-option v-for="conch in candidates" :key="conch.conch" :value="conch.conch" :label="`${conch.conch}（${conch.title}）`" />
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
            <div v-for="peer in adding.check.peers" :key="`${peer.origin}/${peer.conch}/${peer.account}`" class="peer">
              <span class="mono">{{ peer.conch }} / {{ peer.account }}</span>
              <el-tag size="small" effect="plain" :type="credentialText[peer.credentials].type" class="tag">
                {{ credentialText[peer.credentials].text }}
              </el-tag>
              <div class="hm-muted note">相同的地址：{{ peer.addresses.join('、') }}</div>
            </div>
            <el-alert
              v-for="warning in adding.check.warnings"
              :key="warning"
              type="warning"
              :closable="false"
              show-icon
              :title="warning"
              class="warning"
            />
            <p class="hm-muted note">只比较文本类型的凭证（登录名通常是这类），不比较也不显示密码。请自己确认两边是同一个人。</p>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="adding.open = false">取消</el-button>
        <el-button
          type="primary"
          :loading="adding.saving"
          :disabled="!adding.check?.allowed"
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

@media (max-width: 640px) {
  .add-form {
    grid-template-columns: 1fr;
  }
}
</style>
