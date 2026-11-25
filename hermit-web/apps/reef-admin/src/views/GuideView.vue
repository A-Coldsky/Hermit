<script setup lang="ts">
import {
  Box,
  Checked,
  CopyDocument,
  Download,
  Lock,
  MagicStick,
  Setting,
  Tickets,
  ZoomIn,
} from '@element-plus/icons-vue'
import { computed, ref } from 'vue'
import MascotCrab from '@shared/components/MascotCrab.vue'
import MascotReef from '@shared/components/MascotReef.vue'
import MascotShell from '@shared/components/MascotShell.vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText } from '@shared/format'
import { isAdmin } from '../auth'

import shotDashboard from '../assets/guide/reef_dashboard_notice.webp'
import shotDownload from '../assets/guide/reef_crab_download.webp'
import shotApproval from '../assets/guide/reef_rule_approval.webp'
import shotCrabReef from '../assets/guide/crab_reef_connect.webp'
import shotCrabConches from '../assets/guide/crab_conch_account.webp'
import shotCrabApiKeys from '../assets/guide/crab_apikey_mcp.webp'
import shotCrabRecorder from '../assets/guide/crab_browser_recorder.webp'

type TabKey = 'quickstart' | 'subscribe' | 'authoring' | 'intranet'

const activeTab = ref<TabKey>('quickstart')

const allScreenshots = [
  shotDashboard,
  shotDownload,
  shotCrabReef,
  shotCrabConches,
  shotCrabApiKeys,
  shotApproval,
  shotCrabRecorder,
]

const tabs: { key: TabKey; badge: string; title: string; subtitle: string }[] = [
  {
    key: 'quickstart',
    badge: '场景 1',
    title: '免 Docker 快速启动 Crab',
    subtitle: '下载独立 JAR，JDK 17 一条命令运行并接入 Reef',
  },
  {
    key: 'subscribe',
    badge: '场景 2',
    title: '订阅规则与对接 AI / MCP',
    subtitle: '配置本机加密凭证、签发最小权限 API Key',
  },
  {
    key: 'authoring',
    badge: '场景 3',
    title: '编写本地规则与审批发布',
    subtitle: '本地调试导出 YAML → 提交 Reef 审批 → 签名上线',
  },
  {
    key: 'intranet',
    badge: '场景 4',
    title: '内网进阶场景速查',
    subtitle: 'SSO 跳转、自签证书、多包共享身份与会话失效识别',
  },
]

const startCmdUnix = 'java -jar hermit-crab-0.1.0-SNAPSHOT-all.jar serve'
const startCmdWin = 'java -jar .\\hermit-crab-0.1.0-SNAPSHOT-all.jar serve'

const mcpConfigSnippet = `{
  "mcpServers": {
    "hermit-crab": {
      "type": "http",
      "url": "http://127.0.0.1:7777/mcp",
      "headers": {
        "Authorization": "Bearer hk_替换为本机创建的API_Key"
      }
    }
  }
}`

const curlSnippet = `curl -X POST http://127.0.0.1:7777/api/demo_oa/list_todo \\
  -H "Authorization: Bearer hk_替换为本机创建的API_Key" \\
  -H "Content-Type: application/json" \\
  -d '{"page": 1}'`

const sampleRuleYaml = `conch: demo_oa
version: 1.0.0
driver: http
min_crab_version: 1.0.0
base_url: "http://oa.example.internal"
title: 协同办公 OA（示例）
description: 查询当前账号的待办审批列表。
owner: 平台团队
tags: [OA, 审批]

credentials:
  username: { type: text, label: OA 账号, required: true, description: 内网登录用户名 }
  password: { type: secret, label: 登录密码, required: true, description: 仅保存在本机 Crab 加密库 }

auth:
  steps:
    - request:
        method: POST
        path: /api/login
        json:
          username: "{{credential.username}}"
          password: "{{credential.password}}"
    - assert: { cookie_exists: OA_SESSION }
  alive_check:
    request: { method: GET, path: /home }
    expect_not_redirect: /login

actions:
  list_todo:
    title: 查询待办审批
    description: 获取当前账号待处理的审批单。
    input:
      page: { type: integer, default: 1, minimum: 1, description: 页码 }
    steps:
      - request:
          method: GET
          path: /api/todos
          query: { page: "{{input.page}}" }
      - extract:
          items: { jsonpath: "$.items" }
    output:
      items: { type: array, description: 待办列表, value: "{{items}}" }
    examples:
      - input: { page: 1 }
        output: { items: [{ id: "OA-1024", title: "示例报销单审批" }] }
    notes: 只读接口，不会修改业务数据。
    related: []
    idempotent: true
    mcp: { read_only: true }`

