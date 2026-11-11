/** Previous version as returned by the installed-list and detail payloads. */
export interface PreviousFields {
  previousVersion?: string | null
  /** True only when the previous zip is on disk and the current rules will not load it. */
  previousDisabled?: boolean
  /** True only when the previous package.zip is still a regular file on this machine. */
  previousPresent?: boolean
}

/**
 * A missing flag is not a yes. The button stays hidden until the server says the
 * previous zip is present and the current rules will load it.
 */
export function canRollback(row: PreviousFields): boolean {
  return Boolean(row.previousVersion) && row.previousDisabled === false && row.previousPresent === true
}

/** Why rollback is withheld. Null when there is no previous version, or when it can be restored. */
export function previousBlockedNote(row: PreviousFields): string | null {
  const version = row.previousVersion
  if (!version) return null
  if (row.previousPresent === false) {
    return `上一版 v${version} 的文件已经不在本机，无法退回。`
  }
  if (row.previousDisabled === true) {
    return `上一版 v${version}（已停用）也不符合当前规则，无法退回，请等发布方出新版`
  }
  return null
}
