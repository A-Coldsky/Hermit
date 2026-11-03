<script setup lang="ts">
import { Connection, DataLine, Lock, Stamp, User } from '@element-plus/icons-vue'
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
        <path d="M10 44c0-14 10-26 24-26 9 0 17 5 20 13-6-3-12-2-16 2 5 1 9 5 9 11H10z" fill="currentColor" />
        <circle cx="26" cy="34" r="3" fill="#0f172a" />
        <path d="M8 48h48" stroke="currentColor" stroke-width="3" stroke-linecap="round" />
      </svg>
      <h1>Hermit Reef</h1>
      <p>把内网老系统包装成可调用的 OpenAPI 与 MCP 能力。在这里上传、发布和停用规则包，并查看各 Crab 回传的运行指标。</p>
      <ul class="points">
        <li><el-icon><Stamp /></el-icon><span><b>签名发布</b>每个规则包版本由 Reef 签名，Crab 验签后才安装</span></li>
        <li><el-icon><Connection /></el-icon><span><b>分发到 Crab</b>各地 Crab 定时同步，新版本异常时自动切回旧版</span></li>
        <li><el-icon><DataLine /></el-icon><span><b>Tide 指标</b>按小时汇总调用量、耗时和失败样本，不含请求内容</span></li>
      </ul>
    </section>
    <section class="panel">
      <el-form class="form" label-position="top" size="large" @submit.prevent="submit">
        <h2>登录管理端</h2>
        <p class="lead">请先登录。使用部署时配置的管理员账号</p>
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
}

.brand {
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 15% 15%, rgba(45, 212, 191, 0.28), transparent 45%),
    radial-gradient(circle at 85% 90%, rgba(245, 158, 11, 0.14), transparent 40%),
    linear-gradient(160deg, var(--hm-login-from), var(--hm-login-to) 70%);
  color: #e2e8f0;
  padding: 72px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
}

.brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(255, 255, 255, 0.07) 1px, transparent 1px);
  background-size: 22px 22px;
  mask-image: linear-gradient(180deg, transparent, #000 30%, #000 70%, transparent);
  pointer-events: none;
}

.brand > * {
  position: relative;
}

.points {
  list-style: none;
  margin: 28px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 460px;
}

.points li {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  backdrop-filter: blur(4px);
  font-size: 13px;
  line-height: 1.6;
  color: #9fb3bf;
}

.brand svg {
  color: var(--hm-brand-soft);
}

.points .el-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 18px;
  color: var(--hm-brand-soft);
}

.points b {
  display: block;
  color: #e6f4f1;
  font-weight: 600;
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
}

.panel {
  background:
    radial-gradient(circle at 100% 0%, rgba(15, 118, 110, 0.06), transparent 40%),
    var(--hm-bg);
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
  border-radius: 18px;
  padding: 36px 32px 32px;
  box-shadow: var(--hm-shadow-lg);
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
