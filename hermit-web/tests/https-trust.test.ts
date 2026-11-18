import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@shared/http'
import { formatTime } from '@shared/format'
import { mountPage } from './helpers'
import {
  CA_NAME,
  CA_NAME_ERROR,
  certificateFileName,
  pinRefusal,
  endpointFromMessage,
  errorWithoutTransport,
  expiryState,
  firstTlsTransport,
  hasPrivateKey,
  isTlsFailure,
  plainFailure,
  suggestCaName,
  trustLocation,
} from '../apps/crab-console/src/tls'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  tlsTrust: vi.fn(),
  tlsFailures: vi.fn(),
  importTlsCa: vi.fn(),
  deleteTlsCa: vi.fn(),
  reloadTls: vi.fn(),
  probeTls: vi.fn(),
  trustTlsHost: vi.fn(),
  removeTlsHost: vi.fn(),
  testLogin: vi.fn(),
  runLocal: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn(), pendingConches: { value: 0 } }))

import App from '../apps/crab-console/src/App.vue'
import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import DeveloperPanel from '../apps/crab-console/src/components/DeveloperPanel.vue'
import DevTracePanel from '../apps/crab-console/src/components/DevTracePanel.vue'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'
import TrustView from '../apps/crab-console/src/views/TrustView.vue'

const PEM = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n'
const SOON = new Date(Date.now() + 10 * 24 * 60 * 60 * 1000).toISOString()
const LEAF = {
  subject: 'CN=newmail.dev.bos',
  issuer: 'CN=www.bosc.cn, O=BOS',
  sha256: 'ab12',
  notBefore: '2024-01-01T00:00:00Z',
  notAfter: '2026-12-01T00:00:00Z',
  ca: false,
}
const ISSUER = {
  subject: 'CN=www.bosc.cn, O=BOS',
  issuer: 'CN=www.bosc.cn, O=BOS',
  sha256: 'cd34',
  notBefore: '2020-01-01T00:00:00Z',
  notAfter: '2030-01-01T00:00:00Z',
  ca: true,
  pem: PEM,
}

function trustView() {
  return {
    sources: [
      { kind: 'JDK', label: 'JDK', count: 140, location: null, error: null },
      { kind: 'OS', label: '操作系统', count: 0, location: '/etc/ssl/certs/ca-certificates.crt', error: '读不到操作系统信任库' },
      { kind: 'CA_DIR', label: 'CA 目录', count: 2, location: '/var/hermit/trust/ca', error: null },
    ],
    caFiles: [
      {
        name: 'intranet.pem',
        error: null,
        certificates: [
          {
            subject: 'CN=leaf.example',
            issuer: 'CN=Intranet',
            sha256: 'ee56',
            notBefore: '2024-01-01T00:00:00Z',
            notAfter: SOON,
            ca: false,
          },
        ],
      },
    ],
    trustedHosts: [
      {
        host: 'mail.intra.example',
        port: 443,
        sha256: 'ff78',
        subject: 'CN=mail.intra.example',
        issuer: 'CN=Intranet',
        notAfter: SOON,
        createdAt: '2026-11-01T00:00:00Z',
        createdBy: 'console',
      },
    ],
    loadErrors: [{ file: 'bad.pem', message: '含有私钥，已跳过整个文件' }],
  }
}

function failures() {
  return {
    failures: [
      {
        host: 'newmail.dev.bos',
        port: 443,
        kind: 'TLS_UNTRUSTED',
        message: '上游证书不受信任：颁发者 CN=www.bosc.cn, O=BOS。可在控制台「HTTPS 信任」导入这个颁发者的根证书，或信任这张证书',
        at: '2026-11-18T01:00:00Z',
        chain: [LEAF, ISSUER],
      },
      {
        host: 'old.example',
        port: 443,
        kind: 'TLS_EXPIRED',
        message: '上游证书已过期（2020-01-01）',
        at: '2026-11-18T02:00:00Z',
        chain: [
          {
            subject: 'CN=old.example',
            issuer: 'CN=old.example',
            sha256: 'expired',
            notBefore: '2019-01-01T00:00:00Z',
            notAfter: '2020-01-01T00:00:00Z',
            ca: false,
          },
        ],
      },
      {
        host: 'missing.example',
        port: 443,
        kind: 'DNS',
        message: '无法解析上游地址 missing.example',
        at: '2026-11-18T03:00:00Z',
        chain: [],
      },
    ],
  }
}

