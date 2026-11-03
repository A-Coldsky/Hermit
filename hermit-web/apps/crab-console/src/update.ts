import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@shared/http'
import { notifyError } from '@shared/format'
import { api } from './api'
import { outcomeLabels, syncReasonLabels } from './labels'

const MISSING_PREFIX = 'missing credentials for update:'

export function missingCredentials(error: unknown): string[] | null {
  if (!(error instanceof ApiError) || !error.message.startsWith(MISSING_PREFIX)) return null
  return error.message
    .slice(MISSING_PREFIX.length)
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
}

export interface UpdateTarget {
  conch: string
  installedVersion: string | null
  latestVersion: string | null
  newRequiredCredentials: string[]
}

/** 确认后安装最近一次同步看到的更高版本。返回是否已切换。 */
export async function updateConch(target: UpdateTarget): Promise<boolean> {
  const from = target.installedVersion ? `v${target.installedVersion}` : '当前版本'
  const to = target.latestVersion ? `v${target.latestVersion}` : '最新版本'
  const credentials = target.newRequiredCredentials.length
    ? `新版本需要这些凭证：${target.newRequiredCredentials.join('、')}。默认账号缺少时会先下载、不切换，补齐后再点一次「更新」。`
    : ''
  try {
    await ElMessageBox.confirm(
      `把 ${target.conch} 从 ${from} 更新到 ${to}。更新后不能退回旧版本。${credentials}`,
      `更新 ${target.conch}`,
      { confirmButtonText: '更新', cancelButtonText: '暂不更新', type: 'info' },
    )
  } catch {
    return false
  }
  try {
    const result = await api.update(target.conch)
    const kind = result.outcome?.kind
    if (!kind || kind === 'INSTALLED') {
      ElMessage.success(`${target.conch} 已更新到 ${result.outcome?.version ? `v${result.outcome.version}` : to}`)
      return true
    }
    const label = outcomeLabels[kind]?.text ?? kind
    const reason = result.outcome?.reason ? `：${syncReasonLabels[result.outcome.reason] ?? result.outcome.reason}` : ''
    ElMessage.warning(`${target.conch} 没有更新（${label}${reason}）`)
    return false
  } catch (error) {
    const missing = missingCredentials(error)
    if (missing) {
      ElMessage.warning({
        message: `新版本已下载，但默认账号还缺凭证：${missing.join('、')}。在账号里补齐后再点「更新」。`,
        duration: 8000,
      })
    } else {
      notifyError(error)
    }
    return false
  }
}
