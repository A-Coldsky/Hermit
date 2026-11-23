<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { notifyError } from '@shared/format'
import { api, type ConfigValue } from '../api'
import FieldInput from './FieldInput.vue'
import { notifySameSite } from '../same-site'

const props = defineProps<{ conch: string; fields: Record<string, ConfigValue>; emptyText?: string }>()
const emit = defineEmits<{ changed: [] }>()

const values = reactive<Record<string, string>>({})
const saving = ref(false)

const entries = computed(() => Object.entries(props.fields))
const changes = computed(() => {
  const body: Record<string, string> = {}
  for (const [name, field] of entries.value) {
    if ((values[name] ?? '') !== (field.value ?? '')) {
      body[name] = values[name] ?? ''
    }
  }
  return body
})

watch(
  () => props.fields,
  (fields) => {
    for (const [name, field] of Object.entries(fields)) {
      values[name] = field.value ?? ''
    }
  },
  { immediate: true },
)

function defaultText(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return ''
  }
  return typeof value === 'string' ? value : JSON.stringify(value)
}

async function save() {
  saving.value = true
  try {
    const saved = await api.writeConfig(props.conch, changes.value)
    ElMessage.success('配置已保存')
    // 站点跟 base_url 走；认证地址可以引用任何一个 type: url 的配置项。
    const urlChanged = Object.keys(changes.value).some((name) => props.fields[name]?.type === 'url')
    notifySameSite(props.conch, {
      sameSite: 'base_url' in changes.value ? saved.sameSite : null,
      sameAuth: urlChanged ? saved.sameAuth : null,
    })
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="hm-card">
    <div class="hm-card-title">
      <span>配置</span>
      <el-button type="primary" :disabled="!Object.keys(changes).length" :loading="saving" @click="save">保存配置</el-button>
    </div>
    <p class="hm-muted hint">配置属于整个规则包，所有账号共用。留空表示使用默认值。</p>
    <el-empty v-if="!entries.length" :image-size="48" :description="emptyText || '这个规则包没有配置项'" />
    <el-form v-else label-position="top" class="form" @submit.prevent="save">
      <el-form-item v-for="[name, field] in entries" :key="name" :required="field.required" :error="field.missing ? '必填，尚未填写' : ''">
        <template #label>
          <span>{{ field.label || name }}</span>
          <span class="mono hm-muted name">{{ name }}</span>
        </template>
        <FieldInput
          v-model="values[name]"
          :type="field.type"
          :options="field.options"
          :placeholder="defaultText(field.defaultValue) ? `默认：${defaultText(field.defaultValue)}` : field.description ?? ''"
        />
        <div v-if="field.description" class="hm-muted desc">{{ field.description }}</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 0 20px;
}

.name {
  margin-left: 6px;
  font-size: 11px;
}

.desc {
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
</style>