function probe(host: string, port: number) {
  if (host === 'old.example') {
    return {
      host,
      port,
      trusted: false,
      kind: 'TLS_EXPIRED',
      message: '上游证书已过期（2020-01-01）',
      chain: failures().failures[1].chain,
    }
  }
  return {
    host,
    port,
    trusted: false,
    kind: 'TLS_UNTRUSTED',
    message: '上游证书不受信任：颁发者 CN=www.bosc.cn, O=BOS',
    chain: [LEAF, { ...ISSUER, pem: undefined }],
  }
}

function account() {
  return {
    name: 'alice',
    default: true,
    needsConfiguration: false,
    missingFields: [],
    credentialsUnreadable: false,
    credentialMessage: null,
    loginPaused: false,
    loginPauseReason: null,
    loginPauseRecovery: null,
    loginInputRequired: false,
    loginInputExpiresAt: null,
    oauth: null,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
})

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conches.mockResolvedValue({ conches: [] })
  crab.calls.mockResolvedValue({ calls: [] })
  crab.tlsTrust.mockResolvedValue(trustView())
  crab.tlsFailures.mockResolvedValue(failures())
  crab.importTlsCa.mockResolvedValue(trustView())
  crab.deleteTlsCa.mockResolvedValue({ deleted: true })
  crab.reloadTls.mockResolvedValue(trustView())
  crab.probeTls.mockImplementation(async (host: string, port: number) => probe(host, port))
  crab.trustTlsHost.mockResolvedValue({ host: 'newmail.dev.bos', port: 443, sha256: 'ab12' })
  crab.removeTlsHost.mockResolvedValue({ deleted: true })
})

describe('TLS 文案', () => {
  it('不受信任用白话，技术词不进这句话', () => {
    const text = plainFailure({ kind: 'TLS_UNTRUSTED', host: 'newmail.dev.bos' })
    expect(text).toBe('这个站点的证书不被本机信任，常见于公司内网。确认地址是 newmail.dev.bos 无误后，可以信任它。')
    expect(text).not.toContain('PEM')
    expect(text).not.toContain('指纹')
    expect(text).not.toContain('CA')
  })

  it('从主机名句子里取出地址，颁发者 DN 不算地址', () => {
    expect(endpointFromMessage('上游证书与主机名 newmail.dev.bos 不符')).toEqual({ host: 'newmail.dev.bos', port: 443 })
    expect(endpointFromMessage('上游拒绝连接（newmail.dev.bos:8443）')).toEqual({ host: 'newmail.dev.bos', port: 8443 })
    expect(endpointFromMessage('上游证书不受信任：颁发者 CN=www.bosc.cn, O=BOS')).toBeNull()
    expect(isTlsFailure({ message: '上游返回 502' })).toBe(false)
    expect(isTlsFailure({ kind: 'TLS_HOSTNAME', message: '上游连接失败' })).toBe(true)
    expect(trustLocation({ kind: 'TLS_UNTRUSTED', host: 'newmail.dev.bos', port: 443 })).toEqual({
      path: '/trust',
      query: { host: 'newmail.dev.bos', port: '443' },
    })
    expect(trustLocation({ message: '上游状态码 404' })).toBeNull()
  })

  it('30 天内到期、私钥和证书文件名', () => {
    expect(expiryState(SOON)).toBe('soon')
    expect(expiryState(new Date(Date.now() + 31 * 24 * 60 * 60 * 1000).toISOString())).toBe('ok')
    expect(expiryState('2020-01-01T00:00:00Z')).toBe('expired')
    expect(hasPrivateKey('-----BEGIN RSA PRIVATE KEY-----\nabc')).toBe(true)
    expect(hasPrivateKey(PEM)).toBe(false)
    expect(CA_NAME.test('a/b.pem')).toBe(false)
    expect(CA_NAME.test('root')).toBe(false)
    expect(CA_NAME.test('root.pem')).toBe(true)
    expect(CA_NAME.test('root.CRT')).toBe(true)
    expect(CA_NAME_ERROR).toContain('.pem、.crt 或 .cer')
    expect(pinRefusal({ notBefore: '2020-01-01T00:00:00Z', notAfter: '2020-06-01T00:00:00Z' })).toBe(
      '这张证书已经过期，不能登记。',
    )
    expect(pinRefusal({ notBefore: '2027-01-01T00:00:00Z', notAfter: '2028-01-01T00:00:00Z' })).toBe(
      '这张证书尚未生效，不能登记。',
    )
    expect(suggestCaName('bos-root.pem')).toBe('bos-root.pem')
    expect(suggestCaName('my cert.pem')).toBe('mycert.pem')
    expect(certificateFileName('CN=www.bosc.cn, O=BOS')).toBe('www.bosc.cn.pem')
  })
})

