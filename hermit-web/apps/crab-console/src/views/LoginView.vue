<script setup lang="ts">
import { Lock } from '@element-plus/icons-vue'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText } from '@shared/format'
import { ApiError } from '@shared/http'
import HermitMark from '@shared/components/HermitMark.vue'
import { api } from '../api'
import { markSignedIn, refreshSession, safeRedirect } from '../session'

const route = useRoute()
const router = useRouter()
const password = ref('')
const loading = ref(false)
const failure = ref('')

async function submit() {
  if (!password.value) {
    failure.value = '请输入控制台口令'
    return
  }
  loading.value = true
  failure.value = ''
  try {
    await api.login(password.value)
    markSignedIn()
    await router.replace(safeRedirect(route.query.redirect))
  } catch (error) {
    if (error instanceof ApiError && error.code === 'NOT_CONFIGURED') {
      await refreshSession().catch(() => undefined)
      await router.replace(safeRedirect(route.query.redirect))
      return
    }
    failure.value =
      error instanceof ApiError && error.status === 401
        ? '口令错误。连续输错 5 次会锁定 15 分钟，锁定期间正确的口令也进不去。'
        : errorText(error)
  } finally {
    loading.value = false
    password.value = ''
  }
}
</script>

<template>
  <div class="login">
    <el-form class="form" label-position="top" size="large" @submit.prevent="submit">
      <HermitMark variant="crab" :size="52" intro class="mark" />
      <h2>Crab 本地控制台</h2>
      <p class="lead">这台 Crab 设置了控制台口令，输入后才能继续</p>
      <el-form-item label="控制台口令">
        <el-input
          v-model="password"
          :prefix-icon="Lock"
          type="password"
          show-password
          autocomplete="current-password"
          autofocus
          data-test="password"
        />
      </el-form-item>
      <el-alert v-if="failure" :title="failure" type="error" :closable="false" show-icon class="failure" data-test="failure" />
      <el-button type="primary" native-type="submit" :loading="loading" class="submit" data-test="submit">进入控制台</el-button>
      <p class="hint hm-muted">
        忘了口令：先停掉 Crab，在本机运行 <code>crab console password</code> 重新设置，再启动。
      </p>
    </el-form>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  background:
    radial-gradient(circle at 100% 0%, rgba(15, 118, 110, 0.06), transparent 40%),
    var(--hm-bg);
}

.form {
  width: 100%;
  max-width: 380px;
  background: var(--hm-surface);
  border: 1px solid var(--hm-border);
  border-radius: 18px;
  padding: 36px 32px 28px;
  box-shadow: var(--hm-shadow-lg);
}

.mark {
  margin-bottom: 16px;
}

.form h2 {
  margin: 0;
  font-size: 22px;
}

.lead {
  margin: 6px 0 24px;
  color: var(--hm-muted);
  font-size: 13px;
}

.failure {
  margin-bottom: 16px;
}

.submit {
  width: 100%;
  height: 40px;
  margin-top: 4px;
}

.hint {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
