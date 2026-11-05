<script setup lang="ts">
import JsonBlock from '@shared/components/JsonBlock.vue'
import { millis } from '@shared/format'
import type { DevTrace, DevTraceExchange, DevTraceStep } from '../api'

defineProps<{ trace: DevTrace }>()

function stepTitle(step: DevTraceStep): string {
  if (step.method && step.path) {
    return `${step.type} · ${step.method} ${step.path}`
  }
  return step.type
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
      <el-collapse v-else>
        <el-collapse-item v-for="step in trace.steps" :key="step.index" :name="step.index">
          <template #title>
            <span>#{{ step.index }} {{ stepTitle(step) }}</span>
            <el-tag v-if="step.status != null" size="small" :type="step.status >= 400 ? 'danger' : 'success'" effect="plain">
              HTTP {{ step.status }}
            </el-tag>
            <span class="hm-muted">{{ millis(step.durationMs) }}</span>
            <el-tag v-if="step.truncated" size="small" type="warning" effect="plain">已截断</el-tag>
          </template>
          <p v-if="step.truncation" class="hm-muted warn">{{ step.truncation }}</p>
          <dl class="hm-kv">
            <template v-if="step.requestHeaders">
              <dt>请求头</dt>
              <dd><JsonBlock :value="step.requestHeaders" /></dd>
            </template>
            <template v-if="step.requestBody !== undefined">
              <dt>请求体</dt>
              <dd><JsonBlock :value="step.requestBody" /></dd>
            </template>
            <template v-if="step.responseHeaders">
              <dt>响应头</dt>
              <dd><JsonBlock :value="step.responseHeaders" /></dd>
            </template>
            <template v-if="step.responseBody !== undefined">
              <dt>响应体</dt>
              <dd><JsonBlock :value="step.responseBody" /></dd>
            </template>
            <template v-if="step.extractors?.length">
              <dt>提取</dt>
              <dd>{{ step.extractors.join('、') }}</dd>
            </template>
            <template v-if="step.assertions?.length">
              <dt>断言</dt>
              <dd>{{ step.assertions.join('、') }}{{ step.passed === false ? '（未通过）' : '' }}</dd>
            </template>
          </dl>
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
              <dd><JsonBlock :value="item.error" /></dd>
            </template>
          </dl>
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

:deep(.el-collapse-item__header) {
  gap: 8px;
}
</style>
