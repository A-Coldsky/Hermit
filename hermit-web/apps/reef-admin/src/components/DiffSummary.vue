<script setup lang="ts">
import { computed } from 'vue'
import type { ActionChange, ShellDiff } from '../api'

const props = defineProps<{ diff: Partial<ShellDiff> | null }>()

const changeParts: { key: keyof Omit<ActionChange, 'name'>; label: string }[] = [
  { key: 'input', label: '输入' },
  { key: 'output', label: '输出' },
  { key: 'steps', label: '步骤' },
  { key: 'method', label: '方法' },
  { key: 'mcp', label: 'MCP' },
]

const d = computed(() => props.diff ?? {})
const empty = computed(() => {
  const v = d.value
  return ![
    v.addedActions,
    v.removedActions,
    v.changedActions,
    v.addedCredentials,
    v.removedCredentials,
    v.credentialRequiredChanges,
    v.addedConfig,
    v.removedConfig,
    v.configDefaultChanges,
  ].some((list) => list && list.length)
})

function show(value: unknown): string {
  return value === undefined || value === null ? '（无）' : JSON.stringify(value)
}
</script>

<template>
  <div class="diff">
    <el-alert
      v-if="d.newRequiredCredentials?.length"
      type="warning"
      :closable="false"
      show-icon
      :title="`新增必填凭证：${d.newRequiredCredentials.join('、')}。Crab 同步后这些账号需要补填才能调用。`"
    />
    <div v-if="empty" class="hm-muted">与对比版本相比没有结构变化（或这是第一个版本）。</div>
    <template v-else>
      <section v-if="d.addedActions?.length || d.removedActions?.length || d.changedActions?.length">
        <h4>能力</h4>
        <div class="chips">
          <el-tag v-for="name in d.addedActions" :key="`a-${name}`" type="success" class="mono">+ {{ name }}</el-tag>
          <el-tag v-for="name in d.removedActions" :key="`r-${name}`" type="danger" class="mono">− {{ name }}</el-tag>
        </div>
        <ul v-if="d.changedActions?.length" class="changes">
          <li v-for="change in d.changedActions" :key="change.name">
            <span class="mono">~ {{ change.name }}</span>
            <span class="hm-muted">变更了</span>
            <el-tag v-for="part in changeParts.filter((p) => change[p.key])" :key="part.key" size="small" type="warning" effect="plain">
              {{ part.label }}
            </el-tag>
          </li>
        </ul>
      </section>
      <section v-if="d.addedCredentials?.length || d.removedCredentials?.length || d.credentialRequiredChanges?.length">
        <h4>凭证</h4>
        <div class="chips">
          <el-tag v-for="name in d.addedCredentials" :key="`a-${name}`" type="success" class="mono">+ {{ name }}</el-tag>
          <el-tag v-for="name in d.removedCredentials" :key="`r-${name}`" type="danger" class="mono">− {{ name }}</el-tag>
        </div>
        <ul v-if="d.credentialRequiredChanges?.length" class="changes">
          <li v-for="change in d.credentialRequiredChanges" :key="change.name">
            <span class="mono">~ {{ change.name }}</span>
            <span class="hm-muted">{{ change.before ? '必填' : '选填' }} → {{ change.after ? '必填' : '选填' }}</span>
          </li>
        </ul>
      </section>
      <section v-if="d.addedConfig?.length || d.removedConfig?.length || d.configDefaultChanges?.length">
        <h4>配置</h4>
        <div class="chips">
          <el-tag v-for="name in d.addedConfig" :key="`a-${name}`" type="success" class="mono">+ {{ name }}</el-tag>
          <el-tag v-for="name in d.removedConfig" :key="`r-${name}`" type="danger" class="mono">− {{ name }}</el-tag>
        </div>
        <ul v-if="d.configDefaultChanges?.length" class="changes">
          <li v-for="change in d.configDefaultChanges" :key="change.name">
            <span class="mono">~ {{ change.name }}</span>
            <span class="hm-muted">默认值 {{ show(change.before) }} → {{ show(change.after) }}</span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.diff {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hm-muted);
  font-weight: 600;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.changes {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.changes li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  flex-wrap: wrap;
}
</style>
