const CHINA_OFFSET = '+08:00'

/** Parse API timestamps without an offset as database-local China time. */
export function parseChinaDateTime(value: unknown): Date | undefined {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? undefined : value
  if (typeof value !== 'string' || !value.trim()) return undefined
  let text = value.trim().replace(' ', 'T')
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) text += 'T00:00:00'
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text)
  const date = new Date(hasZone ? text : `${text}${CHINA_OFFSET}`)
  return Number.isNaN(date.getTime()) ? undefined : date
}

export function formatChinaDateTime(value: unknown, compact = false): string {
  const date = parseChinaDateTime(value)
  if (!date) return value == null || value === '' ? '—' : String(value)
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', ...(compact ? {} : { second: '2-digit' }),
    hourCycle: 'h23',
  }).formatToParts(date)
  const part = (type: string) => parts.find(item => item.type === type)?.value || ''
  const datePart = compact ? `${part('month')}-${part('day')}` : `${part('year')}-${part('month')}-${part('day')}`
  return `${datePart} ${part('hour')}:${part('minute')}${compact ? '' : `:${part('second')}`}`
}

export function chinaDateTimeEpoch(value: unknown): number | undefined {
  return parseChinaDateTime(value)?.getTime()
}
