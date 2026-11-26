<script setup lang="ts">
import { isAdmin } from '../auth'
import { ArrowRight, Grid, List, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import KindShell from '@shared/components/KindShell.vue'
import HermitMark from '@shared/components/HermitMark.vue'
import { formatTime, notifyError, relativeTime } from '@shared/format'
import { api, type ConchSummary } from '../api'
import DriverBadge from '../components/DriverBadge.vue'
import { isMcp, pullCountHint, upstreamHost } from '../labels'

const router = useRouter()
const loading = ref(true)
/** 读取失败和目录为空要分开：失败时不能让人以为规则包没了。 */
const loadFailed = ref(false)
const conches = ref<ConchSummary[]>([])
const keyword = ref('')
const kind = ref<'all' | 'mcp' | 'http'>('all')
const mode = ref<'cards' | 'table'>(localStorage.getItem('hermit.reef.conchesView') === 'table' ? 'table' : 'cards')
watch(mode, (value) => localStorage.setItem('hermit.reef.conchesView', value))

/** 页签：可调用的规则包，或只负责登录的授权器。类型筛选只在规则包页签里。 */
const tab = ref<'packages' | 'authenticators'>('packages')
const isAuthenticator = (s: ConchSummary) => s.driver === 'auth'
const packages = computed(() => conches.value.filter((s) => !isAuthenticator(s)))
const authenticators = computed(() => conches.value.filter(isAuthenticator))
/** 引用这个授权器的规则包。 */
function consumersOf(name: string): string[] {
  return packages.value.filter((s) => s.authUse === name).map((s) => s.conch)
}

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const byKind = tab.value === 'authenticators'
    ? authenticators.value
    : kind.value === 'all' ? packages.value : packages.value.filter((s) => isMcp(s) === (kind.value === 'mcp'))
  if (!q) {
    return byKind
  }
  return byKind.filter((s) =>
    [s.conch, s.title, s.description, s.owner ?? '', upstreamHost(s.upstream?.url) ?? '', ...s.tags].some((v) =>
      v.toLowerCase().includes(q),
    ),
  )
})

/** 目录本身为空才提示上传；筛选零匹配用另一句，两种视图共用。 */
const emptyText = computed(() => {
  if (loadFailed.value && !conches.value.length) {
    return '没有读到规则包目录'
  }
  if (!conches.value.length) {
    return isAdmin.value ? '还没有规则包，先上传一个 YAML' : '还没有规则包，可以点击右上角「提交规则包」提交审批'
  }
  if (tab.value === 'authenticators') {
    return keyword.value.trim() ? `没有匹配「${keyword.value}」的授权器` : '还没有授权器'
  }
  if (kind.value === 'all') {
    return keyword.value.trim() ? `没有匹配「${keyword.value}」的规则包` : '还没有规则包，现有的都是授权器'
  }
  return `没有符合筛选条件的${kind.value === 'mcp' ? '原生 MCP 规则包' : 'HTTP 规则包'}`
})

