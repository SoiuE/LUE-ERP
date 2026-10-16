export type LocationInfo = Record<string, any>
export function locationPath(row?: LocationInfo | null): string {
  if (!row) return '请选择库位'
  if (row.positionStatus === 'HISTORICAL_UNLOCATED' || row.locationCode === '历史未定位') return '历史未定位'
  if (row.locationPath) return row.locationPath
  const parts = ['warehouse', 'area', 'shelf', 'level'].map(key => row[`${key}Name`] || row[`${key}Code`])
  if (parts.every(Boolean) && row.slotCode) return [...parts, String(row.slotCode).endsWith('位') ? row.slotCode : `${row.slotCode}位`].join(' / ')
  return `位置信息不完整${row.locationCode ? ` · ${row.locationCode}` : ''}`
}
export function locationTypeName(type?: string): string {
  return ({ STORAGE: '正式存储位', PRIMARY_PICK: '主拣货位', PENDING_PUTAWAY: '待上架位', TEMPORARY: '暂存位', QUARANTINE: '异常 / 待检位' } as Record<string, string>)[type || ''] || ''
}
export function locationMeta(row?: LocationInfo | null): string {
  if (!row || row.positionStatus === 'HISTORICAL_UNLOCATED' || row.locationCode === '历史未定位') return ''
  return [row.locationCode, row.locationName, locationTypeName(row.locationType), row.locationStatus === 'INACTIVE' ? '已停用' : ''].filter(Boolean).join(' · ')
}
export function locationLabel(row?: LocationInfo | null): string {
  return [locationPath(row), locationMeta(row)].filter(Boolean).join(' ｜ ')
}
export function locationSearch(row: LocationInfo): string {
  return `${row.locationSearchText || ''} ${locationLabel(row)} ${row.scanCode || ''}`.toLowerCase()
}
