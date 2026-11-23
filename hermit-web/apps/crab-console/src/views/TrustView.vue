<script setup lang="ts">
import { Refresh, RefreshRight, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@shared/components/PageHeader.vue'
import TimeText from '@shared/components/TimeText.vue'
import { errorText, notifyError } from '@shared/format'
import {
  api,
  type TlsCertificate,
  type TlsFailure,
  type TlsProbeResult,
  type TlsTrustView,
} from '../api'
import CertChain from '../components/CertChain.vue'
import {
  CA_NAME,
  CA_NAME_ERROR,
  NOT_CERTIFICATE_DETAIL,
  PIN_NOTE,
  PRIVATE_KEY_DETAIL,
  TOO_LARGE_DETAIL,
  caFileError,
  certificateFileName,
  expiryState,
  hasCertificate,
  hasPrivateKey,
  isPinnableTlsKind,
  issuerCertificate,
  leafOf,
  pinRefusal,
  plainFailure,
  sourceLabel,
  suggestCaName,
  transportKindLabels,
} from '../tls'

const route = useRoute()
const loading = ref(true)
const reloading = ref(false)
const trust = ref<TlsTrustView | null>(null)
const failures = ref<TlsFailure[]>([])
const fileInput = ref<HTMLInputElement>()
const importAnchor = ref<HTMLElement>()
const nameInput = ref<{ focus: () => void } | null>(null)

const draft = reactive({ name: '', pem: '', error: '', saving: false })
const pin = reactive({
  open: false,
  loading: false,
  saving: false,
  host: '',
  port: 443,
  probe: null as TlsProbeResult | null,
  error: '',
})

const leaf = computed(() => leafOf(pin.probe?.chain ?? []))
const leafExpiry = computed(() => expiryState(leaf.value?.notAfter))
const refusal = computed(() => pinRefusal(leaf.value))
const canConfirm = computed(
  () => !!leaf.value?.sha256 && !pin.probe?.trusted && !refusal.value && !pin.loading && !pin.saving,
)

const listedErrors = computed(() => {
  const files = new Set((trust.value?.caFiles ?? []).filter((file) => file.error).map((file) => file.name))
  return (trust.value?.loadErrors ?? []).filter((error) => !error.file || !files.has(error.file))
})

function queryTarget(): { host: string; port: number } | null {
  const host = typeof route.query.host === 'string' ? route.query.host.trim() : ''
  if (!host || /[\s/\\]/.test(host)) return null
  const raw = route.query.port
  const port = raw == null || raw === '' ? 443 : Number(Array.isArray(raw) ? raw[0] : raw)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  return { host, port }
}

function focused(failure: TlsFailure): boolean {
  const target = queryTarget()
  return !!target && target.host === failure.host && target.port === failure.port
}

async function load() {
  loading.value = true
  try {
    const [view, recent] = await Promise.all([api.tlsTrust(), api.tlsFailures()])
    trust.value = {
      sources: view.sources ?? [],
      caFiles: view.caFiles ?? [],
      trustedHosts: view.trustedHosts ?? [],
      loadErrors: view.loadErrors ?? [],
    }
    failures.value = recent.failures ?? []
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function reloadTrust() {
  reloading.value = true
  try {
    await api.reloadTls()
    await load()
    ElMessage.success('已重新加载')
  } catch (error) {
    notifyError(error)
  } finally {
    reloading.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

function readFileText(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error ?? new Error('无法读取文件'))
    reader.readAsText(file)
  })
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  draft.error = ''
  if (!file) return
  const chosen = draft.name.trim() || suggestCaName(file.name) || file.name
  if (file.size > 64 * 1024) {
    draft.error = caFileError(chosen, TOO_LARGE_DETAIL)
    return
  }
  let text: string
  try {
    text = await readFileText(file)
  } catch {
    draft.error = '无法读取文件'
    return
  }
  if (hasPrivateKey(text)) {
    draft.error = caFileError(chosen, PRIVATE_KEY_DETAIL)
    return
  }
  draft.pem = text
  if (!draft.name.trim()) draft.name = suggestCaName(file.name)
}

function validateDraft(): string | null {
  const name = draft.name.trim()
  const pem = draft.pem.trim()
  if (!CA_NAME.test(name)) return CA_NAME_ERROR
  if (hasPrivateKey(pem)) return caFileError(name, PRIVATE_KEY_DETAIL)
  if (!hasCertificate(pem)) return caFileError(name, NOT_CERTIFICATE_DETAIL)
  return null
}

async function submitCa() {
  const problem = validateDraft()
  draft.error = problem ?? ''
  if (problem) return
  draft.saving = true
  try {
    await api.importTlsCa(draft.name.trim(), draft.pem.trim())
    draft.name = ''
    draft.pem = ''
    ElMessage.success('已导入')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    draft.saving = false
  }
}

async function focusImport(name: string, message: string) {
  draft.name = name
  draft.error = message
  await nextTick()
  importAnchor.value?.scrollIntoView({ block: 'center' })
  nameInput.value?.focus()
}

async function importIssuer(chain: TlsCertificate[]) {
  const cert = issuerCertificate(chain)
  if (!cert?.pem) {
    await focusImport(
      cert ? certificateFileName(cert.subject) : 'issuer.pem',
      cert ? '对端没有发来这张证书，请粘贴或选择文件' : '对端没有发来颁发者的证书，请粘贴或选择文件',
    )
    return
  }
  if (hasPrivateKey(cert.pem)) {
    draft.error = caFileError(certificateFileName(cert.subject), PRIVATE_KEY_DETAIL)
    return
  }
  try {
    await ElMessageBox.confirm(
      `导入「${cert.subject}」之后，由它签发的站点都可以直接访问。`,
      '导入这个颁发者',
      { confirmButtonText: '导入', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  try {
    await api.importTlsCa(certificateFileName(cert.subject), cert.pem)
    ElMessage.success('已导入')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function removeCa(name: string) {
  try {
    await ElMessageBox.confirm(`删除「${name}」后，里面的证书不再用于校验。已经连上的请求会照常结束。`, '删除证书', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api.deleteTlsCa(name)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function removeHost(host: string, port: number) {
  try {
    await ElMessageBox.confirm(`取消后，${host}:${port} 不再按这张证书放行。`, '取消信任', {
      confirmButtonText: '取消信任',
      cancelButtonText: '留下',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api.removeTlsHost(host, port)
    ElMessage.success('已取消信任')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

let probeSeq = 0

async function beginTrust(host: string, port: number) {
  const seq = ++probeSeq
  pin.open = true
  pin.loading = true
  pin.saving = false
  pin.host = host
  pin.port = port
  pin.probe = null
  pin.error = ''
  try {
    const result = await api.probeTls(host, port)
    if (seq !== probeSeq) return
    pin.probe = result
  } catch (error) {
    if (seq !== probeSeq) return
    pin.error = errorText(error)
  } finally {
    if (seq === probeSeq) pin.loading = false
  }
}

watch(
  () => pin.open,
  (open) => {
    if (!open) {
      probeSeq++
      pin.loading = false
    }
  },
)

async function confirmPin() {
  const cert = leaf.value
  if (!cert?.sha256 || !canConfirm.value) return
  pin.saving = true
  try {
    await api.trustTlsHost(pin.host, pin.port, cert.sha256)
    ElMessage.success(`已信任 ${pin.host}`)
    pin.open = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    pin.saving = false
  }
}

onMounted(async () => {
  await load()
  const target = queryTarget()
  if (target) await beginTrust(target.host, target.port)
})

watch(
  () => route.fullPath,
  async () => {
    const target = queryTarget()
    if (!target) return
    if (pin.open && pin.host === target.host && pin.port === target.port) return
    await beginTrust(target.host, target.port)
  },
)
</script>

<template>
  <div v-loading="loading">
    <PageHeader title="HTTPS 信任" description="公司内网的站点如果提示证书不受信任，可以在这里处理。电脑里已经有公司证书时，通常不用改任何东西。">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="primary" plain :icon="RefreshRight" :loading="reloading" data-test="reload-trust" @click="reloadTrust">
        重新加载
      </el-button>
    </PageHeader>

    <div class="hm-grid cols-4" data-test="trust-sources">
      <div
        v-for="(source, index) in trust?.sources ?? []"
        :key="`${source.kind}-${index}`"
        class="hm-card source"
        :class="{ bad: source.error }"
        :data-test="`source-${source.kind}`"
      >
        <div class="hm-muted">{{ sourceLabel(source.kind, source.label) }}</div>
        <div class="count">{{ source.count }}</div>
        <div class="hm-muted loc">{{ source.error || source.location || '已合并进信任库' }}</div>
      </div>
      <div v-if="trust && !trust.sources.length" class="hm-card hm-muted">还没有读到信任来源。</div>
    </div>

    <section class="hm-card">
      <div class="hm-card-title"><span>最近的连接问题</span></div>
      <p class="hm-muted intro">确认地址无误后再信任。技术细节在详情里。</p>
      <el-empty v-if="!failures.length" :image-size="48" description="最近没有连接失败" />
      <article
        v-for="failure in failures"
        :key="`${failure.host}:${failure.port}:${failure.at}`"
        class="failure"
        :class="{ focus: focused(failure) }"
        data-test="failure-card"
      >
        <div class="failure-head">
          <strong class="mono">{{ failure.host }}<template v-if="failure.port !== 443">:{{ failure.port }}</template></strong>
          <TimeText :value="failure.at" />
        </div>
        <p class="plain" data-test="failure-plain">{{ plainFailure(failure) }}</p>
        <el-button
          v-if="isPinnableTlsKind(failure.kind)"
          type="primary"
          data-test="trust-site"
          @click="beginTrust(failure.host, failure.port)"
        >
          信任这个站点
        </el-button>
        <details data-test="failure-detail">
          <summary>详情</summary>
          <dl class="hm-kv">
            <dt>种类</dt>
            <dd>
              {{ transportKindLabels[failure.kind] ?? failure.kind }}
              <span class="mono hm-muted">{{ failure.kind }}</span>
            </dd>
            <dt>说明</dt>
            <dd>{{ failure.message }}</dd>
          </dl>
          <CertChain :chain="failure.chain ?? []" />
          <el-button
            v-if="failure.kind === 'TLS_UNTRUSTED' || failure.kind === 'TLS_INTERMEDIATE'"
            link
            type="primary"
            data-test="import-issuer"
            @click="importIssuer(failure.chain ?? [])"
          >
            导入这个颁发者
          </el-button>
        </details>
      </article>
    </section>

    <section class="hm-card">
      <div class="hm-card-title"><span>已信任的站点</span></div>
      <el-empty v-if="!(trust?.trustedHosts.length)" :image-size="48" description="还没有按站点信任的证书" />
      <el-table v-else :data="trust?.trustedHosts ?? []" data-test="trusted-hosts">
        <el-table-column label="地址" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.host }}:{{ row.port }}</span></template>
        </el-table-column>
        <el-table-column label="到期" min-width="220">
          <template #default="{ row }">
            <TimeText :value="row.notAfter" />
            <el-tag v-if="expiryState(row.notAfter) === 'soon'" size="small" type="warning" effect="plain" class="tag">30 天内到期</el-tag>
            <el-tag v-else-if="expiryState(row.notAfter) === 'expired'" size="small" type="danger" effect="plain" class="tag">已过期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="颁发者" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.issuer || '—' }}</template>
        </el-table-column>
        <el-table-column label="登记" width="230">
          <template #default="{ row }"><TimeText :value="row.createdAt" /></template>
        </el-table-column>
        <el-table-column label="" width="110" align="right">
          <template #default="{ row }">
            <el-button link type="danger" data-test="remove-host" @click="removeHost(row.host, row.port)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section ref="importAnchor" class="hm-card">
      <div class="hm-card-title"><span>额外信任的证书</span></div>
      <p class="hm-muted intro">把公司的根证书放在这里，以后由它签发的站点都可以直接访问。可以粘贴 PEM，也可以选择文件。</p>
      <el-alert
        v-for="(error, index) in listedErrors"
        :key="`${error.file ?? ''}-${index}`"
        type="warning"
        :closable="false"
        show-icon
        :title="error.file ? `${error.file}：${error.message}` : error.message"
        class="load-error"
        data-test="load-error"
      />
      <el-empty v-if="!(trust?.caFiles.length)" :image-size="48" description="还没有额外导入的证书" />
      <article v-for="file in trust?.caFiles ?? []" :key="file.name" class="file" data-test="ca-file-row">
        <header>
          <strong class="mono">{{ file.name }}</strong>
          <el-button link type="danger" data-test="ca-delete" @click="removeCa(file.name)">删除</el-button>
        </header>
        <p v-if="file.error" class="file-error">{{ file.error }}</p>
        <CertChain :chain="file.certificates ?? []" />
      </article>

      <el-form class="import" label-position="top" @submit.prevent="submitCa">
        <el-form-item label="名字">
          <el-input ref="nameInput" v-model="draft.name" placeholder="bos-root.pem" data-test="ca-name" class="mono" />
        </el-form-item>
        <el-form-item label="证书内容">
          <el-input
            v-model="draft.pem"
            type="textarea"
            :rows="6"
            placeholder="-----BEGIN CERTIFICATE-----"
            data-test="ca-pem"
            class="mono"
          />
        </el-form-item>
        <p v-if="draft.error" class="file-error" data-test="ca-error">{{ draft.error }}</p>
        <div class="actions">
          <input ref="fileInput" type="file" accept=".pem,.crt,.cer" class="file-input" data-test="ca-file" @change="onFile" />
          <el-button :icon="Upload" @click="pickFile">选择文件</el-button>
          <el-button type="primary" :loading="draft.saving" data-test="ca-import" @click="submitCa">导入</el-button>
        </div>
      </el-form>
    </section>

    <el-dialog v-model="pin.open" title="信任这个站点" width="520px">
      <div v-loading="pin.loading" data-test="pin-dialog">
        <p v-if="pin.error" class="file-error">{{ pin.error }}</p>
        <template v-else-if="pin.probe">
          <el-result v-if="pin.probe.trusted" icon="success" title="这个地址的证书已经受信任" sub-title="不用再登记。" />
          <template v-else-if="leaf">
            <dl class="hm-kv">
              <dt>地址</dt>
              <dd class="mono">{{ pin.host }}:{{ pin.port }}</dd>
              <dt>到期</dt>
              <dd>
                <TimeText :value="leaf.notAfter" />
                <el-tag v-if="leafExpiry === 'soon'" size="small" type="warning" effect="plain" class="tag">30 天内到期</el-tag>
                <el-tag v-else-if="leafExpiry === 'expired'" size="small" type="danger" effect="plain" class="tag">已过期</el-tag>
                <el-tag v-else-if="refusal" size="small" type="warning" effect="plain" class="tag">尚未生效</el-tag>
              </dd>
              <dt>颁发者</dt>
              <dd>{{ leaf.issuer || '—' }}</dd>
            </dl>
            <p v-if="refusal" class="file-error">{{ refusal }}</p>
            <p v-else class="hm-muted note">{{ PIN_NOTE }}</p>
            <details data-test="pin-detail">
              <summary>详情</summary>
              <dl class="hm-kv">
                <dt>主题</dt>
                <dd>{{ leaf.subject || '—' }}</dd>
                <dt>指纹</dt>
                <dd class="mono">{{ leaf.sha256 }}</dd>
                <template v-if="pin.probe.message">
                  <dt>说明</dt>
                  <dd>{{ pin.probe.message }}</dd>
                </template>
              </dl>
            </details>
          </template>
          <p v-else class="hm-muted">没有拿到这张证书，暂时不能信任。</p>
        </template>
      </div>
      <template #footer>
        <el-button @click="pin.open = false">关闭</el-button>
        <el-button
          v-if="leaf && !pin.probe?.trusted && !pin.error"
          type="primary"
          data-test="pin-confirm"
          :disabled="!canConfirm"
          :loading="pin.saving"
          @click="confirmPin"
        >
          确认信任
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.source .count {
  margin: 6px 0 2px;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.6px;
  line-height: 1.15;
}

.source.bad .count,
.source.bad .loc {
  color: var(--hm-danger);
}

.loc {
  overflow-wrap: anywhere;
  font-size: 12px;
}

.intro {
  margin: -6px 0 14px;
}

.failure {
  padding: 14px 0;
  border-top: 1px solid var(--hm-border);
}

.failure.focus {
  margin: 0 -12px;
  padding: 14px 12px;
  border-top-color: transparent;
  border-radius: 10px;
  background: var(--hm-teal-soft);
}

.failure-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.plain {
  margin: 0 0 10px;
}

details {
  margin-top: 10px;
}

summary {
  cursor: pointer;
  color: var(--hm-muted);
}

.hm-kv {
  margin-top: 10px;
}

.file {
  padding: 12px 0;
  border-top: 1px solid var(--hm-border);
}

.file header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.file-error {
  margin: 8px 0 0;
  color: var(--hm-danger);
}

.load-error {
  margin-bottom: 10px;
}

.import {
  margin-top: 16px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.file-input {
  display: none;
}

.note {
  margin: 12px 0 0;
}

.tag {
  margin-left: 6px;
}
</style>
