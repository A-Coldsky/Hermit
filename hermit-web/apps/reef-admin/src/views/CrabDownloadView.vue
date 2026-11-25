<script setup lang="ts">
import {
  CopyDocument,
  Delete,
  Download,
  Link as LinkIcon,
  Reading,
  Refresh,
  RefreshLeft,
  Tickets,
  Upload,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import HermitDuet from '@shared/components/HermitDuet.vue'
import HermitMark from '@shared/components/HermitMark.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { copyText, notifyError } from '@shared/format'
import { api, type CrabDownloadInfo } from '../api'
import { isAdmin } from '../auth'

const MAX_UPLOAD_BYTES = 64 * 1024 * 1024

const loading = ref(true)
const loadFailed = ref(false)
const downloading = ref(false)
const uploading = ref(false)
const rollingBack = ref(false)
const resetting = ref(false)
const info = ref<CrabDownloadInfo | null>(null)
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const isDirectDownload = computed(() => {
  const src = info.value?.source
  return src === 'UPLOADED' || src === 'LOCAL' || src === 'BUNDLED'
})

const sourceLabel = computed(() => {
  switch (info.value?.source) {
    case 'UPLOADED':
      return '管理员已上传'
    case 'BUNDLED':
      return '内置默认版本'
    case 'LOCAL':
      return '服务器本地文件'
    default:
      return ''
  }
})

const sourceTagType = computed<'primary' | 'success' | 'info'>(() => {
  switch (info.value?.source) {
    case 'UPLOADED':
      return 'primary'
    case 'BUNDLED':
      return 'success'
    default:
      return 'info'
  }
})

const jarName = computed(() => info.value?.fileName || 'hermit-crab-0.1.0-SNAPSHOT-all.jar')
const runCommand = computed(() => `java -jar ${jarName.value} serve`)
const customCommand = computed(() => `HERMIT_HOME=./crab-data java -jar ${jarName.value} serve`)
const windowsCommand = computed(() => `java -jar .\\${jarName.value} serve`)
const windowsCustomCommand = computed(() => `$env:HERMIT_HOME=".\\crab-data"; java -jar .\\${jarName.value} serve`)

function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function shortSha(sha: string | null | undefined): string {
  if (!sha) return '—'
  return sha.length > 16 ? `${sha.slice(0, 16)}…` : sha
}

async function load() {
  loading.value = true
  try {
    info.value = await api.crabDownloadInfo()
    loadFailed.value = false
  } catch (error) {
    loadFailed.value = true
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function downloadJar() {
  if (!info.value || !isDirectDownload.value) return
  downloading.value = true
  try {
    const blob = await api.downloadCrabJar()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = info.value.fileName || 'hermit-crab-all.jar'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
    ElMessage.success('已开始下载 Crab 可执行 JAR 包')
  } catch (error) {
    notifyError(error)
  } finally {
    downloading.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
}

function clearFileInput() {
  selectedFile.value = null
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

async function uploadJar() {
  const file = selectedFile.value
  if (!file) {
    ElMessage.warning('请先选择要上传的 hermit-crab-*-all.jar 文件')
    return
  }
  if (!file.name.toLowerCase().endsWith('.jar')) {
    ElMessage.warning('只支持上传 .jar 文件')
    return
  }
  if (file.size <= 0) {
    ElMessage.warning('上传的文件为空')
    return
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    ElMessage.warning('文件大小不能超过 64 MB')
    return
  }
  uploading.value = true
  try {
    info.value = await api.uploadCrabJar(file)
    clearFileInput()
    ElMessage.success('新版 Crab JAR 已上传并生效')
  } catch (error) {
    notifyError(error)
  } finally {
    uploading.value = false
  }
}

async function rollbackJar() {
  const prev = info.value?.previousUpload
  if (!prev) return
  try {
    await ElMessageBox.confirm(
      `确认将当前下载版本回退到上一版本「${prev.fileName}」（${shortSha(prev.sha256)}）？回退后立即生效。`,
      '回退到上一版本',
      { confirmButtonText: '确认回退', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  rollingBack.value = true
  try {
    info.value = await api.rollbackCrabJar()
    ElMessage.success('已回退到上一版本')
  } catch (error) {
    notifyError(error)
  } finally {
    rollingBack.value = false
  }
}

async function resetJar() {
  try {
    await ElMessageBox.confirm(
      '恢复内置默认版本将删除管理员已上传的当前版本与上一版本，并立即切回 Reef 内置的 Crab 客户端。是否继续？',
      '恢复内置默认版本',
      { confirmButtonText: '确认恢复', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    info.value = await api.resetCrabJar()
    ElMessage.success('已恢复为默认版本')
  } catch (error) {
    notifyError(error)
  } finally {
    resetting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <PageHeader
      title="下载 Crab 客户端"
      description="独立可执行 JAR 包，本机安装 JDK 17 即可直接启动，无需 Docker 或额外依赖"
    >
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="Tickets" @click="$router.push('/tokens')">获取接入令牌</el-button>
      <el-button :icon="Reading" @click="$router.push('/guide')">查看图文教程</el-button>
    </PageHeader>

    <el-alert
      v-if="loadFailed"
      type="error"
      show-icon
      :closable="false"
      class="gap"
      title="无法获取 Crab 下载信息，请稍后重试"
    >
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>

    <div v-if="info" class="hm-card hero" data-test="crab-download-card">
      <template v-if="isDirectDownload">
        <div class="hero-top">
          <div class="hero-lead">
            <HermitMark variant="crab" :size="56" intro class="hero-mark" />
            <div>
              <div class="hero-badges">
                <el-tag type="success" effect="dark">可直接下载</el-tag>
                <el-tag :type="sourceTagType" effect="plain" data-test="jar-source-tag">{{ sourceLabel }}</el-tag>
                <el-tag type="info" effect="plain">独立可执行 JAR · JDK 17+</el-tag>
                <el-tag v-if="info.latestCrabVersion" type="primary" effect="plain" class="mono">
                  推荐版本 v{{ info.latestCrabVersion }}
                </el-tag>
              </div>
              <h2 class="hero-file mono" data-test="jar-filename">{{ info.fileName }}</h2>
              <p class="hm-muted hero-desc">
                内含 Crab 规则引擎、本地 H2 存储、MCP 服务端与本机控制台界面，下载后一条命令即可运行。
              </p>
            </div>
          </div>
          <div class="hero-actions">
            <el-button
              type="primary"
              size="large"
              :icon="Download"
              :loading="downloading"
              data-test="download-jar-btn"
              @click="downloadJar"
            >
              下载可执行 JAR 包
            </el-button>
            <a
              v-if="info.externalUrl"
              :href="info.externalUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="ext-link"
            >
              <el-button text :icon="LinkIcon">备用外部下载链接</el-button>
            </a>
          </div>
        </div>

        <el-descriptions :column="2" border class="meta-table">
          <el-descriptions-item label="文件大小">{{ formatBytes(info.sizeBytes) }}</el-descriptions-item>
          <el-descriptions-item :label="info.source === 'BUNDLED' ? '构建时间' : '更新时间'">
            <TimeText v-if="info.updatedAt" :value="info.updatedAt" />
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="info.source === 'UPLOADED' && info.uploadedBy" label="上传人" :span="2">
            {{ info.uploadedBy }}
          </el-descriptions-item>
          <el-descriptions-item label="SHA-256 校验和" :span="2">
            <div class="sha-row">
              <code class="mono sha-text" data-test="jar-sha256">{{ info.sha256 }}</code>
              <el-button
                v-if="info.sha256"
                size="small"
                text
                :icon="CopyDocument"
                @click="copyText(info.sha256, 'SHA-256 已复制')"
              >
                复制
              </el-button>
            </div>
          </el-descriptions-item>
        </el-descriptions>
        <p class="hm-muted small-note">
          Windows 浏览器下载 <code class="mono">.jar</code> 时若出现安全提示，选择「保留」并核对上方 SHA-256 校验和即可。
        </p>
      </template>

      <template v-else-if="info.source === 'EXTERNAL'">
        <div class="hero-top">
          <div class="hero-lead">
            <HermitMark variant="crab" :size="56" intro class="hero-mark" />
            <div>
              <div class="hero-badges">
                <el-tag type="primary" effect="plain">外部下载地址</el-tag>
                <el-tag v-if="info.latestCrabVersion" type="info" effect="plain" class="mono">
                  推荐版本 v{{ info.latestCrabVersion }}
                </el-tag>
              </div>
              <h2 class="hero-file">前往指定地址下载 Crab 客户端</h2>
              <p class="hm-muted hero-desc">管理员已配置外部安装包链接，点击右侧按钮在新窗口打开。</p>
            </div>
          </div>
          <div class="hero-actions">
            <a :href="info.externalUrl || '#'" target="_blank" rel="noopener noreferrer" class="ext-link">
              <el-button type="primary" size="large" :icon="LinkIcon" data-test="external-download-btn">
                打开下载链接
              </el-button>
            </a>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="empty-box" data-test="no-jar-state">
          <div class="empty-head">
            <div>
              <h3>暂未放置 Crab 独立 JAR 包</h3>
              <p class="hm-muted">
                {{
                  isAdmin
                    ? '可直接通过下方管理区上传 hermit-crab-*-all.jar，或将其放入 Reef 数据目录下的 downloads/ 子目录中（无需重启 Reef）。'
                    : '请联系管理员在管理台上传 Crab 独立可执行 JAR，或将其放入 Reef 数据目录的 downloads/ 目录中。'
                }}
              </p>
            </div>
            <HermitDuet variant="scene" :size="82" status="disconnected" />
          </div>
          <div v-if="isAdmin" class="admin-tip">
            <div class="cmd-label">1. 在源码仓库打包独立可执行 JAR：</div>
            <div class="cmd-box">
              <code class="mono">./mvnw -pl hermit-crab -am package -DskipTests</code>
              <el-button
                text
                size="small"
                :icon="CopyDocument"
                @click="copyText('./mvnw -pl hermit-crab -am package -DskipTests', '命令已复制')"
              >
                复制
              </el-button>
            </div>
            <div class="cmd-label">2. 通过下方上传框直接上传，或复制到 Reef 数据目录：</div>
            <div class="cmd-box">
              <code class="mono">cp hermit-crab/target/hermit-crab-*-all.jar &lt;reef-data&gt;/downloads/</code>
              <el-button
                text
                size="small"
                :icon="CopyDocument"
                @click="copyText('cp hermit-crab/target/hermit-crab-*-all.jar ./reef-data/downloads/', '命令已复制')"
              >
                复制
              </el-button>
            </div>
            <p class="hm-muted small-note">
              正式打包的 <code class="mono">hermit-reef</code> JAR 已内置默认 Crab 客户端，无需手工拷贝即可直接使用；也可通过环境变量
              <code class="mono">HERMIT_REEF_CRAB_JAR_FILE</code> 固定指定文件名。
            </p>
          </div>
        </div>
      </template>
    </div>

    <div v-if="isAdmin && info" class="hm-card row" data-test="crab-admin-manage">
      <div class="hm-card-title">
        <span>客户端版本管理（仅管理员）</span>
        <span class="hm-muted small-title-note">自动保留当前版本与上一个上传版本，立即生效</span>
      </div>

      <el-alert
        v-if="info.pinnedByConfig"
        type="warning"
        show-icon
        :closable="false"
        class="gap"
        title="当前由配置项 hermit.reef.crab-jar-file 固定指定客户端文件，页面上传与回退已停用。"
      />

      <div class="upload-bar">
        <input
          ref="fileInput"
          type="file"
          accept=".jar"
          class="file-input"
          data-test="crab-file-input"
          :disabled="uploading || !!info.pinnedByConfig"
          @change="onFileChange"
        />
        <el-button
          type="primary"
          :icon="Upload"
          :loading="uploading"
          :disabled="!selectedFile || !!info.pinnedByConfig"
          data-test="upload-jar-btn"
          @click="uploadJar"
        >
          上传并立即生效
        </el-button>
      </div>
      <p class="hm-muted small-note">
        请上传带 <code class="mono">-all</code> 后缀的独立可执行包（如 <code class="mono">hermit-crab-0.1.0-SNAPSHOT-all.jar</code>，上限 64 MB）。重复上传新版本时仅保留最新两版，更早的上传文件会自动清理。
      </p>

      <div v-if="info.previousUpload || info.bundledAvailable" class="version-slots">
        <div v-if="info.previousUpload" class="slot-row" data-test="previous-upload-row">
          <div class="slot-info">
            <div class="slot-head">
              <el-tag size="small" type="warning" effect="plain">上一上传版本</el-tag>
              <span class="mono slot-name">{{ info.previousUpload.fileName }}</span>
            </div>
            <div class="hm-muted slot-meta">
              <span>{{ formatBytes(info.previousUpload.sizeBytes) }}</span>
              <span>·</span>
              <span>SHA-256 <code class="mono">{{ shortSha(info.previousUpload.sha256) }}</code></span>
              <template v-if="info.previousUpload.uploadedAt">
                <span>·</span>
                <TimeText :value="info.previousUpload.uploadedAt" />
              </template>
              <template v-if="info.previousUpload.uploadedBy">
                <span>·</span>
                <span>{{ info.previousUpload.uploadedBy }}</span>
              </template>
            </div>
          </div>
          <el-button
            size="small"
            :icon="RefreshLeft"
            :loading="rollingBack"
            :disabled="!!info.pinnedByConfig"
            data-test="rollback-jar-btn"
            @click="rollbackJar"
          >
            回退到上一版本
          </el-button>
        </div>

        <div v-if="info.bundledAvailable" class="slot-row" data-test="bundled-default-row">
          <div class="slot-info">
            <div class="slot-head">
              <el-tag size="small" type="success" effect="plain">Reef 内置默认版本</el-tag>
              <span class="mono slot-name">{{ info.bundledFileName }}</span>
            </div>
            <div class="hm-muted slot-meta">
              <span>{{ formatBytes(info.bundledSizeBytes) }}</span>
              <span>·</span>
              <span>SHA-256 <code class="mono">{{ shortSha(info.bundledSha256) }}</code></span>
              <span v-if="info.source === 'BUNDLED'">· 当前生效中</span>
            </div>
          </div>
          <el-button
            v-if="info.source === 'UPLOADED'"
            size="small"
            type="danger"
            plain
            :icon="Delete"
            :loading="resetting"
            data-test="reset-jar-btn"
            @click="resetJar"
          >
            恢复内置默认版本
          </el-button>
        </div>
      </div>
    </div>

    <div class="hm-card row">
      <div class="hm-card-title">
        <span>3 步启动本机 Crab（无需 Docker）</span>
        <router-link to="/guide" class="more">完整图文教程 →</router-link>
      </div>
      <div class="steps-grid">
        <div class="step-card">
          <div class="step-num">01</div>
          <h4>确认本机已安装 JDK 17+</h4>
          <p class="hm-muted">打开终端 / PowerShell，确认 Java 版本不低于 17：</p>
          <div class="cmd-box">
            <code class="mono">java -version</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText('java -version', '命令已复制')" />
          </div>
        </div>

        <div class="step-card">
          <div class="step-num">02</div>
          <h4>一条命令启动 Crab</h4>
          <p class="hm-muted">macOS / Linux：</p>
          <div class="cmd-box">
            <code class="mono">{{ runCommand }}</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText(runCommand, '命令已复制')" />
          </div>
          <p class="hm-muted sub-gap">Windows PowerShell：</p>
          <div class="cmd-box">
            <code class="mono">{{ windowsCommand }}</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText(windowsCommand, '命令已复制')" />
          </div>
          <p class="hm-muted sub-gap">如需指定独立数据目录（端口可在 <code class="mono">config.yml</code> 中修改 <code class="mono">server.port</code>，路径不要含分号）：</p>
          <div class="cmd-box">
            <code class="mono">{{ customCommand }}</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText(customCommand, '命令已复制')" />
          </div>
          <p class="hm-muted sub-gap">Windows PowerShell 指定独立数据目录：</p>
          <div class="cmd-box">
            <code class="mono">{{ windowsCustomCommand }}</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText(windowsCustomCommand, '命令已复制')" />
          </div>
        </div>

        <div class="step-card">
          <div class="step-num">03</div>
          <h4>打开本机控制台并接入 Reef</h4>
          <p class="hm-muted">
            浏览器访问 <code class="mono">http://127.0.0.1:7777/console/</code>，进入「连接 Reef」，粘贴在 Reef
            「我的令牌」页面生成的一次性接入配置 JSON，即可自动导入签名公钥与同步令牌。
          </p>
          <div class="step-links">
            <el-button size="small" type="primary" plain :icon="Tickets" @click="$router.push('/tokens')">
              去生成接入令牌
            </el-button>
            <el-button size="small" :icon="Reading" @click="$router.push('/guide')">
              查看详细步骤
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gap {
  margin-bottom: 16px;
}

.row {
  margin-top: 16px;
}

.hero-top {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.hero-lead {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex: 1 1 360px;
  min-width: 0;
}

.hero-mark {
  margin-top: 2px;
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.hero-file {
  margin: 0;
  font-size: 20px;
  word-break: break-all;
}

.hero-desc {
  margin: 8px 0 0;
  font-size: 13.5px;
}

.hero-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.ext-link {
  text-decoration: none;
}

.meta-table {
  margin-top: 18px;
}

.sha-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sha-text {
  word-break: break-all;
  font-size: 12.5px;
}

.small-title-note {
  font-size: 12.5px;
  font-weight: 400;
}

.upload-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.file-input {
  flex: 1 1 260px;
  min-width: 0;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px dashed var(--hm-border);
  background: var(--hm-subtle);
  color: inherit;
  font-size: 13px;
}

.version-slots {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.slot-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--hm-border);
  background: var(--hm-subtle);
}

.slot-info {
  flex: 1 1 300px;
  min-width: 0;
}

.slot-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.slot-name {
  font-size: 13.5px;
  font-weight: 600;
  word-break: break-all;
}

.slot-meta {
  margin-top: 6px;
  font-size: 12.5px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.empty-box {
  padding: 12px 4px;
}

.empty-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.empty-head > div {
  flex: 1 1 320px;
  min-width: 0;
}

.empty-box h3 {
  margin: 0 0 8px;
}

.admin-tip {
  margin-top: 14px;
  padding: 14px;
  border-radius: 10px;
  background: var(--hm-subtle);
  border: 1px solid var(--hm-border);
}

.cmd-label {
  font-size: 13px;
  font-weight: 500;
  margin: 10px 0 6px;
}

.cmd-label:first-child {
  margin-top: 0;
}

.cmd-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--hm-code-bg);
  color: var(--hm-code-text);
  font-size: 12.5px;
  overflow-x: auto;
}

.cmd-box code {
  white-space: pre-wrap;
  word-break: break-all;
}

.small-note {
  margin: 10px 0 0;
  font-size: 12.5px;
}

.more {
  font-size: 13px;
  font-weight: 400;
}

.steps-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}

.step-card {
  padding: 16px;
  border-radius: 10px;
  border: 1px solid var(--hm-border);
  background: var(--hm-subtle);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-num {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--el-color-primary);
}

.step-card h4 {
  margin: 0;
  font-size: 15px;
}

.step-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.sub-gap {
  margin-top: 6px !important;
}

.step-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
  padding-top: 8px;
}
</style>
