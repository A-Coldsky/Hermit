import { ref } from 'vue'
import { api } from './api'

export const pendingConches = ref(0)

export async function refreshPending(): Promise<void> {
  try {
    const result = await api.conches()
    pendingConches.value = result.conches.filter((s) => s.needsConfiguration).length
  } catch {
    // 角标是附加信息，失败不打扰用户
  }
}
