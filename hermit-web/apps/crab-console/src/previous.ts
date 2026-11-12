/** Previous version as returned by the installed-list and detail payloads. */
export interface PreviousFields {
  /** The version on the current pointer. */
  version?: string | null
  previousVersion?: string | null
  /** True only when the previous zip is on disk and the current rules will not load it. */
  previousDisabled?: boolean
  /** True only when the previous package.zip is still a regular file on this machine. */
  previousPresent?: boolean
  /** The current version itself does not pass the current rules. */
  ruleDisabled?: boolean
  updateAvailable?: boolean
  latestVersion?: string | null
}

/** A "previous" equal to the current version is not a previous version at all. */
function realPrevious(row: PreviousFields): string | null {
  const version = row.previousVersion
  if (!version || version === row.version) return null
  return version
}

/**
 * A missing flag is not a yes. The button stays hidden until the server says the
 * previous zip is present and the current rules will load it. It also stays hidden when the
 * update button already points at the same version, so the page does not offer the same
 * version twice under two names.
 */
export function canRollback(row: PreviousFields): boolean {
  const previous = realPrevious(row)
  if (!previous || row.previousDisabled !== false || row.previousPresent !== true) return false
  return !(row.updateAvailable && row.latestVersion === previous)
}

/** Why rollback is withheld. Null when there is no previous version, or when it can be restored. */
export function previousBlockedNote(row: PreviousFields): string | null {
  const version = realPrevious(row)
  if (!version) return null
  if (row.previousPresent === false) {
    return `上一版 v${version} 的文件已经不在本机，无法退回。`
  }
  if (row.previousDisabled === true) {
    if (row.ruleDisabled !== true) {
      return `上一版 v${version}（已停用）不符合当前规则，不能退回到它。`
    }
    if (row.updateAvailable && row.latestVersion) {
      return `上一版 v${version}（已停用）也不符合当前规则，无法退回，请更新到 v${row.latestVersion}`
    }
    return `上一版 v${version}（已停用）也不符合当前规则，无法退回，请等发布方出新版`
  }
  return null
}
