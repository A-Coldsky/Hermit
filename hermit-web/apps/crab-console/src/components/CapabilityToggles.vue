<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'
import { notifyError } from '@shared/format'
import { buildGroupTree, type GroupDef, type GroupTreeNode } from '@shared/groups'
import { api, type CapabilityState } from '../api'
import CapabilityToggleTable from './CapabilityToggleTable.vue'

const props = defineProps<{
  conch: string
  capabilities: CapabilityState[]
  groups?: GroupDef[]
  emptyText?: string
  /** 这版已停用：能力调不了，开关也先不能拨，免得看起来还能用。 */
  ruleDisabled?: boolean
}>()

const busyAction = ref<string | null>(null)
const busyGroup = ref<string | null>(null)

const grouped = computed(() =>
  buildGroupTree(props.groups, props.capabilities, (cap) => cap.group),
)

const collapsed = ref<Record<string, boolean>>({})

watch(
  () => props.conch,
  () => {
    collapsed.value = {}
  },
)

function isOpen(path: string): boolean {
  return !collapsed.value[path]
}

function toggleFold(path: string) {
  collapsed.value = { ...collapsed.value, [path]: !collapsed.value[path] }
}

function allGroupPaths(): string[] {
  const paths: string[] = []
  for (const g of grouped.value.groups) {
    paths.push(g.path)
    for (const c of g.children) {
      paths.push(c.path)
    }
  }
  return paths
}

const allCollapsed = computed(() => {
  const paths = allGroupPaths()
  return paths.length > 0 && paths.every((p) => collapsed.value[p])
})

function toggleAllFold() {
  const next: Record<string, boolean> = {}
  const collapse = !allCollapsed.value
  for (const path of allGroupPaths()) {
    next[path] = collapse
  }
  collapsed.value = next
}

function enabledCount(node: GroupTreeNode<CapabilityState>): number {
  return node.all.filter((c) => !c.disabled).length
}

function groupAllEnabled(node: GroupTreeNode<CapabilityState>): boolean {
  return node.all.length > 0 && enabledCount(node) === node.all.length
}

function groupStatusText(node: GroupTreeNode<CapabilityState>): string {
  if (!node.all.length) return '无能力'
  const on = enabledCount(node)
  if (on === node.all.length) return `已启用 ${on}/${node.all.length}`
  if (on === 0) return `已全部停用 (0/${node.all.length})`
  return `部分启用 (${on}/${node.all.length})`
}

function groupStatusType(node: GroupTreeNode<CapabilityState>): 'success' | 'info' | 'warning' {
  if (!node.all.length) return 'info'
  const on = enabledCount(node)
  if (on === node.all.length) return 'success'
  if (on === 0) return 'info'
  return 'warning'
}

const anyBusy = computed(() => busyAction.value !== null || busyGroup.value !== null)

async function toggle(cap: CapabilityState, enabled: boolean) {
  if (anyBusy.value) return
  busyAction.value = cap.action
  try {
    const result = await api.setCapability(props.conch, cap.action, enabled)
    cap.disabled = result.disabled
    ElMessage.success(result.disabled ? `已停用 ${cap.action}` : `已启用 ${cap.action}`)
  } catch (error) {
    notifyError(error)
  } finally {
    busyAction.value = null
  }
}

async function toggleGroup(node: GroupTreeNode<CapabilityState>, enabled: boolean) {
  if (!node.all.length || anyBusy.value) return
  busyGroup.value = node.path
  try {
    const result = await api.setGroup(props.conch, node.path, enabled)
    const updated = new Map(result.actions.map((item) => [item.action, item.disabled]))
    for (const cap of props.capabilities) {
      const state = updated.get(cap.action)
      if (state !== undefined) {
        cap.disabled = state
      }
    }
    ElMessage.success(
      enabled
        ? `已启用分组「${node.title}」（${result.actions.length} 个能力）`
        : `已停用分组「${node.title}」（${result.actions.length} 个能力）`,
    )
  } catch (error) {
    notifyError(error)
  } finally {
    busyGroup.value = null
  }
}
</script>

