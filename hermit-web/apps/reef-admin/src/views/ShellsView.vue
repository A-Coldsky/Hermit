<script setup lang="ts">
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { formatTime, notifyError } from '@shared/format'
import { api, type ShellSummary } from '../api'

const router = useRouter()
const loading = ref(false)
const shells = ref<ShellSummary[]>([])
const keyword = ref('')

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) {
    return shells.value
  }
  return shells.value.filter((s) =>
    [s.shell, s.title, s.description, s.owner ?? '', ...s.tags].some((v) => v.toLowerCase().includes(q)),
  )
})

async function load() {
  loading.value = true
  try {
    shells.value = await api.shells()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function open(row: ShellSummary) {
  void router.push(`/shells/${encodeURIComponent(row.shell)}`)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="Shell 目录" description="每个 Shell 描述一个老系统的登录方式与可调用能力">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Upload" @click="router.push('/shells/upload')">上传 Shell</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-toolbar">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按名称、标题、负责人或标签筛选" clearable class="search" />
        <span class="spacer" />
        <span class="hm-muted">共 {{ filtered.length }} 个</span>
      </div>
      <el-table v-loading="loading" :data="filtered" empty-text="还没有 Shell，先上传一个 YAML" row-class-name="clickable" @row-click="open">
        <el-table-column label="Shell" min-width="220">
          <template #default="{ row }">
            <div class="name">
              <strong>{{ row.title || row.shell }}</strong>
              <span class="mono hm-muted">{{ row.shell }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.version" type="success" effect="plain" class="mono">v{{ row.version }}</el-tag>
            <el-tag v-else type="info" effect="plain">未发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capabilityCount" label="能力数" width="90" align="right" />
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
</style>
