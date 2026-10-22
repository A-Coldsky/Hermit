<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText } from '@shared/format'
import { ApiError } from '@shared/http'
import { api } from '../api'
import { signIn } from '../auth'

const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const failure = ref('')

async function submit() {
  if (!form.username || !form.password) {
    failure.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  failure.value = ''
  try {
    const result = await api.login(form.username, form.password)
    signIn(result.token, result.expiresAt, form.username)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    failure.value = error instanceof ApiError && error.status === 401 ? '用户名或密码错误' : errorText(error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="brand">
      <svg viewBox="0 0 64 64" width="56" height="56" aria-hidden="true">
        <path d="M10 44c0-14 10-26 24-26 9 0 17 5 20 13-6-3-12-2-16 2 5 1 9 5 9 11H10z" fill="#5eead4" />
        <circle cx="26" cy="34" r="3" fill="#0f172a" />
        <path d="M8 48h48" stroke="#5eead4" stroke-width="3" stroke-linecap="round" />
      </svg>
      <h1>Hermit Reef</h1>
      <p>把内网老系统包装成可调用的 OpenAPI 与 MCP 能力。在这里上传、发布和回滚 Shell，并查看各 Crab 回传的运行指标。</p>
      <ul class="points">
        <li>Ed25519 签名发布，Crab 验签后安装</li>
        <li>新版本异常时 Crab 自动回退</li>
        <li>按小时汇总调用量、耗时与失败样本</li>
      </ul>
    </section>
    <section class="panel">
      <el-form class="form" label-position="top" @submit.prevent="submit">
        <h2>登录管理端</h2>
        <el-form-item label="用户名">
          <el-input v-model="form.username" :prefix-icon="User" autocomplete="username" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-alert v-if="failure" :title="failure" type="error" :closable="false" show-icon class="failure" />
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  background: var(--hm-bg);
}

.brand {
  position: relative;
  overflow: hidden;
  background: radial-gradient(circle at 18% 18%, #134e4a, #0f172a 72%);
  color: #e2e8f0;
  padding: 72px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
}

/* 淡淡的点阵，只做质感，不影响阅读。 */
.brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(94, 234, 212, 0.14) 1px, transparent 1px);
  background-size: 22px 22px;
  mask-image: linear-gradient(120deg, #000 10%, transparent 70%);
  pointer-events: none;
}

.brand > * {
  position: relative;
}

.brand h1 {
  margin: 12px 0 0;
  font-size: 34px;
  letter-spacing: -0.5px;
}

.brand p {
  max-width: 440px;
  line-height: 1.7;
  color: #94a3b8;
  margin: 0;
}

.points {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: #cbd5e1;
}

.points li {
  display: flex;
  align-items: center;
  gap: 10px;
}

.points li::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #5eead4;
  box-shadow: 0 0 0 4px rgba(94, 234, 212, 0.15);
}

.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}

.form {
  width: 100%;
  max-width: 380px;
  background: var(--hm-surface);
  border: 1px solid var(--hm-border);
  border-radius: 16px;
  box-shadow: var(--hm-shadow-raised);
  padding: 32px;
}

.form h2 {
  margin: 0 0 24px;
  font-size: 22px;
}

.failure {
  margin-bottom: 16px;
}

.submit {
  width: 100%;
  height: 40px;
}

@media (max-width: 860px) {
  .login {
    grid-template-columns: 1fr;
  }

  .brand {
    padding: 40px 32px;
  }

  .points {
    display: none;
  }

  .panel {
    padding: 24px 16px 48px;
  }
}
</style>
