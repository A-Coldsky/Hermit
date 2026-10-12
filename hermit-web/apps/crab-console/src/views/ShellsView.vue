<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import { notifyError } from '@shared/format'
import { api, type ShellSummary } from '../api'
import { refreshPending } from '../status'

const router = useRouter()
const loading = ref(false)
const shells = ref<ShellSummary[]>([])

async function load() {
  loading.value = true
  try {
    shells.value = (await api.shells()).shells
    void refreshPending()
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
    <PageHeader title="Shell 与账号" description="已安装到本机的 Shell。为每个 Shell 建账号、填凭证和配置后即可调用">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <el-table
        v-loading="loading"
        :data="shells"
        empty-text="还没有 Shell。到概览页从 Reef 同步，或把 YAML 放进本地 Shell 目录"
        row-class-name="clickable"
        @row-click="open"
      >
        <el-table-column label="Shell" min-width="220">
          <template #default="{ row }">
            <div class="name">
              <strong>{{ row.title || row.shell }}</strong>
              <span class="mono hm-muted">{{ row.shell }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="120">
          <template #default="{ row }"><span class="mono">v{{ row.version }}</span></template>
        </el-table-column>
        <el-table-column prop="capabilityCount" label="能力数" width="90" align="right" />
        <el-table-column prop="accountCount" label="账号数" width="90" align="right" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.needsConfiguration" type="warning" effect="plain">待配置</el-tag>
            <el-tag v-else-if="!row.accountCount" type="info" effect="plain">未建账号</el-tag>
            <el-tag v-else type="success" effect="plain">可用</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.name .mono {
  font-size: 12px;
}

:deep(.clickable) {
  cursor: pointer;
}
</style>
