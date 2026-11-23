import { ElMessage } from 'element-plus'

/** 操作结果里带回来的两类规则包：同网站的，和经同一个认证地址登录的。 */
export interface SitePeers {
  sameSite?: string[] | null
  sameAuth?: string[] | null
}

/** 同一网站的其它已启用规则包。只是提示：各自登录，不共用 Cookie。 */
export function sameSiteText(names: string[]): string {
  return `${names.join('、')} 和它访问同一个网站。各自登录、互不共用；网站只允许一个会话时会互相顶掉登录。`
}

/** 经同一个认证地址（门户、SSO）登录的其它已启用规则包。只是提示：各自在认证中心登录一次。 */
export function sameAuthText(names: string[]): string {
  return `${names.join('、')} 和它经同一个认证地址登录。各自登录、互不共用；认证中心只允许一个会话时会互相顶掉登录。`
}

/** 订阅、导入、启用、改地址之后当场提示一次。没有这样的包时不出声。 */
export function notifySameSite(conch: string, peers: SitePeers | undefined | null) {
  const parts: string[] = []
  if (peers?.sameSite?.length) parts.push(sameSiteText(peers.sameSite))
  if (peers?.sameAuth?.length) parts.push(sameAuthText(peers.sameAuth))
  if (parts.length === 0) return
  ElMessage.warning({
    message: `${conch}：本机已启用的 ${parts.join(' 另外，')}`,
    duration: 8000,
    showClose: true,
  })
}
