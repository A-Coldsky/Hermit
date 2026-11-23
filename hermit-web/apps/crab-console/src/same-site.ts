import { ElMessage } from 'element-plus'

/** 同一网站的其它已启用规则包。只是提示：各自登录，不共用 Cookie。 */
export function sameSiteText(names: string[]): string {
  return `${names.join('、')} 和它访问同一个网站。各自登录、互不共用；网站只允许一个会话时会互相顶掉登录。`
}

/** 订阅、导入、启用、改地址之后当场提示一次。没有同网站的包时不出声。 */
export function notifySameSite(conch: string, names: string[] | undefined | null) {
  if (!names || names.length === 0) return
  ElMessage.warning({
    message: `${conch}：本机已启用的 ${sameSiteText(names)}`,
    duration: 8000,
    showClose: true,
  })
}
