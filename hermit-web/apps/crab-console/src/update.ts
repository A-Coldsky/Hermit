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

/**
 * 修复同步标为 REPAIR_REQUIRED 的包。本机文件仍能验签就重新载入，不下载；验签失败才安装 Reef 当前版本。
 * 返回是否已恢复可调用。
 */
export async function repairConch(conch: string, reason: string | null): Promise<boolean> {
  const why = reason ? `原因：${syncReasonLabels[reason] ?? reason}。` : ''
  try {
    await ElMessageBox.confirm(
      `${why}先重新载入本机验过签的版本；本机文件验签不通过时，改为下载 Reef 当前发布的版本，可能比原来的版本高。`,
      `修复 ${conch}`,
      { confirmButtonText: '修复', cancelButtonText: '暂不修复', type: 'warning' },
    )
  } catch {
    return false
  }
  try {
    const result = await api.update(conch)
    const kind = result.outcome?.kind
    const version = result.outcome?.version ? ` v${result.outcome.version}` : ''
    if (kind === 'REPAIRED') {
      ElMessage.success(`${conch} 已从本机重新载入${version}`)
      return true
    }
    if (!kind || kind === 'INSTALLED') {
      ElMessage.success(`${conch} 本机文件不可用，已重新安装 Reef 版本${version}`)
      return true
    }
    const label = outcomeLabels[kind]?.text ?? kind
    const detail = result.outcome?.reason ? `：${syncReasonLabels[result.outcome.reason] ?? result.outcome.reason}` : ''
    ElMessage.warning(`${conch} 没有修复（${label}${detail}）`)
    return false
  } catch (error) {
    const missing = missingCredentials(error)
    if (missing) {
      ElMessage.warning({
        message: `Reef 版本已下载，但默认账号还缺凭证：${missing.join('、')}。在账号里补齐后再点「修复」。`,
        duration: 8000,
      })
    } else {
      notifyError(error)
    }
    return false
  }
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
      `把 ${target.conch} 从 ${from} 更新到 ${to}。更新后可以退回刚换下的那一版。${credentials}`,
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