const submissionLabel = computed(() => (isAdmin.value ? '前往规则审批' : '前往规则提交'))
</script>

<template>
  <div>
    <PageHeader
      title="使用教程"
      description="围绕 4 个最常用的业务场景，用架构图、实景截图和可复制命令带你快速上手 Hermit（Reef + Crab）"
    >
      <el-button :icon="Download" @click="$router.push('/crab')">下载 Crab</el-button>
      <el-button :icon="Tickets" @click="$router.push('/tokens')">我的令牌</el-button>
      <el-button type="primary" :icon="Checked" @click="$router.push('/submissions')">{{ submissionLabel }}</el-button>
    </PageHeader>

    <!-- 场景切换卡片 -->
    <div class="scenario-nav" role="tablist" data-test="guide-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key"
        class="scenario-btn"
        :class="{ active: activeTab === tab.key }"
        :data-test="`guide-tab-${tab.key}`"
        @click="activeTab = tab.key"
      >
        <span class="scenario-badge">{{ tab.badge }}</span>
        <strong>{{ tab.title }}</strong>
        <span class="scenario-sub">{{ tab.subtitle }}</span>
      </button>
    </div>

    <!-- 场景 1：快速启动与接入 -->
    <section v-show="activeTab === 'quickstart'" class="guide-section" data-test="guide-panel-quickstart">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>整体分工与安全边界</span>
          <el-tag type="success" effect="plain">凭证永不出本机</el-tag>
        </div>
        <p class="lead">
          Hermit 由中心管理端 <strong>Reef</strong> 与运行在你自己电脑上的轻量客户端 <strong>Crab</strong> 组成。
          Reef 只负责分发经 Ed25519 签名的规则包、发布公告与回收脱敏指标；所有老系统账号密码和 Cookie
          只加密保存在本机 Crab 中，无需 Docker，安装 JDK 17 即可运行。
        </p>

        <div class="diagram-wrap" aria-label="Reef 与 Crab 分工架构图">
          <svg viewBox="0 0 880 210" class="diagram-svg" role="img">
            <defs>
              <marker id="arrow-teal" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--el-color-primary)" />
              </marker>
              <marker id="arrow-muted" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--hm-muted)" />
              </marker>
            </defs>
            <!-- Reef -->
            <rect x="20" y="34" width="235" height="142" rx="14" class="svg-box svg-box-primary" />
            <g transform="translate(216 40) scale(0.46)"><MascotReef :bubbles="false" /></g>
            <text x="40" y="66" class="svg-title">Reef 管理端（团队共用）</text>
            <text x="40" y="92" class="svg-desc">· 公共公告 &amp; 使用教程</text>
            <text x="40" y="114" class="svg-desc">· 规则包审批 &amp; Ed25519 签名</text>
            <text x="40" y="136" class="svg-desc">· 提供独立 Crab JAR 下载</text>
            <text x="40" y="158" class="svg-desc">· 汇总脱敏调用统计（Tide）</text>

            <!-- Arrow Reef -> Crab -->
            <line x1="255" y1="88" x2="328" y2="88" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-teal)" />
            <text x="292" y="78" text-anchor="middle" class="svg-edge">签名规则包</text>
            <line x1="328" y1="126" x2="255" y2="126" stroke="var(--hm-muted)" stroke-width="1.6" stroke-dasharray="5 4" marker-end="url(#arrow-muted)" />
            <text x="292" y="144" text-anchor="middle" class="svg-edge-muted">脱敏上报</text>

            <!-- Crab -->
            <rect x="330" y="24" width="265" height="162" rx="14" class="svg-box svg-box-accent" />
            <g transform="translate(556 30) scale(0.46)">
              <MascotShell />
              <MascotCrab />
            </g>
            <text x="350" y="56" class="svg-title">本机 Crab（JDK 17 单 JAR）</text>
            <text x="350" y="82" class="svg-desc">· 监听 127.0.0.1:7777（仅本机）</text>
            <text x="350" y="104" class="svg-desc">· 校验 Reef 公钥签名后才载入</text>
            <text x="350" y="126" class="svg-desc">· 本地加密保存业务账号与 Cookie</text>
            <text x="350" y="148" class="svg-desc">· 暴露 MCP / OpenAPI 给 AI 调用</text>
            <rect x="350" y="158" width="166" height="20" rx="10" class="svg-pill" />
            <text x="433" y="172" text-anchor="middle" class="svg-pill-text">账号密码不上传 Reef</text>

            <!-- Arrow Crab -> Legacy OA -->
            <line x1="595" y1="105" x2="664" y2="105" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-teal)" />
            <text x="630" y="95" text-anchor="middle" class="svg-edge">自动登录</text>
            <text x="630" y="123" text-anchor="middle" class="svg-edge-muted">HTTP / MCP</text>

            <!-- Legacy OA -->
            <rect x="666" y="42" width="194" height="126" rx="14" class="svg-box" />
            <text x="686" y="74" class="svg-title">企业内网老系统</text>
            <text x="686" y="100" class="svg-desc">· OA / ERP / 报销 / 考勤</text>
            <text x="686" y="122" class="svg-desc">· 表单 / Cookie / SSO</text>
            <text x="686" y="144" class="svg-desc">· 内网原生 MCP 服务</text>
          </svg>
        </div>
      </div>

      <div class="steps-stack">
        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 1</span>
            <h3>查看 Reef 首页「公共公告」并下载 Crab 可执行 JAR</h3>
          </div>
          <p class="step-text">
            登录 Reef 后，首页顶部的「公共公告」会第一时间同步团队的客户端更新、维护窗口和规则变更。
            确认后进入左侧「下载 Crab」页面，直接下载独立可执行的 <code class="mono">hermit-crab-*-all.jar</code>。
          </p>
          <div class="shot-grid cols-2">
            <figure class="shot-card">
              <el-image
                :src="shotDashboard"
                :preview-src-list="allScreenshots"
                :initial-index="0"
                fit="cover"
                class="shot-img"
                alt="Reef 首页公共公告与概览截图"
              />
              <figcaption>
                <span>Reef 概览页：置顶公共公告与运行总览</span>
                <el-icon><ZoomIn /></el-icon>
              </figcaption>
            </figure>
            <figure class="shot-card">
              <el-image
                :src="shotDownload"
                :preview-src-list="allScreenshots"
                :initial-index="1"
                fit="cover"
                class="shot-img"
                alt="Reef 下载 Crab 客户端页面截图"
              />
              <figcaption>
                <span>「下载 Crab」页：获取独立 JAR 与 SHA-256 校验和</span>
                <el-icon><ZoomIn /></el-icon>
              </figcaption>
            </figure>
          </div>
        </article>

        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 2</span>
            <h3>使用本机 JDK 17 启动 Crab（无需 Docker）</h3>
          </div>
          <p class="step-text">
            确认本机 <code class="mono">java -version</code> 为 JDK 17 及以上，在 JAR 所在目录直接运行：
          </p>
          <div class="cmd-grid">
            <div>
              <div class="cmd-title">macOS / Linux</div>
              <div class="cmd-box">
                <code class="mono">{{ startCmdUnix }}</code>
                <el-button text size="small" :icon="CopyDocument" @click="copyText(startCmdUnix, '命令已复制')" />
              </div>
            </div>
            <div>
              <div class="cmd-title">Windows PowerShell</div>
              <div class="cmd-box">
                <code class="mono">{{ startCmdWin }}</code>
                <el-button text size="small" :icon="CopyDocument" @click="copyText(startCmdWin, '命令已复制')" />
              </div>
            </div>
          </div>
          <p class="step-tip">
            启动成功后会输出 <code class="mono">listening on 127.0.0.1:7777</code>，默认数据保存在
            <code class="mono">~/.hermit</code>（可用 <code class="mono">HERMIT_HOME</code> 指定其他目录，或在
            <code class="mono">config.yml</code> 修改 <code class="mono">server.port</code>）。
          </p>
        </article>

        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 3</span>
            <h3>一次性导入初始化 JSON，连接 Reef</h3>
          </div>
          <p class="step-text">
            在 Reef 左侧「我的令牌」新建一个令牌，复制或下载页面生成的<strong>初始化 JSON</strong>（内含 Reef
            地址、同步令牌和 Ed25519 公钥）。随后在浏览器打开本机 Crab 控制台
            <code class="mono">http://127.0.0.1:7777/console/</code>，进入「连接 Reef」粘贴导入即可完成双向对接。
          </p>
          <figure class="shot-card single">
            <el-image
              :src="shotCrabReef"
              :preview-src-list="allScreenshots"
              :initial-index="2"
              fit="cover"
              class="shot-img"
              alt="Crab 本机控制台连接 Reef 界面截图"
            />
            <figcaption>
              <span>Crab「连接 Reef」页：粘贴初始化 JSON 后自动校验证书指纹并开始同步规则目录</span>
              <el-icon><ZoomIn /></el-icon>
            </figcaption>
          </figure>
        </article>
      </div>
    </section>

    <!-- 场景 2：订阅规则与对接 AI / MCP -->
    <section v-show="activeTab === 'subscribe'" class="guide-section" data-test="guide-panel-subscribe">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>AI / 自动化脚本如何通过 Crab 安全调用老系统</span>
          <el-tag type="primary" effect="plain">最小权限 API Key</el-tag>
        </div>
        <div class="diagram-wrap" aria-label="AI 通过 Crab 调用老系统流程图">
          <svg viewBox="0 0 880 165" class="diagram-svg" role="img">
            <defs>
              <marker id="arrow-s2" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--el-color-primary)" />
              </marker>
            </defs>
            <rect x="20" y="30" width="210" height="105" rx="14" class="svg-box" />
            <text x="40" y="60" class="svg-title">AI 客户端 / 内部脚本</text>
            <text x="40" y="85" class="svg-desc">· Claude / Cursor / Codex</text>
            <text x="40" y="106" class="svg-desc">· 仅持有受限 API Key</text>
            <text x="40" y="124" class="svg-desc">· 看不到 OA 密码与 Cookie</text>

            <line x1="230" y1="82" x2="318" y2="82" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-s2)" />
            <text x="274" y="72" text-anchor="middle" class="svg-edge">MCP / HTTP</text>
            <text x="274" y="100" text-anchor="middle" class="svg-edge-muted">Bearer hk_...</text>

            <rect x="320" y="20" width="260" height="125" rx="14" class="svg-box svg-box-primary" />
            <text x="340" y="50" class="svg-title">Crab 权限网关 &amp; 规则引擎</text>
            <text x="340" y="76" class="svg-desc">1. 校验 Key 范围与只读限制</text>
            <text x="340" y="98" class="svg-desc">2. 会话失效时自动重登续期</text>
            <text x="340" y="120" class="svg-desc">3. 自动脱敏密码/Cookie 后返回</text>

            <line x1="580" y1="82" x2="668" y2="82" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-s2)" />
            <text x="624" y="72" text-anchor="middle" class="svg-edge">执行步骤</text>
            <text x="624" y="100" text-anchor="middle" class="svg-edge-muted">提取结构化 JSON</text>

            <rect x="670" y="34" width="190" height="96" rx="14" class="svg-box" />
            <text x="690" y="64" class="svg-title">目标业务系统</text>
            <text x="690" y="89" class="svg-desc">· 返回 HTML / JSON / XML</text>
            <text x="690" y="110" class="svg-desc">· 或附件文件流下载</text>
          </svg>
        </div>
      </div>

      <div class="steps-stack">
        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 1</span>
            <h3>订阅规则包并配置本机账号凭证</h3>
          </div>
          <p class="step-text">
            在 Crab 控制台「可订阅规则包」中找到目标系统点击<strong>订阅</strong>；随后进入「规则包与账号」，
            为该规则包新建账号（例如 <code class="mono">work</code>）并填入你的业务系统登录凭证。
            卡片状态变为绿色「可用：可以通过 OpenAPI 和 MCP 调用」即代表配置完成。
          </p>
          <figure class="shot-card single">
            <el-image
              :src="shotCrabConches"
              :preview-src-list="allScreenshots"
              :initial-index="3"
              fit="cover"
              class="shot-img"
              alt="Crab 规则包与账号管理界面截图"
            />
            <figcaption>
              <span>Crab「规则包与账号」页：同时管理从 Reef 订阅的正式包与本机调试草稿</span>
              <el-icon><ZoomIn /></el-icon>
            </figcaption>
          </figure>
        </article>

        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 2</span>
            <h3>按最小权限创建 API Key 并接入 MCP / OpenAPI</h3>
          </div>
          <p class="step-text">
            进入 Crab「API Key」点击「创建 API Key」，建议为 AI 工具勾选<strong>仅只读能力</strong>并限定允许访问的规则包
            （如只开放 <code class="mono">demo_oa</code>），避免 AI 误触发审批或删除操作。创建后既可以直接复制页面内置的
            「Agent 接入提示词」发给 AI，也可以手动填入以下配置：
          </p>
          <figure class="shot-card single">
            <el-image
              :src="shotCrabApiKeys"
              :preview-src-list="allScreenshots"
              :initial-index="4"
              fit="cover"
              class="shot-img"
              alt="Crab API Key 与 Agent 接入提示词界面截图"
            />
            <figcaption>
              <span>Crab「API Key」页：精细控制每个 Key 的规则包范围与只读权限</span>
              <el-icon><ZoomIn /></el-icon>
            </figcaption>
          </figure>

          <div class="code-grid">
            <div class="code-card">
              <div class="code-head">
                <span>MCP 客户端配置（Streamable HTTP）</span>
                <el-button text size="small" :icon="CopyDocument" @click="copyText(mcpConfigSnippet, 'MCP 配置已复制')">
                  复制
                </el-button>
              </div>
              <pre class="code-pre mono">{{ mcpConfigSnippet }}</pre>
            </div>
            <div class="code-card">
              <div class="code-head">
                <span>普通脚本调用（HTTP / OpenAPI）</span>
                <el-button text size="small" :icon="CopyDocument" @click="copyText(curlSnippet, 'curl 示例已复制')">
                  复制
                </el-button>
              </div>
              <pre class="code-pre mono">{{ curlSnippet }}</pre>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 场景 3：编写规则与审批发布 -->
    <section v-show="activeTab === 'authoring'" class="guide-section" data-test="guide-panel-authoring">
      <div class="hm-card">
        <div class="hm-card-title">
          <span>团队协作：本地开发 → 提交审批 → 签名发布</span>
          <el-tag type="warning" effect="plain">独立审批单 · 不占正式版本</el-tag>
        </div>
        <p class="lead">
          普通成员（<code class="mono">USER</code>）无需管理员权限，即可在本机调试好新规则包并提交到 Reef 审批队列。
          待审批记录独立存储、不会抢占正式版本号；管理员审核 Diff 差异与安全警告后一键完成 Ed25519 签名发布。
        </p>

        <div class="diagram-wrap" aria-label="规则包编写与审批发布流程图">
          <svg viewBox="0 0 880 155" class="diagram-svg" role="img">
            <defs>
              <marker id="arrow-s3" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M 0 1 L 10 5 L 0 9 z" fill="var(--el-color-primary)" />
              </marker>
            </defs>
            <rect x="16" y="34" width="180" height="86" rx="12" class="svg-box" />
            <text x="34" y="64" class="svg-title">1. 本机录制与调试</text>
            <text x="34" y="88" class="svg-desc">浏览器录制 / 导入 HAR</text>
            <text x="34" y="106" class="svg-desc">AI 写规则、试跑并导出</text>

            <line x1="196" y1="77" x2="236" y2="77" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-s3)" />

            <rect x="238" y="34" width="186" height="86" rx="12" class="svg-box svg-box-primary" />
            <text x="256" y="64" class="svg-title">2. 提交审批单</text>
            <text x="256" y="88" class="svg-desc">Reef「规则提交」上传</text>
            <text x="256" y="106" class="svg-desc">自动校验，可随时撤回</text>

            <line x1="424" y1="77" x2="464" y2="77" stroke="var(--el-color-primary)" stroke-width="2" marker-end="url(#arrow-s3)" />

            <rect x="466" y="34" width="186" height="86" rx="12" class="svg-box svg-box-accent" />
            <text x="484" y="64" class="svg-title">3. 管理员审核</text>
            <text x="484" y="88" class="svg-desc">核对 Diff &amp; 安全警告</text>
            <text x="484" y="106" class="svg-desc">查看完整 YAML 原文</text>

            <line x1="652" y1="62" x2="692" y2="48" stroke="var(--hm-success)" stroke-width="2" marker-end="url(#arrow-s3)" />
            <line x1="652" y1="92" x2="692" y2="106" stroke="var(--hm-danger)" stroke-width="1.6" stroke-dasharray="4 3" />

            <rect x="694" y="16" width="170" height="54" rx="10" class="svg-box svg-box-ok" />
            <text x="710" y="40" class="svg-title">批准并签名发布</text>
            <text x="710" y="58" class="svg-desc">进入正式目录供同步</text>

            <rect x="694" y="86" width="170" height="54" rx="10" class="svg-box svg-box-danger" />
            <text x="710" y="110" class="svg-title">写明原因驳回</text>
            <text x="710" y="128" class="svg-desc">修改后重新提交</text>
          </svg>
        </div>
      </div>

      <div class="steps-stack">
        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 1</span>
            <h3>录制抓包并一键交给 AI 生成规则（推荐，免手写 YAML）</h3>
          </div>
          <p class="step-text">
            在 Crab 概览打开「开发者模式」后，进入左侧「浏览器录制」：点「开始录制」会自动拉起独立空白 Chrome/Edge（不影响日常浏览器），
            完成登录与业务点击后点「停止录制」并「生成脱敏快照」（Cookie 值与密码自动抹除，仅存内存），最后点「复制给 AI」即可让已接入 MCP 的 AI 自动分析接口、编写并校验本地规则包。
            无 Chrome 环境也可直接「导入本地 .har 文件」，或跳过录制参考步骤 2 手动编写 YAML。
          </p>
          <figure class="shot-card single">
            <el-image
              :src="shotCrabRecorder"
              :preview-src-list="allScreenshots"
              :initial-index="6"
              fit="contain"
              class="shot-img shot-img-auto"
              alt="Crab 浏览器录制与脱敏快照截图"
            />
            <figcaption>
              <span>Crab「浏览器录制」：自动过滤静态噪声与脱敏，生成快照后一键「复制给 AI」或下载脱敏 HAR</span>
              <el-icon><ZoomIn /></el-icon>
            </figcaption>
          </figure>
        </article>

        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 2</span>
            <h3>本机试跑与导出 YAML（或参考最小模板手写）</h3>
          </div>
          <p class="step-text">
            无论是 AI 通过 MCP 保存还是在 Crab「规则包与账号」手动导入本地 YAML，都可以先配置本机测试账号并在开发者模式下试跑、查看每步请求与提取结果；
            验证无误后在规则包详情页点击「导出 YAML」。下面是一份可直接通过校验的最小示例模板：
          </p>
          <div class="code-card">
            <div class="code-head">
              <span>示例规则包模板（demo_oa.yaml）</span>
              <el-button text size="small" :icon="CopyDocument" @click="copyText(sampleRuleYaml, '规则包模板已复制')">
                复制模板
              </el-button>
            </div>
            <pre class="code-pre mono">{{ sampleRuleYaml }}</pre>
          </div>
        </article>

        <article class="hm-card step-block">
          <div class="step-header">
            <span class="step-index">步骤 3</span>
            <h3>在 Reef 提交审批并由管理员一键签发</h3>
          </div>
          <p class="step-text">
            进入 Reef 左侧「规则提交 / 规则审批」，点击右上角「提交规则包」上传 YAML 或 ZIP 并填写变更说明。
            若规则存在语法或安全错误（如明文密码、未声明凭证），Reef 会直接标出行号提示修改；
            校验通过后管理员会在右侧抽屉中看到实时版本差异、安全警告与 YAML 原文，一键「批准并发布」即可用 Reef 私钥签名上线。
          </p>
          <figure class="shot-card single">
            <el-image
              :src="shotApproval"
              :preview-src-list="allScreenshots"
              :initial-index="5"
              fit="cover"
              class="shot-img"
              alt="Reef 规则审批详情抽屉截图"
            />
            <figcaption>
              <span>Reef「规则审批」抽屉：展示版本 Diff、警告明细、YAML 原文与批准发布 / 驳回按钮</span>
              <el-icon><ZoomIn /></el-icon>
            </figcaption>
          </figure>
        </article>
      </div>
    </section>

    <!-- 场景 4：企业内网进阶场景速查 -->
    <section v-show="activeTab === 'intranet'" class="guide-section" data-test="guide-panel-intranet">
      <div class="tips-grid">
        <article class="hm-card tip-card">
          <div class="tip-icon"><el-icon :size="20"><Lock /></el-icon></div>
          <h3>1. 门户 / SSO 跳转登录（auth.origins）</h3>
          <p class="step-text">
            当业务系统需要跳转到独立门户或单点登录中心（如 <code class="mono">https://sso.example.internal</code>）完成认证时，
            在规则包中声明 <code class="mono">auth.origins</code>（并将 <code class="mono">min_crab_version</code> 设为
            <code class="mono">1.11.0</code> 或以上）。
          </p>
          <ul class="bullet-list">
            <li>只有 <code class="mono">auth.steps</code> 允许访问声明的认证地址并跨站跟随 301/302/303 重定向。</li>
            <li>业务能力步骤与 <code class="mono">alive_check</code> 仍严格锁定在 <code class="mono">base_url</code>。</li>
            <li>规则新增认证地址时，Reef 审批抽屉会自动发出 <code class="mono">AUTH_ORIGIN_ADDED</code> 安全警告供管理员复核。</li>
          </ul>
        </article>

        <article class="hm-card tip-card">
          <div class="tip-icon"><el-icon :size="20"><Setting /></el-icon></div>
          <h3>2. 内网自签 HTTPS 证书信任</h3>
          <p class="step-text">
            老系统使用企业内部 CA 或自签证书时，不要关闭全局 TLS 校验，推荐以下两种安全做法：
          </p>
          <ul class="bullet-list">
            <li><strong>组织根证书</strong>：把 PEM 格式内部 CA 证书放入 <code class="mono">$HERMIT_HOME/trust/ca/</code>，重启 Crab 自动信任。</li>
            <li><strong>单站点证书固定</strong>：在 Crab 控制台「HTTPS 信任」页输入主机名拉取证书指纹，核对后点击信任即可。</li>
          </ul>
        </article>

        <article class="hm-card tip-card">
          <div class="tip-icon"><el-icon :size="20"><Box /></el-icon></div>
          <h3>3. 多系统共用 SSO 会话（「身份」绑定）</h3>
          <p class="step-text">
            如果 OA、报销、考勤拆成了多个规则包，但它们共用同一套单点登录且后端限制单会话登录，互相顶号怎么办？
          </p>
          <ul class="bullet-list">
            <li>在 Crab 控制台「身份」页新建一个身份，把地址有交集的几个规则包账号绑定进去。</li>
            <li>绑定后这些账号共享同一个 Cookie 罐、HTTP 客户端和账号锁，一处登录、多处免登。</li>
            <li>各包的凭证、探活与失败计数依然独立管理，不同身份之间严格隔离。</li>
          </ul>
        </article>

        <article class="hm-card tip-card">
          <div class="tip-icon"><el-icon :size="20"><MagicStick /></el-icon></div>
          <h3>4. HTTP 200 登录页失效识别 &amp; 原生 MCP</h3>
          <p class="step-text">
            许多老系统会话过期时不返回 401/403，而是返回 HTTP 200 并在 JSON 里写 <code class="mono">code: 40100</code>
            或返回一段跳转登录页的 JS/HTML：
          </p>
          <ul class="bullet-list">
            <li>将 <code class="mono">min_crab_version</code> 设为 <code class="mono">1.12.0</code>，使用 <code class="mono">auth.expired_when</code> 或断言 <code class="mono">on_fail: session_expired</code>，Crab 会自动重新登录并重试当前能力。</li>
            <li>若目标服务本身已提供 Streamable HTTP MCP 端点，可直接编写 <code class="mono">driver: mcp</code> 规则包，由 Crab 统一处理请求头鉴权或 OAuth PKCE 登录并转发。</li>
          </ul>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.scenario-nav {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.scenario-btn {
  text-align: left;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1.5px solid var(--hm-border);
  background: var(--hm-surface);
  color: var(--hm-text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}

.scenario-btn:hover {
  border-color: var(--el-color-primary-light-3);
  background: var(--hm-subtle);
}

.scenario-btn.active {
  border-color: var(--el-color-primary);
  background: var(--hm-teal-soft);
  box-shadow: var(--hm-ring);
}

.scenario-badge {
  font-size: 11px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 0.04em;
}

.scenario-btn strong {
  font-size: 14.5px;
}

.scenario-sub {
  font-size: 12px;
  color: var(--hm-muted);
  line-height: 1.45;
}

.guide-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lead {
  margin: 0 0 14px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--hm-text);
}

.diagram-wrap {
  width: 100%;
  overflow-x: auto;
  padding: 8px 4px;
  border-radius: 12px;
  background: var(--hm-subtle);
  border: 1px solid var(--hm-border);
}

.diagram-svg {
  display: block;
  width: 100%;
  min-width: 680px;
  height: auto;
}

.svg-box {
  fill: var(--hm-surface);
  stroke: var(--hm-border);
  stroke-width: 1.5;
}

.svg-box-primary {
  stroke: var(--el-color-primary);
  stroke-width: 1.8;
}

.svg-box-accent {
  stroke: var(--hm-brand);
  stroke-width: 1.8;
}

.svg-box-ok {
  stroke: var(--hm-success);
  stroke-width: 1.6;
}

.svg-box-danger {
  stroke: var(--hm-danger);
  stroke-width: 1.5;
}

.svg-title {
  font-size: 14px;
  font-weight: 700;
  fill: var(--hm-text);
}

.svg-desc {
  font-size: 12px;
  fill: var(--hm-muted);
}

.svg-edge {
  font-size: 11.5px;
  font-weight: 600;
  fill: var(--el-color-primary);
}

.svg-edge-muted {
  font-size: 11px;
  fill: var(--hm-muted);
}

.svg-pill {
  fill: var(--hm-teal-soft);
}

.svg-pill-text {
  font-size: 11px;
  font-weight: 600;
  fill: var(--el-color-primary);
}

.steps-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.step-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.step-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step-header h3 {
  margin: 0;
  font-size: 16px;
}

.step-index {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--hm-teal-soft);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.step-text {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--hm-text);
}

