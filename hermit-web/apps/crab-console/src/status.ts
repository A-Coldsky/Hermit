import { ref } from 'vue'
import { api } from './api'

export const pendingShells = ref(0)

export async function refreshPending(): Promise<void> {
  try {
    const result = await api.shells()
    pendingShells.value = result.shells.filter((s) => s.needsConfiguration).length
  } catch {
    // 角标是附加信息，失败不打扰用户
  }
}
