<script setup lang="ts">
import JsonBlock from '@shared/components/JsonBlock.vue'
import type { Capability } from '../api'

defineProps<{
  items: Capability[]
  open: string[]
}>()

const emit = defineEmits<{
  'update:open': [names: string[]]
  openRelated: [name: string]
}>()

function methodType(method: string): 'success' | 'warning' {
  return method === 'GET' ? 'success' : 'warning'
}

function onOpenChange(v: string | number | Array<string | number>) {
  emit('update:open', Array.isArray(v) ? v.map(String) : [String(v)])
}
</script>

<template>
  <el-collapse :model-value="open" class="caps" @update:model-value="onOpenChange">
    <el-collapse-item v-for="cap in items" :key="cap.name" :name="cap.name">
      <template #title>
        <div class="title">
          <el-tag :type="methodType(cap.effectiveMethod)" size="small" class="method mono">{{ cap.effectiveMethod }}</el-tag>
          <span class="mono name">{{ cap.name }}</span>
          <span class="label">{{ cap.title }}</span>
          <el-tag v-if="!cap.mcp.expose" size="small" type="info" effect="plain">不暴露给 MCP</el-tag>
          <el-tag v-if="cap.mcp.readOnly" size="small" effect="plain">只读</el-tag>
          <el-tag v-if="cap.mcp.destructive" size="small" type="danger" effect="plain">破坏性</el-tag>
        </div>
      </template>
      <p class="desc">{{ cap.description }}</p>
      <el-alert v-if="cap.notes" :title="cap.notes" type="info" :closable="false" class="notes" />
      <div class="hm-grid cols-2">
        <div>
          <h4>输入 Schema</h4>
          <JsonBlock :value="cap.inputJsonSchema" />
        </div>
        <div>
          <h4>输出 Schema</h4>
          <JsonBlock :value="cap.outputJsonSchema" />
        </div>
      </div>
      <template v-if="cap.examples.length">
        <h4>示例</h4>
        <div v-for="(example, index) in cap.examples" :key="index" class="hm-grid cols-2 example">
          <JsonBlock :value="example.input" />
          <JsonBlock :value="example.output" />
        </div>
      </template>
      <div v-if="cap.related.length" class="related">
        <span class="hm-muted">相关能力：</span>
        <el-tag v-for="name in cap.related" :key="name" size="small" class="mono" @click="emit('openRelated', name)">{{ name }}</el-tag>
      </div>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.caps {
  background: var(--hm-surface);
  border-radius: 8px;
  padding: 0 16px;
  box-shadow: var(--hm-shadow);
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.method {
  min-width: 48px;
  text-align: center;
}

.name {
  font-weight: 600;
}

.label {
  color: var(--hm-muted);
}

.desc {
  margin: 0 0 12px;
  line-height: 1.7;
}

.notes {
  margin-bottom: 12px;
}

h4 {
  margin: 12px 0 8px;
  font-size: 13px;
  color: var(--hm-muted);
}

.example + .example {
  margin-top: 8px;
}

.related {
  margin-top: 12px;
  display: flex;
  gap: 6px;
  align-items: center;
}

.related .el-tag {
  cursor: pointer;
}
</style>
