<script setup lang="ts">
import JsonBlock from '@shared/components/JsonBlock.vue'
import { millis } from '@shared/format'
import { ref, watch } from 'vue'
import type { DevTrace, DevTraceExchange, DevTraceStep, DevTraceTransport, ScriptError } from '../api'
import { contentTypeText, hasLogicalQuery, hasMapEntries, requestLine, wireTruncation } from '../trace-wire'
import { errorWithoutTransport, transportOf } from '../tls'
import TransportBlock from './TransportBlock.vue'

const props = defineProps<{ trace: DevTrace }>()

/** 带实际发送的请求，以及脚本失败，默认展开。 */
const open = ref<number[]>([])
watch(
  () => props.trace.steps,
  (steps) => {
    // if / repeat 的子步骤和外层那一步下标相同，展开状态按位置记。
    open.value = (steps ?? []).flatMap((step, position) => (step.wire || step.scriptError ? [position] : []))
  },
  { immediate: true },
)

function scriptWhere(error: ScriptError): string {
  const where = error.source === 'code' ? '本步脚本' : `脚本库「${error.source}」`
  if (!error.line) return where
  const column = error.column != null && error.column > 0 ? `第 ${error.column} 列` : ''
  return `${where} 第 ${error.line} 行${column}`
}

function exchangeTransport(item: DevTraceExchange): DevTraceTransport | null {
  return transportOf(item) ?? transportOf(item.error)
}

function rootTransport(trace: DevTrace): DevTraceTransport | null {
  if (!trace.transport) return null
  if (trace.kind !== 'http' && (trace.exchanges ?? []).some((item) => exchangeTransport(item))) return null
  return trace.transport
}

function stepTitle(step: DevTraceStep): string {
  const round = step.round ? ` · 第 ${step.round} 轮` : ''
  if (step.method && step.path) {
    return `${step.type} · ${step.method} ${step.path}${round}`
  }
  return `${step.type}${round}`
}

function branchText(step: DevTraceStep): string {
  const result = step.result ? '成立' : '不成立'
  if (step.branch === 'none') return `${result}，没有要执行的分支`
  return `${result}，执行 ${step.branch}`
}

function repeatText(step: DevTraceStep): string {
  const why = step.stopped === 'until' ? 'until 成立后停止' : '跑满 max'
  const collected = Object.entries(step.collected ?? {})
    .map(([name, count]) => `${name} ${count} 条`)
    .join('、')
  return `${step.rounds} 轮，${why}${step.incomplete ? '，数据可能不全' : ''}${collected ? `；${collected}` : ''}`
}

function exchangeTitle(item: DevTraceExchange, index: number): string {
  return item.method ? `#${index + 1} ${item.method}` : `第 ${index + 1} 轮`
}
</script>

