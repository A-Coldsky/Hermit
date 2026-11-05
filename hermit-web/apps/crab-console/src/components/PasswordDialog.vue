<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { errorText } from '@shared/format'
import { ApiError } from '@shared/http'
import { api } from '../api'
import { markSignedOut } from '../session'

const visible = defineModel<boolean>({ required: true })
const router = useRouter()
const form = reactive({ current: '', next: '', repeat: '' })
const saving = ref(false)
const failure = ref('')

watch(visible, (open) => {
  if (open) {
    form.current = ''
    form.next = ''
    form.repeat = ''
    failure.value = ''
  }
})

async function save() {
  if (!form.current) {
    failure.value = '请输入当前口令'
    return
  }
  if (form.next.length < 8 || form.next.length > 256) {
    failure.value = '新口令至少 8 个字符，至多 256 个字符'
    return
  }
  if (form.next !== form.repeat) {
    failure.value = '两次输入的新口令不一致'
    return
  }
  saving.value = true
  failure.value = ''
  try {
    await api.changePassword(form.current, form.next)
    visible.value = false
    markSignedOut()
    ElMessage.success('口令已修改，请用新口令重新登录')
    await router.replace({ name: 'login' })
  } catch (error) {
    failure.value = error instanceof ApiError && error.status === 401 ? '当前口令不正确' : errorText(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="修改控制台口令" width="420px" data-test="password-dialog">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="当前口令">
        <el-input v-model="form.current" type="password" show-password autocomplete="current-password" data-test="current" />
      </el-form-item>
      <el-form-item label="新口令">
        <el-input v-model="form.next" type="password" show-password autocomplete="new-password" data-test="next" />
      </el-form-item>
      <el-form-item label="再输一次新口令">
        <el-input v-model="form.repeat" type="password" show-password autocomplete="new-password" data-test="repeat" />
      </el-form-item>
      <el-alert v-if="failure" :title="failure" type="error" :closable="false" show-icon data-test="password-failure" />
      <p class="hm-muted note">修改后，所有已登录的浏览器都要用新口令重新登录。</p>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="save-password" @click="save">修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.note {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>