describe('HTTPS 信任页', () => {
  it('显示三种来源的数量、即将到期、非 CA 和加载错误', async () => {
    const wrapper = await mountPage(TrustView)
    await flushPromises()

    expect(wrapper.get('[data-test="source-JDK"]').text()).toContain('140')
    expect(wrapper.get('[data-test="source-OS"]').text()).toContain('操作系统')
    expect(wrapper.get('[data-test="source-OS"]').text()).toContain('读不到操作系统信任库')
    expect(wrapper.get('[data-test="source-CA_DIR"]').text()).toContain('2')
    expect(wrapper.get('[data-test="load-error"]').text()).toContain('bad.pem')
    expect(wrapper.get('[data-test="load-error"]').text()).toContain('含有私钥')
    expect(wrapper.text()).toContain('30 天内到期')
    expect(wrapper.text()).toContain('非 CA')
    expect(wrapper.get('[data-test="trusted-hosts"]').text()).toContain('mail.intra.example:443')

    const plain = wrapper.get('[data-test="failure-plain"]')
    expect(plain.text()).toContain('这个站点的证书不被本机信任')
    expect(plain.text()).not.toContain('ab12')
    const detail = wrapper.get('[data-test="failure-detail"]').element as HTMLDetailsElement
    expect(detail.open).toBe(false)
    expect(detail.textContent).toContain('ab12')
    expect(detail.textContent).toContain('CN=www.bosc.cn, O=BOS')
    expect(wrapper.findAll('[data-test="trust-site"]')).toHaveLength(2)
    wrapper.unmount()
  })

  it('信任这个站点先检测，确认后登记指纹', async () => {
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()
    await wrapper.get('[data-test="trust-site"]').trigger('click')
    await flushPromises()

    expect(crab.probeTls).toHaveBeenCalledWith('newmail.dev.bos', 443)
    const dialog = document.body.querySelector('[data-test="pin-dialog"]')
    const folded = dialog?.querySelector('[data-test="pin-detail"]') as HTMLDetailsElement
    expect(dialog?.textContent).toContain('newmail.dev.bos:443')
    expect(dialog?.textContent).toContain('CN=www.bosc.cn, O=BOS')
    expect(dialog?.textContent).toContain(formatTime('2026-12-01T00:00:00Z'))
    expect(dialog?.textContent).toContain('需要重新确认')
    expect(folded.open).toBe(false)
    expect(folded.textContent).toContain('ab12')
    expect(dialog?.textContent).not.toMatch(/PEM/)

    await document.body.querySelector<HTMLButtonElement>('[data-test="pin-confirm"]')!.click()
    await flushPromises()
    expect(crab.trustTlsHost).toHaveBeenCalledWith('newmail.dev.bos', 443, 'ab12')
    wrapper.unmount()
  })

  it('过期证书不能确认', async () => {
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()
    await wrapper.findAll('[data-test="trust-site"]')[1].trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('这张证书已经过期，不能登记。')
    const button = document.body.querySelector('[data-test="pin-confirm"]')
    expect(button?.hasAttribute('disabled') || button?.classList.contains('is-disabled')).toBe(true)
    expect(crab.trustTlsHost).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('尚未生效的证书不能确认，不允许的地址显示服务端句子', async () => {
    crab.probeTls.mockResolvedValueOnce({
      host: 'newmail.dev.bos',
      port: 443,
      trusted: false,
      kind: 'TLS_OTHER',
      message: '上游证书尚未生效（2027-01-01）',
      chain: [{ ...LEAF, notBefore: '2027-01-01T00:00:00Z', notAfter: '2028-01-01T00:00:00Z' }],
    })
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()
    await wrapper.get('[data-test="trust-site"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('这张证书尚未生效，不能登记。')
    const blocked = document.body.querySelector('[data-test="pin-confirm"]')
    expect(blocked?.hasAttribute('disabled') || blocked?.classList.contains('is-disabled')).toBe(true)
    wrapper.unmount()

    crab.probeTls.mockRejectedValueOnce(
      new ApiError(400, 'INVALID_INPUT', '这个地址不在已安装规则、MCP 上游或 Reef 地址里。'),
    )
    const again = await mountPage(TrustView, { attach: true })
    await flushPromises()
    await again.get('[data-test="trust-site"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('这个地址不在已安装规则、MCP 上游或 Reef 地址里。')
    again.unmount()
  })

  it('带 host 和 port 打开页面时直接检测', async () => {
    const wrapper = await mountPage(TrustView, { attach: true, path: '/trust?host=newmail.dev.bos&port=8443' })
    await flushPromises()
    expect(crab.probeTls).toHaveBeenCalledWith('newmail.dev.bos', 8443)
    expect(document.body.querySelector('[data-test="pin-dialog"]')?.textContent).toContain('newmail.dev.bos:8443')
    wrapper.unmount()
  })

  it('粘贴或上传 PEM 后导入，拒绝私钥和非法名字', async () => {
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()

    await wrapper.get('[data-test="ca-name"]').setValue('a/b.pem')
    await wrapper.get('[data-test="ca-pem"]').setValue(PEM)
    await wrapper.get('[data-test="ca-import"]').trigger('click')
    expect(crab.importTlsCa).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="ca-error"]').text()).toContain('并且以 .pem、.crt 或 .cer 结尾')

    await wrapper.get('[data-test="ca-name"]').setValue('root')
    await wrapper.get('[data-test="ca-import"]').trigger('click')
    expect(crab.importTlsCa).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="ca-error"]').text()).toContain('.cer')

    await wrapper.get('[data-test="ca-name"]').setValue('bad.pem')
    await wrapper.get('[data-test="ca-pem"]').setValue('-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----')
    await wrapper.get('[data-test="ca-import"]').trigger('click')
    expect(crab.importTlsCa).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="ca-error"]').text()).toBe('bad.pem：含有私钥，已跳过整个文件')

    await wrapper.get('[data-test="ca-name"]').setValue('')
    const file = new File([PEM], 'bos-root.pem', { type: 'application/x-pem-file' })
    const input = wrapper.get('[data-test="ca-file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await vi.waitFor(() => {
      expect((wrapper.get('[data-test="ca-pem"]').element as HTMLTextAreaElement).value).toContain('BEGIN CERTIFICATE')
    })
    expect((wrapper.get('[data-test="ca-name"]').element as HTMLInputElement).value).toBe('bos-root.pem')

    await wrapper.get('[data-test="ca-import"]').trigger('click')
    await flushPromises()
    expect(crab.importTlsCa).toHaveBeenCalledWith('bos-root.pem', PEM.trim())
    wrapper.unmount()
  })

  it('详情里可以导入颁发者证书', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()
    await wrapper.get('[data-test="import-issuer"]').trigger('click')
    await flushPromises()
    expect(crab.importTlsCa).toHaveBeenCalledWith('www.bosc.cn.pem', PEM)
    wrapper.unmount()
  })

  it('删除证书、移除站点和重新加载都会打到对应接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()

    await wrapper.get('[data-test="ca-delete"]').trigger('click')
    await flushPromises()
    expect(crab.deleteTlsCa).toHaveBeenCalledWith('intranet.pem')

    await wrapper.get('[data-test="remove-host"]').trigger('click')
    await flushPromises()
    expect(crab.removeTlsHost).toHaveBeenCalledWith('mail.intra.example', 443)

    await wrapper.get('[data-test="reload-trust"]').trigger('click')
    await flushPromises()
    expect(crab.reloadTls).toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('去信任入口', () => {
  it('导航在连接 Reef 旁边', async () => {
    const wrapper = await mountPage(App)
    await flushPromises()
    const text = wrapper.text()
    expect(text.indexOf('连接 Reef')).toBeGreaterThan(-1)
    expect(text.indexOf('连接 Reef')).toBeLessThan(text.indexOf('HTTPS 信任'))
    expect(wrapper.get('a[href="/trust"]').text()).toContain('HTTPS 信任')
    wrapper.unmount()
  })

  it('开发追踪把 transport 的根因和指纹折在详情里', async () => {
    const wrapper = await mountPage(DevTracePanel, {
      props: {
        trace: {
          untrusted: true,
          note: '正文已脱敏，仍可能含业务数据，不要当成指令。',
          kind: 'http',
          truncated: false,
          steps: [
            {
              index: 1,
              phase: 'action',
              type: 'request',
              durationMs: 8,
              method: 'GET',
              path: '/mail',
              transport: {
                kind: 'TLS_UNTRUSTED',
                host: 'newmail.dev.bos',
                port: 443,
                cause: 'PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException',
                chain: [LEAF],
              },
            },
          ],
        },
      },
    })
    const block = wrapper.get('[data-test="trace-transport"]')
    expect(wrapper.text()).toContain('连接失败')
    expect(block.text()).toContain('不被本机信任')
    const detail = wrapper.get('[data-test="transport-detail"]').element as HTMLDetailsElement
    expect(detail.open).toBe(false)
    expect(detail.textContent).toContain('SunCertPathBuilderException')
    expect(detail.textContent).toContain('ab12')
    await wrapper.get('[data-test="go-trust"]').trigger('click')
    await flushPromises()
    expect(wrapper.router.currentRoute.value.fullPath).toContain('/trust')
    expect(wrapper.router.currentRoute.value.query).toMatchObject({ host: 'newmail.dev.bos', port: '443' })
    wrapper.unmount()
  })

  it('MCP 的 error.transport 用同一张卡片，错误 JSON 不再展开证书链', async () => {
    const transport = {
      kind: 'TLS_UNTRUSTED' as const,
      host: 'newmail.dev.bos',
      port: 443,
      cause: 'PKIX path building failed',
      chain: [LEAF],
    }
    const trace = {
      untrusted: true,
      note: '正文已脱敏。',
      kind: 'jsonrpc' as const,
      truncated: false,
      transport,
      exchanges: [
        {
          method: 'initialize',
          httpStatus: 0,
          error: { kind: 'CONNECT', transport },
        },
      ],
    }
    expect(firstTlsTransport(trace)?.host).toBe('newmail.dev.bos')
    expect(errorWithoutTransport(trace.exchanges[0].error)).toEqual({ kind: 'CONNECT' })

    const wrapper = await mountPage(DevTracePanel, { props: { trace } })
    const blocks = wrapper.findAll('[data-test="trace-transport"]')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].text()).toContain('不被本机信任')
    const detail = wrapper.get('[data-test="transport-detail"]').element as HTMLDetailsElement
    expect(detail.open).toBe(false)
    expect(detail.textContent).toContain('ab12')
    expect(detail.textContent).toContain('PKIX path building failed')
    const error = wrapper.get('.hm-kv').text()
    expect(error).toContain('CONNECT')
    expect(error).not.toContain('ab12')
    wrapper.unmount()

    const nestedOnly = {
      untrusted: true,
      note: '正文已脱敏。',
      kind: 'jsonrpc' as const,
      truncated: false,
      exchanges: [{ method: 'initialize', error: { kind: 'CONNECT', transport } }],
    }
    expect(firstTlsTransport(nestedOnly)?.host).toBe('newmail.dev.bos')
    const only = await mountPage(DevTracePanel, { props: { trace: nestedOnly } })
    expect(only.findAll('[data-test="trace-transport"]')).toHaveLength(1)
    expect(only.get('[data-test="trace-transport"]').text()).toContain('不被本机信任')
    only.unmount()
  })

  it('调用记录只在 TLS 失败时给出去信任，详情优先用 transport', async () => {
    crab.calls.mockResolvedValue({
      calls: [
        {
          traceId: 't-http',
          at: '2026-11-18T01:00:00Z',
          origin: 'API',
          conch: 'bos_mail',
          action: 'list',
          account: 'alice',
          conchVersion: '1.0.0',
          success: false,
          errorCode: 'UPSTREAM_ERROR',
          errorMessage: '上游证书与主机名 files.intra.example 不符',
          durationMs: 20,
          apiKeyId: null,
          source: 'REEF',
          channel: 'HTTP',
        },
        {
          traceId: 't-plain',
          at: '2026-11-18T01:01:00Z',
          origin: 'API',
          conch: 'bos_mail',
          action: 'list',
          account: 'alice',
          conchVersion: '1.0.0',
          success: false,
          errorCode: 'UPSTREAM_ERROR',
          errorMessage: '上游返回 502',
          durationMs: 20,
          apiKeyId: null,
          source: 'REEF',
          channel: 'HTTP',
        },
      ],
    })
    const list = await mountPage(CallsView)
    await flushPromises()
    const jumps = list.findAll('[data-test="go-trust"]')
    expect(jumps).toHaveLength(1)
    await jumps[0].trigger('click')
    await flushPromises()
    expect(list.router.currentRoute.value.query.host).toBe('files.intra.example')
    list.unmount()

    crab.call.mockResolvedValue({
      traceId: 't-tls',
      at: '2026-11-18T01:02:00Z',
      origin: 'API',
      conch: 'bos_mail',
      action: 'list',
      account: 'alice',
      conchVersion: '1.0.0',
      success: false,
      errorCode: 'UPSTREAM_ERROR',
      errorMessage: '上游连接失败：SSLHandshakeException',
      durationMs: 12,
      apiKeyId: null,
      source: 'REEF',
      channel: 'HTTP',
      steps: [],
      devTrace: {
        untrusted: true,
        note: '正文已脱敏。',
        kind: 'http',
        truncated: false,
        steps: [
          {
            index: 1,
            phase: 'action',
            type: 'request',
            durationMs: 12,
            transport: {
              kind: 'TLS_UNTRUSTED',
              host: 'newmail.dev.bos',
              port: 443,
              cause: 'unable to find valid certification path',
              chain: [LEAF],
            },
          },
        ],
      },
    })
    const detail = await mountPage(CallsView, { attach: true, path: '/calls?trace=t-tls' })
    await flushPromises()
    const drawer = document.body.querySelector('.el-drawer')
    const jump = drawer?.querySelector<HTMLButtonElement>('[data-test="go-trust"]')
    expect(jump).not.toBeNull()
    jump!.click()
    await flushPromises()
    expect(detail.router.currentRoute.value.query).toMatchObject({ host: 'newmail.dev.bos', port: '443' })
    detail.unmount()
  })

  it('测试登录和试跑的 TLS 失败可以去信任，其它上游错误不行', async () => {
    crab.testLogin.mockRejectedValue(
      new ApiError(502, 'UPSTREAM_ERROR', '上游证书不受信任：颁发者 CN=www.bosc.cn, O=BOS'),
    )
    const accounts = await mountPage(AccountsPanel, {
      attach: true,
      props: { conch: 'bos_mail', accounts: [account()], credentials: [], shown: true },
    })
    await flushPromises()
    await accounts.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('去信任')
    accounts.unmount()
    document.body.innerHTML = ''

    crab.testLogin.mockRejectedValue(new ApiError(502, 'UPSTREAM_ERROR', '上游返回 502'))
    const other = await mountPage(AccountsPanel, {
      attach: true,
      props: { conch: 'bos_mail', accounts: [account()], credentials: [], shown: true },
    })
    await flushPromises()
    await other.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).not.toContain('去信任')
    other.unmount()
    document.body.innerHTML = ''

    crab.runLocal.mockRejectedValue(
      new ApiError(502, 'UPSTREAM_ERROR', '与上游的 TLS 握手失败：handshake_failure'),
    )
    const trial = await mountPage(DeveloperPanel, {
      attach: true,
      props: {
        conch: 'local_echo',
        driver: 'http',
        debugEnabled: true,
        capabilities: [{ action: 'echo', title: '回声', description: '', method: 'POST', readOnly: true, disabled: false }],
        accounts: [account()],
      },
    })
    await trial.get('[data-test="trial-run"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('去信任')
    const jump = document.body.querySelector<HTMLButtonElement>('[data-test="go-trust"]')
    jump!.click()
    await flushPromises()
    expect(trial.router.currentRoute.value.path).toBe('/trust')
    trial.unmount()
  })
})