async function load() {
  loading.value = true
  try {
    conches.value = await api.conches()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function open(row: ConchSummary) {
  void router.push(`/conches/${encodeURIComponent(row.conch)}`)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="规则包目录" description="每个规则包描述一个老系统的登录方式与可调用能力">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button v-if="isAdmin" type="primary" :icon="Upload" @click="router.push('/conches/upload')">上传规则包</el-button>
      <el-button v-else type="primary" :icon="Upload" data-test="user-submit-btn" @click="router.push('/submissions')">提交规则包</el-button>
    </PageHeader>

    <el-radio-group v-model="tab" class="tabs" data-test="kind-tabs">
      <el-radio-button value="packages">规则包 {{ packages.length }}</el-radio-button>
      <el-radio-button value="authenticators">授权器 {{ authenticators.length }}</el-radio-button>
    </el-radio-group>

    <div class="hm-toolbar bar">
      <el-input v-model="keyword" :prefix-icon="Search" placeholder="按名称、标题、负责人或标签筛选" clearable class="search" />
      <el-radio-group v-if="tab === 'packages'" v-model="kind" size="small" data-test="kind-filter">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="mcp">原生 MCP</el-radio-button>
        <el-radio-button value="http">HTTP 规则</el-radio-button>
      </el-radio-group>
      <span class="spacer" />
      <span v-if="!loading && !(loadFailed && !conches.length)" class="hm-muted" data-test="conch-count">共 {{ filtered.length }} 个</span>
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="cards" aria-label="卡片视图"><el-icon><Grid /></el-icon></el-radio-button>
        <el-radio-button value="table" aria-label="列表视图"><el-icon><List /></el-icon></el-radio-button>
      </el-radio-group>
    </div>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="load-failed"
      :title="conches.length ? '规则包目录刷新失败，下面是上一次读到的内容' : '规则包目录读取失败'"
      data-test="catalog-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <template v-if="loading && !conches.length">
      <div v-if="mode === 'cards'" data-test="catalog-loading">
        <p class="loading-label">加载中</p>
        <div class="cards">
          <div v-for="n in 3" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
        </div>
      </div>
      <div v-else class="hm-card" data-test="catalog-loading">
        <p class="loading-label">加载中</p>
        <el-skeleton :rows="4" animated />
      </div>
    </template>

    <template v-else-if="mode === 'cards'">
      <div v-if="filtered.length" class="cards">
        <RouterLink v-for="row in filtered" :key="row.conch" :to="`/conches/${encodeURIComponent(row.conch)}`" class="hm-card conch" :class="{ off: row.disabled }">
          <div class="top">
            <ConchAvatar :name="row.conch" :title="row.title" />
            <div class="name">
              <strong>{{ row.title || row.conch }}</strong>
              <span class="mono hm-muted">{{ row.conch }}</span>
            </div>
            <el-tag v-if="row.disabled" type="warning" effect="light" round>已停用</el-tag>
            <el-tag v-if="row.version" :type="row.disabled ? 'info' : 'success'" effect="light" round class="mono">v{{ row.version }}</el-tag>
            <el-tag v-else type="info" effect="light" round>未发布</el-tag>
          </div>
          <div class="kind">
            <DriverBadge :driver="row.driver" />
            <span v-if="isMcp(row)" class="mono hm-muted host" data-test="upstream-host">{{ upstreamHost(row.upstream?.url) ?? '上游地址未填' }}</span>
            <span v-if="row.authUse" class="mono hm-muted host" data-test="auth-use">授权器：{{ row.authUse }}</span>
            <span v-if="row.driver === 'auth' && consumersOf(row.conch).length" class="mono hm-muted host" data-test="used-by">被引用：{{ consumersOf(row.conch).join('、') }}</span>
          </div>
          <p class="desc">{{ row.description || '没有描述' }}</p>
          <div class="tags">
            <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain" round>{{ tag }}</el-tag>
          </div>
          <KindShell :kind="row.driver" :size="56" class="hm-kind-water" aria-hidden="true" />
          <div class="foot hm-muted">
            <span><b class="num">{{ row.capabilityCount }}</b> 个{{ isMcp(row) ? '工具' : '能力' }}</span>
            <span :title="`${pullCountHint}，各版本合计`">拉取次数 <b class="num">{{ row.pullCount }}</b></span>
            <span>{{ row.owner ?? '未填负责人' }}</span>
            <span class="spacer" />
            <span>{{ row.publishedAt ? `${relativeTime(row.publishedAt)}发布` : '' }}</span>
            <el-icon class="go"><ArrowRight /></el-icon>
          </div>
        </RouterLink>
      </div>
      <div v-else-if="!loading" class="hm-card empty">
        <HermitMark variant="reef" :size="56" :tile="false" />
        <span>{{ emptyText }}</span>
        <el-button v-if="isAdmin && !conches.length && !loadFailed" type="primary" :icon="Upload" @click="router.push('/conches/upload')">上传规则包</el-button>
      </div>
    </template>

    <div v-else class="hm-card">
      <el-table v-loading="loading" :data="filtered" row-class-name="clickable" @row-click="open">
        <template #empty>{{ emptyText }}</template>
        <el-table-column label="规则包" min-width="220">
          <template #default="{ row }">
            <div class="row-name">
              <ConchAvatar :name="row.conch" :title="row.title" :size="32" />
              <div class="name">
                <strong>{{ row.title || row.conch }}</strong>
                <span class="mono hm-muted">{{ row.conch }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="230">
          <template #default="{ row }">
            <DriverBadge :driver="row.driver" />
            <div v-if="isMcp(row)" class="mono hm-muted host">{{ upstreamHost(row.upstream?.url) ?? '上游地址未填' }}</div>
            <div v-if="row.driver === 'auth' && consumersOf(row.conch).length" class="mono hm-muted host">被引用：{{ consumersOf(row.conch).join('、') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="190">
          <template #default="{ row }">
            <el-tag v-if="row.version" :type="row.disabled ? 'info' : 'success'" effect="plain" class="mono">v{{ row.version }}</el-tag>
            <el-tag v-else type="info" effect="plain">未发布</el-tag>
            <el-tag v-if="row.disabled" type="warning" effect="plain" class="off">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capabilityCount" label="能力/工具数" width="110" align="right" />
        <el-table-column prop="pullCount" label="拉取次数" width="100" align="right">
          <template #header><span :title="`${pullCountHint}，各版本合计`">拉取次数</span></template>
        </el-table-column>
        <el-table-column label="负责人" width="140">
          <template #default="{ row }">{{ row.owner ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="tag in row.tags" :key="tag" size="small" class="tag">{{ tag }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="180">
          <template #default="{ row }">{{ formatTime(row.publishedAt) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  margin-bottom: 12px;
}

.tabs :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.load-failed {
  margin-bottom: 12px;
}
.search {
  max-width: 340px;
}

.bar {
  margin-bottom: 16px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr));
  gap: 16px;
}

/*
 * 卡片分五个区：标题、类型行、描述、标签、底部信息。支持 subgrid 时，同一排卡片的每个区等高，
 * 各区的起始位置横向对齐；类型水印在描述和标签右边，靠下。
 */
.conch {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-content: start;
  color: inherit;
}

.conch > * {
  grid-column: 1 / -1;
  min-width: 0;
}

.conch > .desc,
.conch > .tags {
  grid-column: 1;
}

.conch > .tags {
  align-content: flex-start;
}

.conch > .hm-kind-water {
  grid-area: 3 / 2 / span 2 / auto;
  align-self: end;
}

@supports (grid-template-rows: subgrid) {
  .conch {
    grid-row: span 5;
    grid-template-rows: subgrid;
  }
}

.top {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.top .name {
  flex: 1;
  min-width: 0;
}

.top strong {
  font-size: 15px;
}

.desc {
  margin: 0;
  color: var(--hm-muted);
  line-height: 1.6;
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 42px;
}

.kind {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 卡片里类型行放不下时，上游地址或授权器名折到徽标下面一行，不截断成省略号。 */
.conch .kind {
  flex-wrap: wrap;
  gap: 4px 8px;
}

.host {
  max-width: 100%;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 22px;
}

.foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hm-border);
  font-size: 12.5px;
}

.foot b {
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.go {
  color: var(--el-color-primary);
  transition: transform 0.15s;
}

.conch.off {
  background: var(--hm-subtle);
  opacity: 0.78;
}

.conch:hover .go {
  transform: translateX(3px);
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 48px 20px;
  color: var(--hm-muted);
}

.loading-label {
  margin: 0 0 12px;
  color: var(--hm-muted);
}

.row-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.name .mono {
  font-size: 12px;
}

.tag {
  margin-right: 4px;
}

:deep(.clickable) {
  cursor: pointer;
}

.off {
  margin-left: 6px;
}
</style>
