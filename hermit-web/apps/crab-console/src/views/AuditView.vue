<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { notifyError } from '@shared/format'
import { api, type ConfigAuditPage } from '../api'
import { actorLabels, auditFieldText, configAuditLabels, originLabels } from '../labels'

const loading = ref(true)
const loaded = ref(false)
const query = reactive({ conch: '', account: '', page: 1, limit: 50 })
const result = ref<ConfigAuditPage>({ entries: [], page: 1, limit: 50, total: 0 })

async function load() {
  loading.value = true
  try {
    result.value = await api.audit({
      conch: query.conch.trim() || undefined,
      account: query.account.trim() || undefined,
      page: query.page,
      limit: query.limit,
    })
    loaded.value = true
  } catch (error) {
    loaded.value = false
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

function onPage(page: number) {
  query.page = page
  void load()
}

function onSize(size: number) {
  query.limit = size
  query.page = 1
  void load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="配置审计" description="本机账号、凭证、配置、API Key 和能力开关的变更记录。只记字段名，不记凭证或配置的值；保留 180 天">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <div class="hm-card">
      <div class="hm-toolbar">
        <el-input v-model="query.conch" placeholder="按规则包筛选" clearable class="search" @keyup.enter="search" @clear="search" />
        <el-input v-model="query.account" placeholder="按账号筛选" clearable class="search" @keyup.enter="search" @clear="search" />
        <el-button :icon="Search" @click="search">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="result.entries">
        <template #empty>{{ loading || !loaded ? '' : '没有记录' }}</template>
        <el-table-column label="时间" width="230">
          <template #default="{ row }"><TimeText :value="row.at" /></template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ actorLabels[row.actor] ?? row.actor }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-tag :type="configAuditLabels[row.action]?.tone ?? 'info'" effect="plain" size="small">
              {{ configAuditLabels[row.action]?.text ?? row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="规则包 / 账号" min-width="200">
          <template #default="{ row }">
            <template v-if="row.conch">
              <router-link :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono">{{ row.conch }}</router-link>
              <span v-if="row.account" class="mono hm-muted"> / {{ row.account }}</span>
              <el-tag v-if="row.origin && row.origin !== 'OFFICIAL'" size="small" effect="plain" class="origin">
                {{ originLabels[row.origin] ?? row.origin }}
              </el-tag>
            </template>
            <span v-else class="hm-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="涉及字段" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.fields.length" class="mono">{{ row.fields.map((field: string) => auditFieldText(row.action, field)).join('、') }}</span>
            <span v-else class="hm-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="result.total"
          :current-page="query.page"
          :page-size="query.limit"
          :page-sizes="[20, 50, 100, 200]"
          @current-change="onPage"
          @size-change="onSize"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.search {
  max-width: 220px;
}

.origin {
  margin-left: 6px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
