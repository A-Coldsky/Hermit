import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  files: vi.fn(),
  uploadFile: vi.fn(),
  deleteFile: vi.fn(),
  updateFileSettings: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import FilesView from '../apps/crab-console/src/views/FilesView.vue'

const MIB = 1024 * 1024

describe('文件暂存区页面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    crab.files.mockResolvedValue({
      settings: { ttlSeconds: 3600, maxFileBytes: 50 * MIB, maxTotalBytes: 1024 * MIB },
      usage: { files: 1, bytes: 2048 },
      files: [
        { fileId: 'f_abc', name: '报表.pdf', size: 2048, mime: 'application/pdf', expiresAt: '2026-11-20T10:00:00Z', shared: true },
      ],
    })
    crab.updateFileSettings.mockResolvedValue({})
  })

  it('显示默认设置、用量和文件', async () => {
    const wrapper = await mountPage(FilesView)
    await flushPromises()
    expect((wrapper.find('[data-test="file-ttl"] input').element as HTMLInputElement).value).toBe('60')
    expect(wrapper.find('[data-test="file-usage"]').text()).toContain('1 个')
    expect(wrapper.text()).toContain('报表.pdf')
    expect(wrapper.text()).toContain('f_abc')
    expect(wrapper.text()).toContain('所有 API Key')
    expect(wrapper.find('[data-test="file-save"]').attributes('disabled')).toBeDefined()
  })

  it('有效期按分钟填，保存时换成秒', async () => {
    const wrapper = await mountPage(FilesView)
    await flushPromises()
    await wrapper.find('[data-test="file-ttl"] input').setValue('30')
    await wrapper.find('[data-test="file-ttl"] input').trigger('change')
    await wrapper.find('[data-test="file-save"]').trigger('click')
    await flushPromises()
    expect(crab.updateFileSettings).toHaveBeenCalledWith({
      ttlSeconds: 1800,
      maxFileBytes: 50 * MIB,
      maxTotalBytes: 1024 * MIB,
    })
  })
})
