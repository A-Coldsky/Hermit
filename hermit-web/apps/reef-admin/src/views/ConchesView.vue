<script setup lang="ts">
import { isAdmin } from '../auth'
import { ArrowRight, Grid, List, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ConchAvatar from '@shared/components/ConchAvatar.vue'
import { formatTime, notifyError, relativeTime } from '@shared/format'
import { api, type ConchSummary } from '../api'

const router = useRouter()
const loading = ref(true)
const conches = ref<ConchSummary[]>([])
const keyword = ref('')
const mode = ref<'cards' | 'table'>(localStorage.getItem('hermit.reef.conchesView') === 'table' ? 'table' : 'cards')
watch(mode, (value) => localStorage.setItem('hermit.reef.conchesView', value))

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) {
    return conches.value
  }
  return conches.value.filter((s) =>
    [s.conch, s.title, s.description, s.owner ?? '', ...s.tags].some((v) => v.toLowerCase().includes(q)),
  )
})

async function load() {
  loading.value = true
  try {
    conches.value = await api.conches()
  } catch (error) {
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
    </PageHeader>

    <div class="hm-toolbar bar">
      <el-input v-model="keyword" :prefix-icon="Search" placeholder="按名称、标题、负责人或标签筛选" clearable class="search" />
      <span class="spacer" />
      <span class="hm-muted">共 {{ filtered.length }} 个</span>
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="cards" aria-label="卡片视图"><el-icon><Grid /></el-icon></el-radio-button>
        <el-radio-button value="table" aria-label="列表视图"><el-icon><List /></el-icon></el-radio-button>
      </el-radio-group>
    </div>

    <template v-if="mode === 'cards'">
      <div v-if="loading && !conches.length" class="cards">
        <div v-for="n in 3" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
      </div>
      <div v-else-if="filtered.length" class="cards">
        <RouterLink v-for="row in filtered" :key="row.conch" :to="`/conches/${encodeURIComponent(row.conch)}`" class="hm-card conch" :class="{ off: row.disabled }">
          <div class="top">
            <ConchAvatar :name="row.conch" :title="row.title" />
            <div class="name">
              <strong>{{ row.title || row.conch }}</strong>
              <span class="mono hm-muted">{{ row.conch }}</span>
            </div>
            <el-tag v-if="row.disabled" type="danger" effect="light" round>已停用</el-tag>
            <el-tag v-if="row.version" :type="row.disabled ? 'info' : 'success'" effect="light" round class="mono">v{{ row.version }}</el-tag>
            <el-tag v-else type="info" effect="light" round>未发布</el-tag>
          </div>
          <p class="desc">{{ row.description || '没有描述' }}</p>
          <div class="tags">
            <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain" round>{{ tag }}</el-tag>
          </div>
          <div class="foot hm-muted">
            <span><b class="num">{{ row.capabilityCount }}</b> 个能力</span>
            <span title="Crab 成功下载规则包的次数，各版本合计"><b class="num">{{ row.pullCount }}</b> 次拉取</span>
            <span>{{ row.owner ?? '未填负责人' }}</span>
            <span class="spacer" />
            <span>{{ row.publishedAt ? `${relativeTime(row.publishedAt)}发布` : '' }}</span>
            <el-icon class="go"><ArrowRight /></el-icon>
          </div>
        </RouterLink>
      </div>
      <div v-else-if="!loading" class="hm-card empty">
        <template v-if="conches.length">没有匹配「{{ keyword }}」的规则包</template>
        <template v-else>
          <span>{{ isAdmin ? '还没有规则包，先上传一个 YAML' : '还没有规则包，等管理员上传' }}</span>
          <el-button v-if="isAdmin" type="primary" :icon="Upload" @click="router.push('/conches/upload')">上传规则包</el-button>
        </template>
      </div>
    </template>

    <div v-else class="hm-card">
      <el-table v-loading="loading" :data="filtered" row-class-name="clickable" @row-click="open">
        <template #empty>{{ loading ? '' : '还没有规则包，先上传一个 YAML' }}</template>
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
        <el-table-column label="当前版本" width="190">
          <template #default="{ row }">
            <el-tag v-if="row.version" :type="row.disabled ? 'info' : 'success'" effect="plain" class="mono">v{{ row.version }}</el-tag>
            <el-tag v-else type="info" effect="plain">未发布</el-tag>
            <el-tag v-if="row.disabled" type="danger" effect="plain" class="off">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capabilityCount" label="能力数" width="90" align="right" />
        <el-table-column prop="pullCount" label="拉取次数" width="100" align="right" />
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
.search {
  max-width: 340px;
}

.bar {
  margin-bottom: 16px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.conch {
  display: flex;
  flex-direction: column;
  gap: 12px;
  color: inherit;
}

.top {
  display: flex;
  align-items: center;
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

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 22px;
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
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
