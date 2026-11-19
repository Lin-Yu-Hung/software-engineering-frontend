export const ORDER_STATUS_META = {
  CONFIRMED: { label: '已確認', color: 'emerald', chipColor: 'success' },
  PENDING_PAYMENT: { label: '待付款', color: 'amber', chipColor: 'warning' },
  CANCELLED: { label: '已取消', color: 'rose', chipColor: 'error' },
  EXPIRED: { label: '已逾期', color: 'slate', chipColor: 'grey' },
}

export const PAYMENT_STATUS_META = {
  PAID: { label: '已付款', color: 'emerald', chipColor: 'success' },
  UNPAID: { label: '未收款', color: 'amber', chipColor: 'warning' },
}

export const PAYMENT_METHOD_LABELS = {
  CREDIT_CARD: '線上信用卡 (ECPay)',
  CASH: '現場現金',
  ONSITE_CARD: '現場刷卡',
}

export const SOURCE_LABELS = {
  MEMBER: '會員線上預約',
  WALK_IN: '現場臨櫃代訂',
  PHONE: '電話代訂',
}

export const REFUND_STATUS_META = {
  NOT_REQUIRED: { label: '無須退款', chipColor: 'grey' },
  PENDING_REVIEW: { label: '待人工審核退款', chipColor: 'error' },
}

export const PRICE_TYPE_LABELS = {
  OFF_PEAK: '離峰',
  PEAK: '尖峰',
  WEEKEND: '週末',
  HOLIDAY: '假日',
  SPECIAL: '特殊',
}

export const DAY_TYPE_LABELS = {
  WEEKDAY: '平日',
  WEEKEND: '週末',
  HOLIDAY: '國定假日',
}

export const COURT_STATUS_META = {
  ACTIVE: { label: '開放預約', chipColor: 'success' },
  DISABLED: { label: '暫停開放', chipColor: 'warning' },
  RETIRED: { label: '已退役', chipColor: 'grey' },
}

export const WEEKDAY_NAMES = [
  { value: 1, label: '星期一' },
  { value: 2, label: '星期二' },
  { value: 3, label: '星期三' },
  { value: 4, label: '星期四' },
  { value: 5, label: '星期五' },
  { value: 6, label: '星期六' },
  { value: 7, label: '星期日' },
]

export function getPaymentMethodLabel(method) {
  return PAYMENT_METHOD_LABELS[method] || method || '—'
}

export function getSourceLabel(source) {
  return SOURCE_LABELS[source] || source || '—'
}

export function getPriceTypeLabel(priceType) {
  return PRICE_TYPE_LABELS[priceType] || priceType || '一般'
}
