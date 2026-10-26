<script setup lang="ts">
import { ArrowRight, CircleCheckFilled, Refresh, User, WarningFilled } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import ShellAvatar from '@shared/components/ShellAvatar.vue'
import { notifyError } from '@shared/format'
import { api, type ShellSummary } from '../api'
import { refreshPending } from '../status'

const router = useRouter()
const loading = ref(true)
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

    <div v-if="loading && !shells.length" class="cards">
      <div v-for="n in 2" :key="n" class="hm-card"><el-skeleton :rows="3" animated /></div>
    </div>
    <div v-else-if="shells.length" class="cards">
      <div
        v-for="row in shells"
        :key="row.shell"
        class="hm-card shell clickable"
        :class="{ warn: row.needsConfiguration }"
        role="link"
        tabindex="0"
        @click="open(row)"
        @keyup.enter="open(row)"
      >
        <div class="top">
          <ShellAvatar :name="row.shell" :title="row.title" />
          <div class="name">
            <strong>{{ row.title || row.shell }}</strong>
            <span class="mono hm-muted">{{ row.shell }} · v{{ row.version }}</span>
          </div>
        </div>
        <div class="status" :class="row.needsConfiguration ? 'warn' : row.accountCount ? 'ok' : 'idle'">
          <el-icon><component :is="row.needsConfiguration ? WarningFilled : row.accountCount ? CircleCheckFilled : User" /></el-icon>
          <span v-if="row.loginPaused">已暂停：有账号自动登录已暂停，进入详情查看恢复方法</span>
          <span v-else-if="row.needsConfiguration">待配置：还缺凭证或配置项</span>
          <span v-else-if="!row.accountCount">未建账号：先新建一个账号</span>
          <span v-else>可用：可以通过 OpenAPI 和 MCP 调用</span>
        </div>
        <div class="foot hm-muted">
          <span><b class="num">{{ row.capabilityCount }}</b> 个能力</span>
          <span><b class="num">{{ row.accountCount }}</b> 个账号</span>
          <span class="spacer" />
          <span class="manage">管理<el-icon><ArrowRight /></el-icon></span>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="hm-card empty">还没有 Shell。到概览页从 Reef 同步，或把 YAML 放进本地 Shell 目录</div>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.shell {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.name strong {
  font-size: 15px;
}

.name .mono {
  font-size: 12px;
}

.status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13px;
}

.status.ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
}

.status.warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}

.status.idle {
  background: var(--hm-subtle);
  color: var(--hm-muted);
}

.foot {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12.5px;
}

.foot b {
  color: var(--hm-text);
}

.spacer {
  flex: 1;
}

.manage {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--el-color-primary);
  font-weight: 500;
}

.empty {
  padding: 48px 20px;
  text-align: center;
  color: var(--hm-muted);
}
</style>
