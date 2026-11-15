/** 服务端停用原因的开头。卡片和详情已经用标签或红条说了「已停用」，原因段只写原因。 */
export const DISABLED_LEAD = '已停用。这版规则暂时不能调用。'

export function disabledReasonOnly(message: string | null | undefined): string {
  const text = message ?? ''
  return text.startsWith(DISABLED_LEAD) ? text.slice(DISABLED_LEAD.length) : text
}
