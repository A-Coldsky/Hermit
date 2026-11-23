export interface GroupDef {
  name: string
  title: string
  description: string | null
  children?: GroupDef[]
}

export interface GroupTreeNode<T> {
  path: string
  name: string
  title: string
  description: string | null
  /** 仅直属该分组的能力（不含下属二级子组）。 */
  direct: T[]
  /** 二级子分组列表（二级节点为空）。 */
  children: GroupTreeNode<T>[]
  /** 本组直属能力与全部二级子组能力的合集，保持文档顺序。 */
  all: T[]
}

export interface GroupedItems<T> {
  hasGroups: boolean
  /** 未写 group 的顶层直挂能力。 */
  topLevel: T[]
  groups: GroupTreeNode<T>[]
}

export interface FlatGroupOption {
  path: string
  name: string
  title: string
  fullTitle: string
  description: string | null
  depth: 1 | 2
}

/** 一级分组路径同时命中自身及其下属二级子组；二级分组只命中自身。 */
export function groupMatches(actionGroup: string | null | undefined, filterGroup: string): boolean {
  if (!actionGroup || !filterGroup) return false
  return actionGroup === filterGroup || actionGroup.startsWith(`${filterGroup}/`)
}

/** 把规则包的一/二级分组定义与平铺能力列表组装成顶层直挂 + 两层目录树。 */
export function buildGroupTree<T>(
  groups: GroupDef[] | null | undefined,
  items: T[],
  groupOf: (item: T) => string | null | undefined,
): GroupedItems<T> {
  if (!groups || !groups.length) {
    return { hasGroups: false, topLevel: items, groups: [] }
  }
  const declared = new Set<string>()
  for (const g of groups) {
    declared.add(g.name)
    for (const c of g.children ?? []) {
      declared.add(`${g.name}/${c.name}`)
    }
  }
  const topLevel = items.filter((item) => {
    const ref = groupOf(item)
    return !ref || !declared.has(ref)
  })
  const nodes: GroupTreeNode<T>[] = groups.map((g) => {
    const direct = items.filter((item) => groupOf(item) === g.name)
    const children: GroupTreeNode<T>[] = (g.children ?? []).map((c) => {
      const childPath = `${g.name}/${c.name}`
      const childItems = items.filter((item) => groupOf(item) === childPath)
      return {
        path: childPath,
        name: c.name,
        title: c.title || c.name,
        description: c.description ?? null,
        direct: childItems,
        children: [],
        all: childItems,
      }
    })
    const all = items.filter((item) => groupMatches(groupOf(item), g.name))
    return {
      path: g.name,
      name: g.name,
      title: g.title || g.name,
      description: g.description ?? null,
      direct,
      children,
      all,
    }
  })
  return { hasGroups: true, topLevel, groups: nodes }
}

/** 将一/二级分组展平为有序列表，供选择器与标签展示使用。 */
export function flattenGroups(groups: GroupDef[] | null | undefined): FlatGroupOption[] {
  if (!groups || !groups.length) return []
  const out: FlatGroupOption[] = []
  for (const g of groups) {
    const title = g.title || g.name
    out.push({
      path: g.name,
      name: g.name,
      title,
      fullTitle: title,
      description: g.description ?? null,
      depth: 1,
    })
    for (const c of g.children ?? []) {
      const childTitle = c.title || c.name
      out.push({
        path: `${g.name}/${c.name}`,
        name: c.name,
        title: childTitle,
        fullTitle: `${title} / ${childTitle}`,
        description: c.description ?? null,
        depth: 2,
      })
    }
  }
  return out
}

/** 根据分组路径返回可读标题（二级形如「审批管理 / 请假审批」）。 */
export function groupDisplayTitle(groups: GroupDef[] | null | undefined, path: string | null | undefined): string {
  if (!path) return ''
  if (!groups || !groups.length) return path
  const slash = path.indexOf('/')
  if (slash < 0) {
    const found = groups.find((g) => g.name === path)
    return found ? found.title || found.name : path
  }
  const parentName = path.slice(0, slash)
  const childName = path.slice(slash + 1)
  const parent = groups.find((g) => g.name === parentName)
  if (!parent) return path
  const child = (parent.children ?? []).find((c) => c.name === childName)
  if (!child) return path
  return `${parent.title || parent.name} / ${child.title || child.name}`
}
