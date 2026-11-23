<script setup lang="ts">
import { computed, ref } from 'vue'
import { buildGroupTree, type GroupDef } from '@shared/groups'
import type { Capability } from '../api'
import CapabilityItems from './CapabilityItems.vue'

const props = defineProps<{
  actions: Record<string, Capability>
  groups?: GroupDef[]
}>()

const open = ref<string[]>([])
const collapsed = ref<Record<string, boolean>>({})
const list = computed(() => Object.values(props.actions))
const grouped = computed(() => buildGroupTree(props.groups, list.value, (cap) => cap.group))

function isOpen(path: string): boolean {
  return !collapsed.value[path]
}

function toggleFold(path: string) {
  collapsed.value = { ...collapsed.value, [path]: !collapsed.value[path] }
}

function openRelated(name: string) {
  if (!open.value.includes(name)) {
    open.value = [...open.value, name]
  }
  const target = props.actions[name]
  if (target?.group) {
    const next = { ...collapsed.value }
    next[target.group] = false
    const slash = target.group.indexOf('/')
    if (slash > 0) {
      next[target.group.slice(0, slash)] = false
    }
    collapsed.value = next
  }
}
</script>

<template>
  <el-empty v-if="!list.length" description="这个版本没有声明能力" />
  <CapabilityItems
    v-else-if="!grouped.hasGroups"
    v-model:open="open"
    :items="list"
    @open-related="openRelated"
  />
  <div v-else class="grouped-caps" data-test="reef-grouped-capabilities">
    <CapabilityItems
      v-if="grouped.topLevel.length"
      v-model:open="open"
      :items="grouped.topLevel"
      data-test="reef-top-level"
      @open-related="openRelated"
    />

    <section
      v-for="group in grouped.groups"
      :key="group.path"
      class="group-box"
      :data-test="`reef-group-${group.path}`"
    >
      <header class="group-header" @click="toggleFold(group.path)">
        <span class="caret" :class="{ open: isOpen(group.path) }">▸</span>
        <strong class="group-title">{{ group.title }}</strong>
        <span class="mono hm-muted group-key">{{ group.name }}</span>
        <el-tag size="small" effect="plain">{{ group.all.length }} 个能力</el-tag>
        <span v-if="group.description" class="hm-muted group-desc">{{ group.description }}</span>
      </header>
      <div v-show="isOpen(group.path)" class="group-body">
        <p v-if="!group.all.length" class="hm-muted empty-group">该分组下没有能力</p>
        <template v-else>
          <CapabilityItems
            v-if="group.direct.length"
            v-model:open="open"
            :items="group.direct"
            @open-related="openRelated"
          />
          <div
            v-for="child in group.children"
            :key="child.path"
            class="subgroup-box"
            :data-test="`reef-group-${child.path}`"
          >
            <header class="group-header subgroup-header" @click="toggleFold(child.path)">
              <span class="caret" :class="{ open: isOpen(child.path) }">▸</span>
              <strong class="group-title">{{ child.title }}</strong>
              <span class="mono hm-muted group-key">{{ child.path }}</span>
              <el-tag size="small" effect="plain">{{ child.all.length }} 个能力</el-tag>
              <span v-if="child.description" class="hm-muted group-desc">{{ child.description }}</span>
            </header>
            <div v-show="isOpen(child.path)" class="subgroup-body">
              <p v-if="!child.all.length" class="hm-muted empty-group">该子分组下没有能力</p>
              <CapabilityItems
                v-else
                v-model:open="open"
                :items="child.all"
                @open-related="openRelated"
              />
            </div>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.grouped-caps {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.group-box {
  border: 1px solid var(--hm-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--hm-surface);
}

.group-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  cursor: pointer;
  user-select: none;
}

.subgroup-header {
  background: var(--el-fill-color-lighter);
  padding: 8px 12px;
}

.caret {
  display: inline-block;
  font-size: 12px;
  color: var(--hm-muted);
  transition: transform 0.15s ease;
}

.caret.open {
  transform: rotate(90deg);
}

.group-title {
  font-size: 14px;
}

.group-key {
  font-size: 12px;
}

.group-desc {
  font-size: 12.5px;
}

.group-body {
  padding: 10px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.subgroup-box {
  border: 1px solid var(--hm-border);
  border-radius: 6px;
  overflow: hidden;
}

.subgroup-body {
  padding: 8px 10px 10px;
}

.empty-group {
  margin: 4px 2px;
  font-size: 13px;
}
</style>
