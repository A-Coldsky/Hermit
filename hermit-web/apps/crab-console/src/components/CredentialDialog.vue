<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { notifyError } from '@shared/format'
import { api, type CredentialField } from '../api'
import FieldInput from './FieldInput.vue'

const props = defineProps<{ conch: string; account: string; fields: CredentialField[] }>()
const emit = defineEmits<{ saved: [] }>()
const open = defineModel<boolean>('open', { required: true })

const values = reactive<Record<string, string>>({})
const cleared = reactive<Record<string, boolean>>({})
const stored = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

const changes = computed(() => {
  const body: Record<string, string> = {}
  for (const field of props.fields) {
    if (cleared[field.name]) {
      body[field.name] = ''
    } else if (values[field.name]) {
      body[field.name] = values[field.name]
    }
  }
  return body
})

watch(open, async (value) => {
  if (!value) {
    return
  }
  for (const field of props.fields) {
    values[field.name] = ''
    cleared[field.name] = false
  }
  loading.value = true
  try {
    stored.value = (await api.credentialNames(props.conch, props.account)).fields
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!Object.keys(changes.value).length) {
    open.value = false
    return
  }
  saving.value = true
  try {
    await api.writeCredentials(props.conch, props.account, changes.value)
    ElMessage.success('凭证已保存，已有会话会在下次调用时重新登录')
    open.value = false
    emit('saved')
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" :title="`填写凭证 · ${account}`" width="560px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="凭证只写不读：已保存的值不会回显。留空表示保持不变。"
      class="notice"
    />
    <el-form v-loading="loading" label-position="top" @submit.prevent="save">
      <el-form-item v-for="field in fields" :key="field.name" :required="field.required">
        <template #label>
          <span>{{ field.label || field.name }}</span>
          <span class="mono hm-muted name">{{ field.name }}</span>
          <el-tag v-if="stored.includes(field.name) && !cleared[field.name]" size="small" type="success" effect="plain">已保存</el-tag>
          <el-tag v-else-if="cleared[field.name]" size="small" type="danger" effect="plain">将清除</el-tag>
        </template>
        <div class="row">
          <FieldInput
            v-model="values[field.name]"
            :type="field.type"
            :options="field.options"
            :placeholder="stored.includes(field.name) ? '已保存，留空保持不变' : field.description ?? ''"
            :disabled="cleared[field.name]"
          />
          <el-checkbox v-if="stored.includes(field.name) && !field.required" v-model="cleared[field.name]">清除</el-checkbox>
        </div>
        <div v-if="field.description" class="hm-muted desc">{{ field.description }}</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.notice {
  margin-bottom: 16px;
}

.name {
  margin: 0 6px;
  font-size: 11px;
}

.row {
  display: flex;
  gap: 12px;
  width: 100%;
  align-items: center;
}

.row > :first-child {
  flex: 1;
}

.desc {
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
</style>
