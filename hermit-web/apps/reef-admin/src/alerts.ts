import { ref } from 'vue'
import { api } from './api'

export const openAlerts = ref(0)

export async function refreshOpenAlerts(): Promise<void> {
  try {
    const result = await api.alerts('open')
    openAlerts.value = result.alerts.length
  } catch {
    // 角标是附加信息，失败不打扰用户
  }
}