.step-tip {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--hm-muted);
}

.shot-grid.cols-2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}

.shot-card {
  margin: 0;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--hm-border);
  background: var(--hm-subtle);
}

.shot-card.single {
  max-width: 920px;
}

.shot-img {
  display: block;
  width: 100%;
  aspect-ratio: 1200 / 806;
  cursor: zoom-in;
}

.shot-img-auto {
  aspect-ratio: auto;
}

.shot-card figcaption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 12px;
  font-size: 12.5px;
  color: var(--hm-muted);
  border-top: 1px solid var(--hm-border);
  background: var(--hm-surface);
}

.cmd-grid,
.code-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px;
}

.cmd-title {
  font-size: 12.5px;
  color: var(--hm-muted);
  margin-bottom: 6px;
}

.cmd-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 8px;
  background: var(--hm-code-bg);
  color: var(--hm-code-text);
  font-size: 12.5px;
}

.code-card {
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--hm-border);
}

.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--hm-subtle);
  border-bottom: 1px solid var(--hm-border);
  font-size: 12.5px;
  font-weight: 600;
}

.code-pre {
  margin: 0;
  padding: 12px 14px;
  background: var(--hm-code-bg);
  color: var(--hm-code-text);
  font-size: 12px;
  line-height: 1.55;
  overflow-x: auto;
  max-height: 360px;
}

.tips-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}

.tip-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tip-card h3 {
  margin: 0;
  font-size: 15.5px;
}

.tip-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--hm-teal-soft);
  color: var(--el-color-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.bullet-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--hm-text);
}

.bullet-list li + li {
  margin-top: 4px;
}
</style>
