<script setup lang="ts">
import type { ValidationIssue } from '../api'

defineProps<{ issues: ValidationIssue[]; tone: 'error' | 'warning' }>()
</script>

<template>
  <ul class="issues" :class="tone">
    <li v-for="(issue, index) in issues" :key="`${issue.code}-${issue.path}-${index}`">
      <div class="head">
        <code>{{ issue.path || '（根）' }}</code>
        <span class="code">{{ issue.code }}</span>
      </div>
      <div>{{ issue.message }}</div>
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
  background: #fffbeb;
  padding: 8px 12px;
  border-radius: 0 6px 6px 0;
  font-size: 13px;
}

.error li {
  border-left-color: var(--hm-danger);
  background: #fef2f2;
}

.head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

code {
  font-family: var(--hm-mono);
  font-weight: 600;
}

.code {
  font-size: 11px;
  color: var(--hm-muted);
  font-family: var(--hm-mono);
}
</style>
