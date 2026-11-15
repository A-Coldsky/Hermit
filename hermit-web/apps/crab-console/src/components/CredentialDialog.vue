<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'
import { notifyError } from '@shared/format'
import { api, type CredentialField } from '../api'
import FieldInput from './FieldInput.vue'

const props = defineProps<{ conch: string; account: string; fields: CredentialField[]; unreadable?: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const open = defineModel<boolean>('open', { required: true })

const values = reactive<Record<string, string>>({})
const cleared = reactive<Record<string, boolean>>({})
const stored = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

function shownField(field: CredentialField) {
  if (!field.label || field.label === field.name) return field.name
  return `${field.label}（${field.name}）`
}

const keepable = (name: string) => !props.unreadable && stored.value.includes(name)

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
  if (props.unreadable) {
    const empty = props.fields.filter((f) => f.required && !values[f.name]).map((f) => shownField(f))
    if (empty.length) {
      ElMessage.closeAll()
      ElMessage.warning(`凭证无法解密，需要全部重新填写：${empty.join('、')}`)
      return
    }
  }
  const blocked = props.fields.filter((field) => {
    if (!field.required || values[field.name]) return false
    return !(keepable(field.name) && !cleared[field.name])
  })
  if (blocked.length) {
    ElMessage.closeAll()
    ElMessage.warning(`请填写必填凭证：${blocked.map((field) => shownField(field)).join('、')}`)
    return
  }
  if (!Object.keys(changes.value).length) {
    open.value = false
    return
  }
  saving.value = true
  try {
    await api.writeCredentials(props.conch, props.account, changes.value)
    // 连续保存两次时，新提示替换上一条，屏上只留一条。
    ElMessage.closeAll()
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
      v-if="unreadable"
      type="error"
      :closable="false"
      show-icon
      title="已保存的凭证无法用当前主密钥解密，需要全部重新填写，不能留空。"
      class="notice"
    />
    <el-alert
      v-else
      type="info"
      :closable="false"
      show-icon
      title="凭证只写不读：已保存的值不会回显。留空表示保持不变。"
      class="notice"
    />
    <el-form v-loading="loading" label-position="top" @submit.prevent="save">
      <el-form-item v-for="field in fields" :key="field.name" :required="field.required">
        <template #label>
          <span>{{ shownField(field) }}</span>
          <el-tag v-if="unreadable" size="small" type="danger" effect="plain">需重填</el-tag>
          <el-tag v-else-if="stored.includes(field.name) && !cleared[field.name]" size="small" type="success" effect="plain">已保存</el-tag>
          <el-tag v-else-if="cleared[field.name]" size="small" type="danger" effect="plain">将清除</el-tag>
        </template>
        <div class="row">
          <FieldInput
            v-model="values[field.name]"
            :type="field.type"
            :options="field.options"
            :placeholder="keepable(field.name) ? '已保存，留空保持不变' : field.description ?? ''"
            :disabled="cleared[field.name]"
          />
          <el-checkbox v-if="keepable(field.name) && !field.required" v-model="cleared[field.name]">清除</el-checkbox>
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
