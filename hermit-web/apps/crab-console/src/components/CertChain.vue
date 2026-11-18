<script setup lang="ts">
import TimeText from '@shared/components/TimeText.vue'
import type { TlsCertificate } from '../api'
import { expiryState } from '../tls'

defineProps<{ chain: TlsCertificate[] }>()
</script>

<template>
  <ul v-if="chain.length" class="chain" data-test="cert-chain">
    <li v-for="(cert, index) in chain" :key="`${cert.sha256}-${index}`">
      <div><span class="hm-muted">主题</span> {{ cert.subject || '—' }}</div>
      <div><span class="hm-muted">颁发者</span> {{ cert.issuer || '—' }}</div>
      <div>
        <span class="hm-muted">指纹</span>
        <span class="mono">{{ cert.sha256 || '—' }}</span>
      </div>
      <div class="meta">
        <span class="hm-muted">到期</span>
        <TimeText :value="cert.notAfter" />
        <el-tag v-if="expiryState(cert.notAfter) === 'soon'" size="small" type="warning" effect="plain">30 天内到期</el-tag>
        <el-tag v-else-if="expiryState(cert.notAfter) === 'expired'" size="small" type="danger" effect="plain">已过期</el-tag>
        <el-tag v-if="cert.ca === false" size="small" type="info" effect="plain">非 CA</el-tag>
      </div>
    </li>
  </ul>
  <p v-else class="hm-muted empty">没有证书信息。</p>
</template>

<style scoped>
.chain {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chain li {
  padding: 10px 12px;
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  background: var(--hm-subtle);
  line-height: 1.6;
}

.mono {
  overflow-wrap: anywhere;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.empty {
  margin: 8px 0 0;
}
</style>
