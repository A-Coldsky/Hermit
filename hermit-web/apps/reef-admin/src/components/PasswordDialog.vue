<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api } from '../api'
import { userErrorText } from '../labels'

const open = defineModel<boolean>({ required: true })
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, message: '新密码至少 8 个字符', trigger: 'blur' },
  ],
  confirm: [
    {
      validator: (_rule, value, callback) =>
        value === form.newPassword ? callback() : callback(new Error('两次输入的新密码不一致')),
      trigger: 'blur',
    },
  ],
}

watch(open, (value) => {
  if (value) {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirm = ''
    formRef.value?.clearValidate()
  }
})

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    await api.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已修改，下次登录使用新密码')
    open.value = false
  } catch (error) {
    const text = error instanceof ApiError ? userErrorText(error.message) : null
    if (text) ElMessage.error(text)
    else notifyError(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="修改密码" width="420px" append-to-body>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <el-form-item label="再次输入新密码" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>
