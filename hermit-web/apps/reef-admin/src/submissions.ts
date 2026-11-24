import { ref } from 'vue'
import { api } from './api'

export const pendingSubmissions = ref(0)

export async function refreshPendingSubmissions(): Promise<void> {
  try {
    const result = await api.pendingSubmissionsCount()
    pendingSubmissions.value = result.count
  } catch {
    // 角标是附加信息，失败不打扰用户
  }
}
