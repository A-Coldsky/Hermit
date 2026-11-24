<script setup lang="ts">
import { CopyDocument, Download, Link as LinkIcon, Reading, Refresh, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { copyText, notifyError } from '@shared/format'
import { api, type CrabDownloadInfo } from '../api'
import { isAdmin } from '../auth'

const loading = ref(true)
const loadFailed = ref(false)
const downloading = ref(false)
const info = ref<CrabDownloadInfo | null>(null)

const jarName = computed(() => info.value?.fileName || 'hermit-crab-0.1.0-SNAPSHOT-all.jar')
const runCommand = computed(() => `java -jar ${jarName.value} serve`)
const customCommand = computed(() => `HERMIT_HOME=./crab-data java -jar ${jarName.value} serve`)
const windowsCommand = computed(() => `java -jar .\\${jarName.value} serve`)

function formatBytes(bytes: number | null): string {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
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
  if (!info.value || info.value.source !== 'LOCAL') return
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
      <template v-if="info.source === 'LOCAL'">
        <div class="hero-top">
          <div>
            <div class="hero-badges">
              <el-tag type="success" effect="dark">可直接下载</el-tag>
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
          <el-descriptions-item label="更新时间">
            <TimeText v-if="info.updatedAt" :value="info.updatedAt" />
            <span v-else>—</span>
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
      </template>

      <template v-else-if="info.source === 'EXTERNAL'">
        <div class="hero-top">
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
          <h3>暂未放置 Crab 独立 JAR 包</h3>
          <p class="hm-muted">
            {{
              isAdmin
                ? '请在构建独立 JAR 后，将文件放入 Reef 数据目录下的 downloads/ 子目录中（无需重启 Reef）。'
                : '请联系管理员将构建好的 Crab 独立可执行 JAR 放入 Reef 数据目录的 downloads/ 目录中。'
            }}
          </p>
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
            <div class="cmd-label">2. 将生成的 fat JAR 复制到 Reef 数据目录：</div>
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
              放入后点击本页右上角「刷新」即可生效；也可以通过环境变量
              <code class="mono">HERMIT_REEF_CRAB_JAR_FILE</code> 固定指定文件名。
            </p>
          </div>
        </div>
      </template>
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
          <p class="hm-muted sub-gap">如需指定独立数据目录（端口可在 <code class="mono">config.yml</code> 中修改 <code class="mono">server.port</code>）：</p>
          <div class="cmd-box">
            <code class="mono">{{ customCommand }}</code>
            <el-button text size="small" :icon="CopyDocument" @click="copyText(customCommand, '命令已复制')" />
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

.empty-box {
  padding: 12px 4px;
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
