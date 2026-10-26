<script setup lang="ts">
import type { ValidationIssue } from '../api'
import { issueHints } from '../labels'

defineProps<{ issues: ValidationIssue[]; tone: 'error' | 'warning' }>()
</script>

<template>
  <ul class="issues" :class="tone">
    <li v-for="(issue, index) in issues" :key="`${issue.code}-${issue.path}-${index}`">
      <div class="head">
        <span class="title">{{ issueHints[issue.code]?.title ?? issue.message }}</span>
        <code>{{ issue.path || '（根）' }}</code>
      </div>
      <div v-if="issueHints[issue.code]" class="hint">{{ issueHints[issue.code].hint }}</div>
      <div v-if="issueHints[issue.code] && issue.message !== issueHints[issue.code].title" class="detail">
        {{ issue.message }} <span class="code">{{ issue.code }}</span>
      </div>
      <div v-else class="detail"><span class="code">{{ issue.code }}</span></div>
    </li>
  </ul>
</template>

<style scoped>
.issues {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

li {
  border-left: 3px solid var(--hm-accent);
  background: var(--el-color-warning-light-9);
  padding: 8px 12px;
  border-radius: 0 6px 6px 0;
  font-size: 13px;
}

.error li {
  border-left-color: var(--hm-danger);
  background: var(--el-color-danger-light-9);
}

.head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 4px;
}

.title {
  font-weight: 600;
}

code {
  font-family: var(--hm-mono);
  font-size: 12px;
  color: var(--hm-muted);
}

.hint {
  line-height: 1.6;
}

.detail {
  margin-top: 2px;
  font-size: 12px;
  color: var(--hm-muted);
}

.code {
  font-size: 11px;
  color: var(--hm-muted);
  font-family: var(--hm-mono);
}
</style>
