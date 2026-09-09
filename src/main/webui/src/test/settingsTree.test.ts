import { describe, it, expect } from 'vitest'
import type { RuntimeSetting } from '../services/settingsService'
import { buildSettingGroups, inactiveReason } from '../utils/settingsTree'

function s(key: string, category: string | undefined, dependsOn: string | null, value: boolean | number | string = true,
  extra: Partial<RuntimeSetting> = {}): RuntimeSetting {
  return { key, type: typeof value === 'boolean' ? 'boolean' : typeof value === 'number' ? 'integer' : 'string', value, defaultValue: value, overridden: false, category, dependsOn, disabledByProperty: null, ...extra }
}

/** Mirrors the backend payload and its parent-before-child order. */
function fixture(overrides: Record<string, boolean | number | string> = {}): RuntimeSetting[] {
  const v = (k: string, d: boolean | number | string) => (k in overrides ? overrides[k] : d)
  return [
    s('terminalEnabled', 'terminal', null, v('terminalEnabled', true)),
    s('terminalMaxSessions', 'terminal', 'terminalEnabled', 5),
    s('terminalIdleTimeoutMinutes', 'terminal', 'terminalEnabled', 30),
    s('terminalUploadEnabled', 'terminal', 'terminalEnabled', v('terminalUploadEnabled', true)),
    s('terminalUploadMaxSizeMb', 'terminal', 'terminalUploadEnabled', 100),
    s('terminalImageUploadEnabled', 'terminal', 'terminalEnabled', v('terminalImageUploadEnabled', true)),
    s('terminalImageUploadPath', 'terminal', 'terminalImageUploadEnabled', '/tmp'),
    s('logAnalyzerEnabled', 'logAnalyzer', null, true),
    s('sessionTimeoutMinutes', 'session', null, 480),
    s('auditRetentionDays', 'audit', null, 0),
  ]
}

const byKeyOf = (list: RuntimeSetting[]) => new Map(list.map((x) => [x.key, x]))

describe('buildSettingGroups', () => {
  it('groups in the fixed category order and skips empty groups', () => {
    const groups = buildSettingGroups(fixture())
    expect(groups.map((g) => g.category)).toEqual(['terminal', 'logAnalyzer', 'session', 'audit'])
    expect(groups[1].rows.map((r) => r.setting.key)).toEqual(['logAnalyzerEnabled'])
  })

  it('carries each row\'s inactive reason so the renderer does not re-walk the tree', () => {
    const rows = buildSettingGroups(fixture({ terminalUploadEnabled: false }))[0].rows
    const byKey = new Map(rows.map((r) => [r.setting.key, r.inactive]))
    expect(byKey.get('terminalUploadMaxSizeMb')).toEqual({ kind: 'setting', parentKey: 'terminalUploadEnabled' })
    expect(byKey.get('terminalImageUploadPath')).toBeNull()
    expect(byKey.get('terminalEnabled')).toBeNull()
  })

  it('nests children in pre-order with depths, grandchildren right after their parent', () => {
    const terminal = buildSettingGroups(fixture())[0]
    expect(terminal.rows.map((r) => [r.setting.key, r.depth])).toEqual([
      ['terminalEnabled', 0],
      ['terminalMaxSessions', 1],
      ['terminalIdleTimeoutMinutes', 1],
      ['terminalUploadEnabled', 1],
      ['terminalUploadMaxSizeMb', 2],
      ['terminalImageUploadEnabled', 1],
      ['terminalImageUploadPath', 2],
    ])
  })

  it('treats a child whose parent is missing as a root, and files rows without a category under other', () => {
    const groups = buildSettingGroups([
      s('orphan', 'terminal', 'missingParent', 1),
      s('noCategory', undefined, null, 'x'),
    ])
    expect(groups.map((g) => g.category)).toEqual(['terminal', 'other'])
    expect(groups[0].rows[0]).toMatchObject({ depth: 0 })
    expect(groups[1].rows[0].setting.key).toBe('noCategory')
  })

  it('appends unknown categories after the known ones and before other', () => {
    const groups = buildSettingGroups([
      s('z', undefined, null, 1),
      s('future', 'containers', null, true),
      s('audit1', 'audit', null, 1),
    ])
    expect(groups.map((g) => g.category)).toEqual(['audit', 'containers', 'other'])
  })

  it('terminates on cyclic dependsOn and still shows every row once', () => {
    const groups = buildSettingGroups([
      s('a', 'session', 'b', true),
      s('b', 'session', 'a', true),
    ])
    const keys = groups.flatMap((g) => g.rows.map((r) => r.setting.key))
    expect(keys.sort()).toEqual(['a', 'b'])
  })
})

describe('inactiveReason', () => {
  it('is null when every ancestor is on', () => {
    const list = fixture()
    const byKey = byKeyOf(list)
    for (const setting of list) expect(inactiveReason(setting, byKey)).toBeNull()
  })

  it('names the terminal flag for each direct child when it is off', () => {
    const list = fixture({ terminalEnabled: false })
    const byKey = byKeyOf(list)
    for (const key of ['terminalMaxSessions', 'terminalIdleTimeoutMinutes', 'terminalUploadEnabled', 'terminalImageUploadEnabled']) {
      expect(inactiveReason(byKey.get(key)!, byKey)).toEqual({ kind: 'setting', parentKey: 'terminalEnabled' })
    }
    expect(inactiveReason(byKey.get('terminalEnabled')!, byKey)).toBeNull()
  })

  it('names the nearest off ancestor for a grandchild', () => {
    const onlyTerminalOff = byKeyOf(fixture({ terminalEnabled: false }))
    expect(inactiveReason(onlyTerminalOff.get('terminalUploadMaxSizeMb')!, onlyTerminalOff))
      .toEqual({ kind: 'setting', parentKey: 'terminalEnabled' })

    const bothOff = byKeyOf(fixture({ terminalEnabled: false, terminalUploadEnabled: false }))
    expect(inactiveReason(bothOff.get('terminalUploadMaxSizeMb')!, bothOff))
      .toEqual({ kind: 'setting', parentKey: 'terminalUploadEnabled' })
  })

  it('keeps the image and file upload branches independent', () => {
    const byKey = byKeyOf(fixture({ terminalImageUploadEnabled: false }))
    expect(inactiveReason(byKey.get('terminalUploadMaxSizeMb')!, byKey)).toBeNull()
    expect(inactiveReason(byKey.get('terminalImageUploadPath')!, byKey)).toEqual({ kind: 'setting', parentKey: 'terminalImageUploadEnabled' })
  })

  it('reports a restart-only property gate', () => {
    const retention = s('auditRetentionDays', 'audit', null, 0, { disabledByProperty: 'audit.enabled' })
    expect(inactiveReason(retention, byKeyOf([retention]))).toEqual({ kind: 'property', property: 'audit.enabled' })
  })
})
