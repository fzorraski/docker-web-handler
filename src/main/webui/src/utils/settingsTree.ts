import type { RuntimeSetting } from '../services/settingsService'

/**
 * Fixed display order for the Settings tab. Unknown categories from a newer
 * backend are appended after these, and rows without a category land in OTHER.
 */
export const SETTING_CATEGORIES = ['terminal', 'logAnalyzer', 'session', 'audit'] as const
export const OTHER_CATEGORY = 'other'

export interface SettingRow {
  setting: RuntimeSetting
  /** 0 for a root, +1 per ancestor. */
  depth: number
  /** Why the row currently has no effect, or null when active. */
  inactive: InactiveReason | null
}

export interface SettingGroup {
  category: string
  /** Pre-order: each parent is immediately followed by its subtree. */
  rows: SettingRow[]
}

export type InactiveReason =
  | { kind: 'setting'; parentKey: string }
  | { kind: 'property'; property: string }

/**
 * Turns the flat backend list into ordered groups of pre-ordered rows. A row is
 * a root when it has no parent or its parent is not in the list; children keep
 * backend order under their parent and inherit its group. Cycles cannot hang
 * the UI: a row is emitted at most once.
 */
export function buildSettingGroups(settings: RuntimeSetting[]): SettingGroup[] {
  const children = new Map<string, RuntimeSetting[]>()
  const keys = new Set(settings.map((s) => s.key))
  const roots: RuntimeSetting[] = []
  for (const s of settings) {
    const parent = s.dependsOn ?? null
    if (parent && keys.has(parent) && parent !== s.key) {
      const list = children.get(parent) ?? []
      list.push(s)
      children.set(parent, list)
    } else {
      roots.push(s)
    }
  }

  const byKey = new Map(settings.map((s) => [s.key, s]))
  const rowsByCategory = new Map<string, SettingRow[]>()
  const visited = new Set<string>()
  const walk = (setting: RuntimeSetting, depth: number, out: SettingRow[]) => {
    if (visited.has(setting.key)) return
    visited.add(setting.key)
    out.push({ setting, depth, inactive: inactiveReason(setting, byKey) })
    for (const child of children.get(setting.key) ?? []) walk(child, depth + 1, out)
  }
  for (const root of roots) {
    const category = root.category || OTHER_CATEGORY
    const out = rowsByCategory.get(category) ?? []
    walk(root, 0, out)
    rowsByCategory.set(category, out)
  }
  // Rows only reachable through a cycle (no root above them) still get shown.
  for (const s of settings) {
    if (!visited.has(s.key)) {
      const category = s.category || OTHER_CATEGORY
      const out = rowsByCategory.get(category) ?? []
      walk(s, 0, out)
      rowsByCategory.set(category, out)
    }
  }

  const known: string[] = [...SETTING_CATEGORIES]
  const unknown = [...rowsByCategory.keys()].filter((c) => !known.includes(c) && c !== OTHER_CATEGORY)
  const ordered = [...known, ...unknown, OTHER_CATEGORY]
  return ordered
    .filter((c) => (rowsByCategory.get(c)?.length ?? 0) > 0)
    .map((c) => ({ category: c, rows: rowsByCategory.get(c)! }))
}

/**
 * Why a setting currently has no effect, or null when it is active. Walks the
 * setting and its ancestors: a restart-only property gate wins, otherwise the
 * nearest ancestor that is switched off is named.
 */
export function inactiveReason(setting: RuntimeSetting, byKey: ReadonlyMap<string, RuntimeSetting>): InactiveReason | null {
  const seen = new Set<string>()
  let node: RuntimeSetting | undefined = setting
  while (node && !seen.has(node.key)) {
    seen.add(node.key)
    if (node.disabledByProperty) return { kind: 'property', property: node.disabledByProperty }
    if (node !== setting && node.value !== true) return { kind: 'setting', parentKey: node.key }
    node = node.dependsOn ? byKey.get(node.dependsOn) : undefined
  }
  return null
}
