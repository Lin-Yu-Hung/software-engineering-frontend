const TAIPEI_TZ = 'Asia/Taipei'

const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: TAIPEI_TZ,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

const hourMinuteFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: TAIPEI_TZ,
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const dateTimeFormatter = new Intl.DateTimeFormat('zh-TW', {
  timeZone: TAIPEI_TZ,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function getTodayTaipei(isoDateTime) {
  const date = isoDateTime ? new Date(isoDateTime) : new Date()
  if (Number.isNaN(date.getTime())) {
    return dateFormatter.format(new Date())
  }
  return dateFormatter.format(date)
}

export function shiftDateStr(dateStr, deltaDays) {
  const [year, month, day] = dateStr.split('-').map(Number)
  const d = new Date(Date.UTC(year, month - 1, day + deltaDays))
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

export function formatTaipeiHour(isoDateTime) {
  if (!isoDateTime) return ''
  const date = new Date(isoDateTime)
  if (Number.isNaN(date.getTime())) return ''
  const text = hourMinuteFormatter.format(date)
  return text === '24:00' ? '00:00' : text
}

export function formatTaipeiDate(isoDateTime) {
  if (!isoDateTime) return ''
  const date = new Date(isoDateTime)
  if (Number.isNaN(date.getTime())) return ''
  return dateFormatter.format(date)
}

export function formatTaipeiDateTime(isoDateTime) {
  if (!isoDateTime) return '—'
  const date = new Date(isoDateTime)
  if (Number.isNaN(date.getTime())) return '—'
  return dateTimeFormatter.format(date)
}

export function buildTaipeiIso(dateStr, hourLabel) {
  const hh = String(hourLabel).split(':')[0].padStart(2, '0')
  return `${dateStr}T${hh}:00:00+08:00`
}

export function slotEpochKey(courtId, isoDateTime) {
  const ms = new Date(isoDateTime).getTime()
  return `${courtId}_${ms}`
}

export function getWeekdayLabel(dateStr, dayType) {
  if (!dateStr) return { label: '', isWeekendOrHoliday: false }
  const [year, month, day] = dateStr.split('-').map(Number)
  const d = new Date(Date.UTC(year, month - 1, day))
  const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  const weekday = days[d.getUTCDay()]

  let typeLabel = d.getUTCDay() === 0 || d.getUTCDay() === 6 ? '週末' : '平日'
  let isWeekendOrHoliday = d.getUTCDay() === 0 || d.getUTCDay() === 6

  if (dayType === 'HOLIDAY') {
    typeLabel = '國定假日'
    isWeekendOrHoliday = true
  } else if (dayType === 'WEEKEND') {
    typeLabel = '週末'
    isWeekendOrHoliday = true
  } else if (dayType === 'WEEKDAY') {
    typeLabel = '平日'
    isWeekendOrHoliday = false
  }

  return {
    label: `${weekday} (${typeLabel})`,
    isWeekendOrHoliday,
  }
}
