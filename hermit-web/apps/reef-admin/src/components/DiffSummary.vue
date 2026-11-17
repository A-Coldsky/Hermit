<script setup lang="ts">
import { computed } from 'vue'
import type { ActionChange, ConchDiff, ToolChange } from '../api'

const props = defineProps<{ diff: Partial<ConchDiff> | null }>()

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
    v.toolChanges,
    v.addedScripts,
    v.removedScripts,
    v.changedScripts,
  ].some((list) => list && list.length)
})

const toolsAdded = computed(() => d.value.toolChanges?.filter((c) => c.kind === 'added') ?? [])
const toolsRemoved = computed(() => d.value.toolChanges?.filter((c) => c.kind === 'removed') ?? [])
const toolsChanged = computed(() => d.value.toolChanges?.filter((c) => c.kind === 'changed') ?? [])

/** 变了的方面；只列有变化的项。 */
function toolParts(change: ToolChange): string[] {
  const parts: string[] = []
  if (change.previousUpstreamName !== change.upstreamName) {
    parts.push(`上游名称 ${change.previousUpstreamName ?? '—'} → ${change.upstreamName ?? '—'}`)
  }
  if (change.previousSchemaSha256 !== change.schemaSha256) {
    parts.push('参数')
  }
  if (change.previousDescription !== change.description) {
    parts.push('说明')
  }
  if (change.previousReadOnly !== change.readOnly) {
    parts.push(change.readOnly ? '改为只读' : '改为写入')
  }
  if (change.previousDestructive !== change.destructive) {
    parts.push(change.destructive ? '变为破坏性' : '不再是破坏性')
  }
  if (change.previousConfirm !== change.confirm) {
    parts.push(change.confirm ? '改为需确认' : '不再需确认')
  }
  return parts
}

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
      <section
        v-if="d.addedScripts?.length || d.removedScripts?.length || d.changedScripts?.length"
        data-test="script-changes"
      >
        <h4>脚本库</h4>
        <div class="chips">
          <el-tag v-for="name in d.addedScripts" :key="`a-${name}`" type="success" class="mono">+ {{ name }}</el-tag>
          <el-tag v-for="name in d.removedScripts" :key="`r-${name}`" type="danger" class="mono">− {{ name }}</el-tag>
        </div>
        <ul v-if="d.changedScripts?.length" class="changes">
          <li v-for="name in d.changedScripts" :key="name">
            <span class="mono">~ {{ name }}</span>
            <el-tag size="small" type="warning" effect="plain">源码</el-tag>
          </li>
        </ul>
      </section>
      <section v-if="d.toolChanges?.length" data-test="tool-changes">
        <h4>MCP 工具</h4>
        <div class="chips">
          <el-tag v-for="change in toolsAdded" :key="`a-${change.name}`" type="success" class="mono">+ {{ change.name }}</el-tag>
          <el-tag v-for="change in toolsRemoved" :key="`r-${change.name}`" type="danger" class="mono">− {{ change.name }}</el-tag>
        </div>
        <ul v-if="toolsChanged.length" class="changes">
          <li v-for="change in toolsChanged" :key="change.name">
            <span class="mono">~ {{ change.name }}</span>
            <span class="hm-muted">变更了</span>
            <el-tag v-for="part in toolParts(change)" :key="part" size="small" type="warning" effect="plain">{{ part }}</el-tag>
          </li>
        </ul>
        <p v-if="toolsAdded.some((c) => c.destructive) || toolsChanged.some((c) => c.destructive && !c.previousDestructive)" class="warn">
          有新的破坏性工具。发布前请核对工具说明，以及调用前是否需要确认。
        </p>
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

.warn {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--el-color-warning);
}

.changes li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  flex-wrap: wrap;
}
</style>
