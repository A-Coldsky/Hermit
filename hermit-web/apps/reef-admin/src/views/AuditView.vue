<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { notifyError } from '@shared/format'
import { api, type AuditPage } from '../api'
import { auditActionLabels } from '../labels'

const loading = ref(true)
const loadFailed = ref(false)
const query = reactive({ conch: '', page: 1, size: 20 })
const result = ref<AuditPage>({ items: [], page: 1, size: 20, total: 0 })
let loadSeq = 0

const actionTone: Record<string, 'success' | 'danger' | 'warning' | 'info' | 'primary'> = {
  LOGIN_SUCCESS: 'info',
  LOGIN_FAILURE: 'danger',
  UPLOAD: 'primary',
  PUBLISH: 'success',
  ROLLBACK: 'warning',
}

async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const data = await api.audit({ conch: query.conch.trim() || undefined, page: query.page, size: query.size })
    if (seq !== loadSeq) return
    result.value = data
    loadFailed.value = false
  } catch (error) {
    if (seq !== loadSeq) return
    loadFailed.value = true
    notifyError(error)
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
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
  query.size = size
  query.page = 1
  void load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="审计日志" description="登录、上传、发布、停用等管理操作的记录">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="load-failed"
      :title="result.items.length ? '审计日志刷新失败，下面是上一次读到的内容' : '审计日志读取失败'"
      data-test="audit-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div class="hm-card">
      <div class="hm-toolbar">
        <el-input v-model="query.conch" placeholder="按规则包名称筛选" clearable class="search" @keyup.enter="search" @clear="search">
          <template #append>
            <el-button :icon="Search" @click="search" />
          </template>
        </el-input>
      </div>
      <el-table v-loading="loading" :data="result.items">
        <template #empty>{{ loading || loadFailed ? '' : '没有记录' }}</template>
        <el-table-column label="时间" width="230">
          <template #default="{ row }"><TimeText :value="row.at" /></template>
        </el-table-column>
        <el-table-column prop="actor" label="操作人" width="140" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-tag :type="actionTone[row.action] ?? 'info'" effect="plain" size="small">
              {{ auditActionLabels[row.action] ?? row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="180">
          <template #default="{ row }">
            <template v-if="row.conch">
              <router-link :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono">{{ row.conch }}</router-link>
              <span v-if="row.version" class="mono hm-muted"> v{{ row.version }}</span>
            </template>
            <span v-else class="hm-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="240" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.detail ?? '' }}</span></template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="result.total"
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="[20, 50, 100]"
          @current-change="onPage"
          @size-change="onSize"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.load-failed {
  margin-bottom: 16px;
}

.search {
  max-width: 320px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
