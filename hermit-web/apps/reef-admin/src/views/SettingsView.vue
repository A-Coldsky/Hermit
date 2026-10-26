<script setup lang="ts">
import { CopyDocument, Download } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import PageHeader from '@shared/components/PageHeader.vue'
import { copyText, notifyError } from '@shared/format'
import { api } from '../api'

const loading = ref(true)
const publicKey = ref('')

async function load() {
  loading.value = true
  try {
    publicKey.value = (await api.signingKey()).publicKey
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function download() {
  const url = URL.createObjectURL(new Blob([publicKey.value], { type: 'application/x-pem-file' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'reef-signing-public.pem'
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="签名公钥" description="Reef 用 Ed25519 私钥为每个发布的 Shell 签名，Crab 用这把公钥验签" />

    <div v-loading="loading" class="hm-card">
      <div class="hm-card-title">
        <span>公钥（PEM）</span>
        <div>
          <el-button :icon="CopyDocument" :disabled="!publicKey" @click="copyText(publicKey, '公钥已复制')">复制</el-button>
          <el-button :icon="Download" :disabled="!publicKey" @click="download">下载</el-button>
        </div>
      </div>
      <pre class="hm-pre">{{ publicKey }}</pre>
    </div>

    <div class="hm-card">
      <div class="hm-card-title"><span>在 Crab 上使用</span></div>
      <ol class="steps">
        <li>把公钥保存到 Crab 机器的 <code>HERMIT_HOME</code> 下，例如 <code>~/.hermit/reef.pub</code>。公钥应走带外渠道分发，Crab 不会从 Reef 自动拉取。</li>
        <li>在 <code>~/.hermit/config.yml</code> 里设置 <code>reef.url</code> 和 <code>reef.public_key: reef.pub</code>（也可以直接写 PEM 文本）。</li>
        <li>Crab 同步时会拒绝签名不匹配的 Shell，并继续使用本地已安装的版本。</li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.steps {
  margin: 0;
  padding-left: 20px;
  line-height: 2;
}
</style>
