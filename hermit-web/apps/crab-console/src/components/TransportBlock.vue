<script setup lang="ts">
import type { DevTraceTransport } from '../api'
import { plainFailure, transportKindLabels } from '../tls'
import CertChain from './CertChain.vue'
import TrustJump from './TrustJump.vue'

defineProps<{ transport: DevTraceTransport }>()
</script>

<template>
  <div class="transport" data-test="trace-transport">
    <p class="plain">{{ plainFailure(transport) }}</p>
    <TrustJump :kind="transport.kind" :host="transport.host" :port="transport.port" />
    <details data-test="transport-detail">
      <summary>详情</summary>
      <dl class="hm-kv">
        <dt>种类</dt>
        <dd>{{ transportKindLabels[transport.kind] ?? transport.kind }} <span class="mono hm-muted">{{ transport.kind }}</span></dd>
        <dt>地址</dt>
        <dd class="mono">{{ transport.host }}<template v-if="transport.port > 0">:{{ transport.port }}</template></dd>
        <template v-if="transport.cause">
          <dt>根因</dt>
          <dd class="mono cause">{{ transport.cause }}</dd>
        </template>
      </dl>
      <CertChain :chain="transport.chain ?? []" />
    </details>
  </div>
</template>

<style scoped>
.transport {
  margin-top: 12px;
}

.plain {
  margin: 0 0 8px;
}

details {
  margin-top: 8px;
}

summary {
  cursor: pointer;
  color: var(--hm-muted);
}

.hm-kv {
  margin-top: 10px;
}

.cause {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