<template>
  <div class="hm-card">
    <div class="hm-card-title">
      <span>能力</span>
      <el-button
        v-if="grouped.hasGroups && grouped.groups.length"
        link
        type="primary"
        size="small"
        data-test="toggle-all-groups"
        @click="toggleAllFold"
      >
        {{ allCollapsed ? '展开全部分组' : '折叠全部分组' }}
      </el-button>
    </div>
    <p v-if="ruleDisabled" class="hm-muted hint" data-test="toggles-disabled">
      这版已停用，下面的能力暂时都不能调用，开关先不能用。
    </p>
    <p v-else class="hm-muted hint">
      停用后这个能力不再出现在 OpenAPI 文档和 MCP 工具列表里，调用会被拒绝。<template v-if="grouped.hasGroups">可以按分组整批启停，也可以单独调整某个能力。</template>
    </p>

    <CapabilityToggleTable
      v-if="!grouped.hasGroups"
      :conch="conch"
      :capabilities="capabilities"
      :empty-text="emptyText"
      :rule-disabled="ruleDisabled"
      :busy-action="busyAction"
      :busy-group="busyGroup"
      @toggle="toggle"
    />

    <div v-else class="grouped-root" data-test="grouped-capabilities">
      <div v-if="grouped.topLevel.length" class="top-level" data-test="top-level-capabilities">
        <CapabilityToggleTable
          :conch="conch"
          :capabilities="grouped.topLevel"
          :rule-disabled="ruleDisabled"
          :busy-action="busyAction"
          :busy-group="busyGroup"
          @toggle="toggle"
        />
      </div>

      <section
        v-for="group in grouped.groups"
        :key="group.path"
        class="group-box"
        :data-test="`group-section-${group.path}`"
      >
        <header class="group-header" @click="toggleFold(group.path)">
          <div class="group-meta">
            <span class="caret" :class="{ open: isOpen(group.path) }">▸</span>
            <strong class="group-title">{{ group.title }}</strong>
            <span class="mono hm-muted group-key">{{ group.name }}</span>
            <el-tag :type="groupStatusType(group)" size="small" effect="plain" :data-test="`group-status-${group.path}`">
              {{ groupStatusText(group) }}
            </el-tag>
            <span v-if="group.description" class="hm-muted group-desc">{{ group.description }}</span>
          </div>
          <div class="group-actions" @click.stop>
            <el-switch
              :model-value="!ruleDisabled && groupAllEnabled(group)"
              :disabled="ruleDisabled || !group.all.length || anyBusy"
              :loading="busyGroup === group.path"
              :aria-label="`开关分组 ${group.title}`"
              :data-test="`group-switch-${group.path}`"
              @change="(v: string | number | boolean) => toggleGroup(group, Boolean(v))"
            />
          </div>
        </header>

        <div v-show="isOpen(group.path)" class="group-body">
          <p v-if="!group.all.length" class="hm-muted empty-group">该分组下没有能力</p>
          <template v-else>
            <CapabilityToggleTable
              v-if="group.direct.length"
              :conch="conch"
              :capabilities="group.direct"
              :rule-disabled="ruleDisabled"
              :busy-action="busyAction"
              :busy-group="busyGroup"
              @toggle="toggle"
            />
            <div
              v-for="child in group.children"
              :key="child.path"
              class="subgroup-box"
              :data-test="`group-section-${child.path}`"
            >
              <header class="group-header subgroup-header" @click="toggleFold(child.path)">
                <div class="group-meta">
                  <span class="caret" :class="{ open: isOpen(child.path) }">▸</span>
                  <strong class="group-title">{{ child.title }}</strong>
                  <span class="mono hm-muted group-key">{{ child.path }}</span>
                  <el-tag :type="groupStatusType(child)" size="small" effect="plain" :data-test="`group-status-${child.path}`">
                    {{ groupStatusText(child) }}
                  </el-tag>
                  <span v-if="child.description" class="hm-muted group-desc">{{ child.description }}</span>
                </div>
                <div class="group-actions" @click.stop>
                  <el-switch
                    :model-value="!ruleDisabled && groupAllEnabled(child)"
                    :disabled="ruleDisabled || !child.all.length || anyBusy"
                    :loading="busyGroup === child.path"
                    :aria-label="`开关子分组 ${child.title}`"
                    :data-test="`group-switch-${child.path}`"
                    @change="(v: string | number | boolean) => toggleGroup(child, Boolean(v))"
                  />
                </div>
              </header>
              <div v-show="isOpen(child.path)" class="subgroup-body">
                <p v-if="!child.all.length" class="hm-muted empty-group">该子分组下没有能力</p>
                <CapabilityToggleTable
                  v-else
                  :conch="conch"
                  :capabilities="child.all"
                  :rule-disabled="ruleDisabled"
                  :busy-action="busyAction"
                  :busy-group="busyGroup"
                  @toggle="toggle"
                />
              </div>
            </div>
          </template>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.grouped-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.top-level {
  margin-bottom: 4px;
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
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: var(--el-fill-color-light);
  cursor: pointer;
  user-select: none;
}

.subgroup-header {
  background: var(--el-fill-color-lighter);
  padding: 8px 12px;
}

.group-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
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

.group-actions {
  flex-shrink: 0;
}

.group-body {
  padding: 8px 12px 12px;
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
  padding: 6px 10px 10px;
}

.empty-group {
  margin: 6px 2px;
  font-size: 13px;
}
</style>
