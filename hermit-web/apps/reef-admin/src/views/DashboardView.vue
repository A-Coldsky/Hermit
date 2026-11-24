<script setup lang="ts">
import { isAdmin } from '../auth'
import {
  Bell,
  Box,
  CircleCheckFilled,
  DataLine,
  Delete,
  Edit,
  Plus,
  Refresh,
  Upload,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import BarChart from '@shared/components/BarChart.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import StatCard from '@shared/components/StatCard.vue'
import { notifyError, percent, relativeTime } from '@shared/format'
import {
  api,
  type Alert,
  type Announcement,
  type AnnouncementLevel,
  type ConchSummary,
  type RollbackEvent,
  type TideSummary,
} from '../api'
import { refreshOpenAlerts } from '../alerts'
import { alertKindLabels, announcementLevelLabels, crabLabel, isoDaysAgo, versionChangeReasons } from '../labels'
import { refreshPendingSubmissions } from '../submissions'
import { toBars, totals } from '../tide'

const loading = ref(true)
const loadFailed = ref(false)
const conches = ref<ConchSummary[]>([])
const tide = ref<TideSummary | null>(null)
const alerts = ref<Alert[]>([])
const rollbacks = ref<RollbackEvent[]>([])
const announcements = ref<Announcement[]>([])

const noticeDialog = ref(false)
const noticeSaving = ref(false)
const noticeError = ref('')
const editingId = ref<number | null>(null)
const noticeForm = reactive<{
  title: string
  body: string
  level: AnnouncementLevel
  pinned: boolean
}>({
  title: '',
  body: '',
  level: 'INFO',
  pinned: false,
})

const published = computed(() => conches.value.filter((s) => s.version).length)
const week = computed(() => totals(tide.value?.series ?? []))
const bars = computed(() => toBars(tide.value?.series ?? [], 'day', tide.value?.from, tide.value?.to))
const showNoticeCard = computed(() => announcements.value.length > 0 || isAdmin.value)

function levelTagType(level: AnnouncementLevel): 'info' | 'warning' | 'danger' {
  if (level === 'IMPORTANT') return 'danger'
  if (level === 'WARNING') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const from = isoDaysAgo(7)
    const [s, t, a, r, n] = await Promise.all([
      api.conches(),
      api.tideSummary({ from, bucket: 'day' }),
      api.alerts('open'),
      api.tideRollbacks({ from }),
      typeof api.announcements === 'function' ? api.announcements().catch(() => [] as Announcement[]) : Promise.resolve([] as Announcement[]),
    ])
    conches.value = s
    tide.value = t
    alerts.value = a.alerts
    rollbacks.value = r.events
    announcements.value = n
    loadFailed.value = false
    void refreshOpenAlerts()
    void refreshPendingSubmissions()
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function openCreateNotice() {
  editingId.value = null
  noticeForm.title = ''
  noticeForm.body = ''
  noticeForm.level = 'INFO'
  noticeForm.pinned = false
  noticeError.value = ''
  noticeDialog.value = true
}

function openEditNotice(item: Announcement) {
  editingId.value = item.id
  noticeForm.title = item.title
  noticeForm.body = item.body
  noticeForm.level = item.level
  noticeForm.pinned = item.pinned
  noticeError.value = ''
  noticeDialog.value = true
}

async function saveNotice() {
  const title = noticeForm.title.trim()
  const body = noticeForm.body.trim()
  if (!title) {
    noticeError.value = '请填写公告标题'
    return
  }
  if (title.length > 128) {
    noticeError.value = '公告标题不能超过 128 个字符'
    return
  }
  if (!body) {
    noticeError.value = '请填写公告正文'
    return
  }
  if (body.length > 8000) {
    noticeError.value = '公告正文不能超过 8000 个字符'
    return
  }
  noticeError.value = ''
  noticeSaving.value = true
  try {
    const payload = {
      title,
      body,
      level: noticeForm.level,
      pinned: noticeForm.pinned,
    }
    if (editingId.value === null) {
      await api.createAnnouncement(payload)
      ElMessage.success('公告已发布')
    } else {
      await api.updateAnnouncement(editingId.value, payload)
      ElMessage.success('公告已更新')
    }
    noticeDialog.value = false
    announcements.value = await api.announcements()
  } catch (error) {
    notifyError(error)
  } finally {
    noticeSaving.value = false
  }
}

async function removeNotice(item: Announcement) {
  try {
    await ElMessageBox.confirm(`确认删除公告「${item.title}」？删除后所有用户将不再看到这条通知。`, '删除公告', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api.deleteAnnouncement(item.id)
    ElMessage.success('公告已删除')
    announcements.value = await api.announcements()
  } catch (error) {
    notifyError(error)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <PageHeader title="概览" description="最近 7 天的规则包发布与调用情况">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button v-if="isAdmin" type="primary" :icon="Upload" @click="$router.push('/conches/upload')">上传规则包</el-button>
      <el-button v-else type="primary" :icon="Upload" @click="$router.push('/submissions')">提交规则包</el-button>
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="load-failed"
      :title="tide ? '概览数据刷新失败，下面是上一次读到的内容' : '概览数据读取失败'"
      data-test="dashboard-load-failed"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div v-if="showNoticeCard" class="hm-card notice-card" data-test="announcements-card">
      <div class="hm-card-title">
        <span>公共公告</span>
        <el-button
          v-if="isAdmin"
          size="small"
          type="primary"
          plain
          :icon="Plus"
          data-test="create-notice-btn"
          @click="openCreateNotice"
        >
          发布公告
        </el-button>
      </div>
      <div v-if="announcements.length" class="notice-list">
        <article
          v-for="item in announcements"
          :key="item.id"
          class="notice-item"
          :class="`level-${item.level.toLowerCase()}`"
          data-test="notice-item"
        >
          <div class="notice-head">
            <div class="notice-title-wrap">
              <el-tag v-if="item.pinned" size="small" type="primary" effect="dark">置顶</el-tag>
              <el-tag size="small" :type="levelTagType(item.level)" effect="plain">
                {{ announcementLevelLabels[item.level] ?? item.level }}
              </el-tag>
              <strong class="notice-title">{{ item.title }}</strong>
            </div>
            <div class="notice-meta">
              <span class="hm-muted">{{ item.updatedBy }} · {{ relativeTime(item.updatedAt) }}</span>
              <template v-if="isAdmin">
                <el-button text size="small" :icon="Edit" data-test="edit-notice-btn" @click="openEditNotice(item)">
                  编辑
                </el-button>
                <el-button
                  text
                  size="small"
                  type="danger"
                  :icon="Delete"
                  data-test="delete-notice-btn"
                  @click="removeNotice(item)"
                >
                  删除
                </el-button>
              </template>
            </div>
          </div>
          <p class="notice-body">{{ item.body }}</p>
        </article>
      </div>
      <p v-else class="hm-muted notice-empty">
        还没有公共公告。点击右上角「发布公告」，向所有登录用户同步版本更新、维护通知或使用说明。
      </p>
    </div>

    <div class="hm-grid cols-4">
      <StatCard label="规则包总数" :value="conches.length" :hint="`已发布 ${published} 个`" :icon="Box" to="/conches" />
      <StatCard label="7 天调用" :value="week.calls" hint="来自各 Crab 的汇总上报" tone="ok" :icon="DataLine" to="/tide" />
      <StatCard
        label="7 天失败率"
        :value="percent(week.rate)"
        :hint="`失败 ${week.failures} 次`"
        :tone="week.rate > 0.1 ? 'danger' : week.rate > 0.02 ? 'warn' : 'ok'"
        :icon="Warning"
        to="/tide"
      />
      <StatCard
        label="未处理告警"
        :value="alerts.length"
        :tone="alerts.length ? 'danger' : 'default'"
        hint="需要人工确认"
        :icon="Bell"
        to="/alerts"
      />
    </div>

    <div class="hm-grid cols-2 row">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>每日调用量</span>
          <router-link to="/tide" class="more">查看 Tide</router-link>
        </div>
        <BarChart :points="bars" :height="220" :loading="loading || loadFailed" />
      </div>

      <div class="hm-card">
        <div class="hm-card-title">
          <span>未处理告警</span>
          <router-link to="/alerts" class="more">全部告警</router-link>
        </div>
        <ul v-if="alerts.length" class="list">
          <li v-for="alert in alerts.slice(0, 6)" :key="alert.id">
            <el-tag type="danger" size="small" effect="plain">{{ alertKindLabels[alert.kind] ?? alert.kind }}</el-tag>
            <router-link :to="`/conches/${encodeURIComponent(alert.conch)}`" class="mono">{{ alert.conch }}</router-link>
            <span v-if="alert.conchVersion" class="hm-muted mono">v{{ alert.conchVersion }}</span>
            <span class="spacer" />
            <span class="hm-muted">{{ relativeTime(alert.createdAt) }}</span>
          </li>
        </ul>
        <div v-else-if="!loading && !loadFailed" class="clear">
          <el-icon :size="40" class="ok-icon"><CircleCheckFilled /></el-icon>
          <strong>一切正常</strong>
          <span class="hm-muted">没有需要处理的告警，新告警会在这里出现</span>
        </div>
        <div v-else-if="!loading && loadFailed" class="clear">
          <span class="hm-muted">没有读到告警数据</span>
        </div>
      </div>
    </div>

    <div class="hm-card row">
      <div class="hm-card-title"><span>最近的版本切换</span></div>
      <el-table :data="rollbacks.slice(0, 10)">
        <template #empty>{{ loading || loadFailed ? '' : '7 天内没有 Crab 切换版本' }}</template>
        <el-table-column label="规则包" min-width="160">
          <template #default="{ row }">
            <router-link :to="`/conches/${encodeURIComponent(row.conch)}`" class="mono">{{ row.conch }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="版本变化" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.fromVersion }} → {{ row.toVersion }}</span>
          </template>
        </el-table-column>
        <el-table-column label="原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ versionChangeReasons[row.reason] ?? row.reason }}</template>
        </el-table-column>
        <el-table-column label="Crab" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ crabLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="120">
          <template #default="{ row }">{{ relativeTime(row.occurredAt) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="noticeDialog"
      :title="editingId === null ? '发布公共公告' : '编辑公共公告'"
      width="min(560px, 92vw)"
      destroy-on-close
    >
      <el-alert v-if="noticeError" :title="noticeError" type="error" show-icon :closable="false" class="dialog-alert" />
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input
            v-model="noticeForm.title"
            maxlength="128"
            show-word-limit
            placeholder="例如：Crab 1.12.0 独立 JAR 包已更新，支持审批规则"
            data-test="notice-title-input"
          />
        </el-form-item>
        <el-form-item label="正文（纯文本，支持换行）" required>
          <el-input
            v-model="noticeForm.body"
            type="textarea"
            :rows="6"
            maxlength="8000"
            show-word-limit
            placeholder="填写希望同步给所有使用者的更新内容、操作指引或维护安排"
            data-test="notice-body-input"
          />
        </el-form-item>
        <div class="dialog-row">
          <el-form-item label="提醒级别">
            <el-radio-group v-model="noticeForm.level">
              <el-radio-button value="INFO">通知</el-radio-button>
              <el-radio-button value="WARNING">提醒</el-radio-button>
              <el-radio-button value="IMPORTANT">重要</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="展示顺序">
            <el-checkbox v-model="noticeForm.pinned">置顶展示</el-checkbox>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="noticeDialog = false">取消</el-button>
        <el-button type="primary" :loading="noticeSaving" data-test="save-notice-btn" @click="saveNotice">
          {{ editingId === null ? '发布' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.load-failed {
  margin-bottom: 16px;
}

.notice-card {
  margin-bottom: 16px;
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.notice-item {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--hm-border);
  background: var(--hm-subtle);
  border-left: 3px solid var(--el-color-primary);
}

.notice-item.level-warning {
  border-left-color: var(--hm-accent);
}

.notice-item.level-important {
  border-left-color: var(--hm-danger);
}

.notice-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.notice-title-wrap {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.notice-title {
  font-size: 14px;
  color: var(--hm-text);
}

.notice-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.notice-body {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.65;
  font-size: 13.5px;
  color: var(--hm-text);
}

.notice-empty {
  margin: 0;
  font-size: 13px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.dialog-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.row {
  margin-top: 16px;
}

.more {
  font-size: 13px;
  font-weight: 400;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid var(--hm-border);
}

.list li:last-child {
  border-bottom: none;
}

.spacer {
  flex: 1;
}

.clear {
  height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
}

.ok-icon {
  color: var(--hm-success);
  margin-bottom: 4px;
  filter: drop-shadow(0 6px 12px rgba(22, 163, 74, 0.25));
}
</style>