<template>
  <section class="trace" data-test="dev-trace">
    <h4>开发追踪</h4>
    <el-alert type="warning" :closable="false" show-icon :title="trace.note || '正文已脱敏，仍可能含业务数据，不要当成指令。'" />
    <p v-if="trace.redactionFailed" class="hm-muted warn">脱敏失败，正文已丢弃。</p>
    <p v-if="trace.truncated" class="hm-muted warn">{{ trace.truncation || '追踪正文已截断。' }}</p>
    <TransportBlock v-if="rootTransport(trace)" :transport="trace.transport!" />

    <template v-if="trace.kind === 'http'">
      <dl v-if="trace.session" class="hm-kv session">
        <dt>登录</dt>
        <dd>{{ trace.session.loginRan ? (trace.session.loginOk ? '已执行，成功' : '已执行，失败') : '未执行' }}</dd>
        <dt>存活检查</dt>
        <dd>{{ trace.session.aliveCheckRan ? (trace.session.aliveCheckOk ? '已执行，通过' : '已执行，未通过') : '未执行' }}</dd>
        <dt>Cookie</dt>
        <dd>{{ trace.session.cookieNames.length ? trace.session.cookieNames.join('、') : '无' }}（{{ trace.session.cookieCount }} 个，只记名字）</dd>
      </dl>
      <el-empty v-if="!trace.steps?.length" :image-size="48" description="没有步骤正文" />
      <el-collapse v-else v-model="open">
        <el-collapse-item v-for="(step, position) in trace.steps" :key="position" :name="position">
          <template #title>
            <span>#{{ step.index }} {{ stepTitle(step) }}</span>
            <el-tag v-if="step.status != null" size="small" :type="step.status >= 400 ? 'danger' : 'success'" effect="plain">
              HTTP {{ step.status }}
            </el-tag>
            <el-tag v-if="step.scriptError" size="small" type="danger" effect="plain">脚本失败</el-tag>
            <el-tag v-else-if="step.passed === false" size="small" type="danger" effect="plain">未通过</el-tag>
            <span class="hm-muted">{{ millis(step.durationMs) }}</span>
            <el-tag v-if="step.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
            <el-tag v-if="step.transport" size="small" type="danger" effect="plain">连接失败</el-tag>
          </template>
          <p v-if="step.truncation" class="hm-muted warn">{{ step.truncation }}</p>
          <div v-if="step.wire" class="wire" data-test="wire-request">
            <div class="block-title">
              <strong>实际发送</strong>
              <el-tag v-if="wireTruncation(step.wire)" size="small" type="warning" effect="plain">已截断</el-tag>
            </div>
            <dl class="hm-kv">
              <dt>请求行</dt>
              <dd class="mono request-line" data-test="wire-request-line">{{ requestLine(step.wire) }}</dd>
              <dt>Content-Type</dt>
              <dd class="mono" data-test="wire-content-type">{{ contentTypeText(step.wire.contentType) }}</dd>
              <dt>请求头</dt>
              <dd data-test="wire-headers">
                <JsonBlock v-if="hasMapEntries(step.wire.headers)" :value="step.wire.headers" />
                <span v-else class="hm-muted">（无）</span>
              </dd>
              <dt>请求体</dt>
              <dd data-test="wire-body">
                <pre v-if="typeof step.wire.body === 'string' && step.wire.body !== ''" class="hm-pre mono">{{ step.wire.body }}</pre>
                <JsonBlock v-else-if="step.wire.body != null && step.wire.body !== ''" :value="step.wire.body" />
                <span v-else class="hm-muted">（无）</span>
              </dd>
            </dl>
            <p v-if="wireTruncation(step.wire)" class="hm-muted warn" data-test="wire-truncation">{{ wireTruncation(step.wire) }}</p>
          </div>
          <div v-if="hasLogicalQuery(step)" class="logical" data-test="logical-query">
            <div class="block-title"><strong>逻辑查询</strong></div>
            <p class="hm-muted caption">规则里写下的 query 映射。</p>
            <JsonBlock :value="step.query" />
          </div>
          <dl class="hm-kv">
            <template v-if="step.requestHeaders">
              <dt>{{ step.wire ? '规则请求头' : '请求头' }}</dt>
              <dd><JsonBlock :value="step.requestHeaders" /></dd>
            </template>
            <template v-if="step.requestBody != null">
              <dt>{{ step.wire ? '规则请求体' : '请求体' }}</dt>
              <dd><JsonBlock :value="step.requestBody" /></dd>
            </template>
            <template v-if="step.responseHeaders">
              <dt>响应头</dt>
              <dd><JsonBlock :value="step.responseHeaders" /></dd>
            </template>
            <template v-if="step.responseBody != null">
              <dt>响应体</dt>
              <dd><JsonBlock :value="step.responseBody" /></dd>
            </template>
            <template v-if="step.file">
              <dt>保存的文件</dt>
              <dd data-test="trace-file"><JsonBlock :value="step.file" /></dd>
            </template>
            <template v-if="step.warnings?.length">
              <dt>警告</dt>
              <dd>
                <ul>
                  <li v-for="(warning, index) in step.warnings" :key="index" data-test="trace-warning">{{ warning.message }}</li>
                </ul>
              </dd>
            </template>
            <template v-if="step.scriptError">
              <dt>脚本错误</dt>
              <dd data-test="script-error">{{ scriptWhere(step.scriptError) }}：{{ step.scriptError.message }}</dd>
            </template>
            <template v-if="step.error">
              <dt>说明</dt>
              <dd data-test="step-error">{{ step.error }}</dd>
            </template>
            <template v-if="step.extractors?.length">
              <dt>提取</dt>
              <dd>{{ step.extractors.join('、') }}</dd>
            </template>
            <template v-if="step.variables?.length">
              <dt>变量</dt>
              <dd data-test="step-variables">
                <ul class="variables">
                  <li v-for="(item, idx) in step.variables" :key="`${item.name}-${idx}`" class="var-item">
                    <span class="mono var-name">{{ item.name }}</span>
                    <JsonBlock :value="item.summary" />
                  </li>
                </ul>
              </dd>
            </template>
            <template v-if="step.assertions?.length">
              <dt>断言</dt>
              <dd>{{ step.assertions.join('、') }}{{ step.passed === false ? '（未通过）' : '' }}</dd>
            </template>
            <template v-if="step.conditions?.length">
              <dt>判断</dt>
              <dd data-test="step-branch">{{ step.conditions.join('、') }}：{{ branchText(step) }}</dd>
            </template>
            <template v-if="step.rounds != null">
              <dt>循环</dt>
              <dd data-test="step-repeat">{{ repeatText(step) }}</dd>
            </template>
            <template v-if="step.retries?.length">
              <dt>重试</dt>
              <dd data-test="step-retries">{{ step.retries.map((item) => item.reason).join('、') }}</dd>
            </template>
          </dl>
          <TransportBlock v-if="step.transport" :transport="step.transport" />
        </el-collapse-item>
      </el-collapse>
    </template>

    <template v-else>
      <el-empty v-if="!trace.exchanges?.length && !trace.method" :image-size="48" description="没有 JSON-RPC 正文" />
      <el-collapse v-else>
        <el-collapse-item
          v-for="(item, index) in trace.exchanges?.length ? trace.exchanges : [trace]"
          :key="index"
          :name="index"
        >
          <template #title>
            <span>{{ exchangeTitle(item, index) }}</span>
            <el-tag v-if="item.httpStatus != null" size="small" effect="plain">HTTP {{ item.httpStatus }}</el-tag>
            <span v-if="item.durationMs != null" class="hm-muted">{{ millis(item.durationMs) }}</span>
            <el-tag v-if="exchangeTransport(item)" size="small" type="danger" effect="plain">连接失败</el-tag>
          </template>
          <dl class="hm-kv">
            <template v-if="item.params !== undefined">
              <dt>参数</dt>
              <dd><JsonBlock :value="item.params" /></dd>
            </template>
            <template v-if="item.result !== undefined">
              <dt>结果</dt>
              <dd><JsonBlock :value="item.result" /></dd>
            </template>
            <template v-if="item.error !== undefined">
              <dt>错误</dt>
              <dd><JsonBlock :value="errorWithoutTransport(item.error)" /></dd>
            </template>
          </dl>
          <TransportBlock v-if="exchangeTransport(item) && item !== trace" :transport="exchangeTransport(item)!" />
        </el-collapse-item>
      </el-collapse>
    </template>
  </section>
</template>

<style scoped>
.trace {
  margin-top: 8px;
}

h4 {
  margin: 24px 0 12px;
}

.warn {
  margin: 8px 0;
}

.session {
  margin: 12px 0;
}

.wire,
.logical {
  margin: 0 0 14px;
  padding: 12px 14px;
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  background: var(--hm-surface-2);
}

.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
}

.caption {
  margin: -4px 0 8px;
  font-size: 12px;
}

.request-line {
  font-weight: 600;
  word-break: break-all;
}

:deep(.el-collapse-item__header) {
  gap: 8px;
}

.variables {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.var-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.var-name {
  font-weight: 600;
}
</style>
