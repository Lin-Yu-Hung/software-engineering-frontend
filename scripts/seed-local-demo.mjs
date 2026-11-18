import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const target = new URL(process.env.DEMO_API_URL || 'http://localhost:8080')
if (target.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(target.hostname)
    || target.username || target.password || target.pathname !== '/') {
  throw new Error('範例資料只允許匯入 localhost／127.0.0.1 的本機 HTTP 後端。')
}
const base = target.origin
async function request(method, path, body, token, expected = 200) {
  const response = await fetch(`${base}/api/v1${path}`, {
    method, signal: AbortSignal.timeout(30000), redirect: 'error',
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Idempotency-Key': randomUUID() },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (response.status !== expected) throw new Error(`${method} ${path}: HTTP ${response.status} (${data?.code || 'UNKNOWN'})`)
  return data
}
const login = (email, password) => request('POST', '/auth/login', { email, password })

try {
  const health = await request('GET', '/health')
  if (health.databaseName !== 'booking' || health.database !== 'UP' || health.schemaVersion !== '7') {
    throw new Error('僅支援本機預設 booking 資料庫（Flyway V7）；不接受 Neon neondb 或其他資料庫。')
  }
  console.log(`範例資料目標：${base}／本機 booking；不會清空既有資料。`)
  const admin = (await login('admin@booking.local', 'AdminDemo123!')).accessToken
  const venue = await request('GET', '/venue')
  // Base the demonstration date on the venue's Taipei calendar, not the machine timezone.
  const first = new Date(`${venue.earliestDate}T00:00:00Z`)
  const dates = Array.from({ length: Math.min(7, venue.advanceBookingDays) }, (_, index) => {
    const day = new Date(first)
    day.setUTCDate(day.getUTCDate() + index + 1)
    return day.toISOString().slice(0, 10)
  })
  let date
  for (const candidate of dates) {
    const grid = await request('GET', `/availability?date=${candidate}`)
    if (grid.slots.filter(slot => slot.courtId === grid.courts[0]?.id && slot.status === 'AVAILABLE').length >= 7) {
      date = candidate
      break
    }
  }
  if (!date) throw new Error('未來七天內需要至少一天有七個空檔；請確認營業時間與預約範圍。')
  const email = 'member@booking.local'
  const password = 'MemberDemo123!'
  const users = await request('POST', '/auth/register', { email, password, name: '示範會員小明', phone: '0912345678' }, undefined, 201)
    .catch(async error => {
      // Reuse only an account for which the documented demo password actually works.
      if (!error.message.includes('HTTP 409')) throw error
      return (await login(email, password)).user
    })
  const member = (await login(email, password)).accessToken
  const summaryFile = resolve(root, 'tmp/local-demo-data.json')
  if (existsSync(summaryFile)) {
    const previous = JSON.parse(readFileSync(summaryFile, 'utf8'))
    if (previous.baseUrl === base && previous.memberId === users.id && previous.holdId) {
      const previousHold = await request('GET', `/holds/${previous.holdId}`, undefined, member)
      if (previousHold.status === 'ACTIVE') {
        await request('DELETE', `/holds/${previous.holdId}`, undefined, member, 204)
        console.log('已釋放上次此工具建立的保留，避免同一會員重複保留衝突。')
      }
    }
  }
  const court = await request('POST', '/backoffice/courts', {
    name: `範例場地 ${randomUUID().slice(0, 8)}`, type: '標準 PU', status: 'ACTIVE', displayOrder: 90,
  }, admin, 201)
  const grid = await request('GET', `/availability?date=${date}&courtId=${court.id}`)
  const slots = grid.slots.filter(slot => slot.status === 'AVAILABLE').slice(0, 7)
  if (slots.length < 7) throw new Error('範例場地需要七個可預約時段。')
  const items = indexes => indexes.map(index => ({ courtId: court.id, startAt: slots[index].startAt }))
  const customer = { name: '示範會員小明', phone: '0912345678' }
  async function memberOrder(indexes) {
    const hold = await request('POST', '/holds', { items: items(indexes) }, member, 201)
    return request('POST', '/orders', { holdId: hold.id, customer, paymentMethod: 'CREDIT_CARD' }, member, 201)
  }
  const paid = await memberOrder([0, 1])
  const confirmed = (await request('POST', `/demo/orders/${paid.id}/confirm-payment`, { amount: paid.totalAmount }, admin)).order
  const staff = await request('POST', '/backoffice/orders', {
    source: 'PHONE', paymentMethod: 'CASH', customer: { name: '電話預約小美', phone: '0923456789' },
    notes: '範例：電話預約、現場收款尚未完成', items: items([2]),
  }, admin, 201)
  const cancelTarget = await memberOrder([3])
  await request('POST', `/demo/orders/${cancelTarget.id}/confirm-payment`, { amount: cancelTarget.totalAmount }, admin)
  const cancelled = await request('POST', `/orders/${cancelTarget.id}/cancellations`, { reason: '範例：行程變更，退款待金流處理' }, member)
  const block = await request('POST', '/backoffice/blocks', { reason: '範例：燈具維修', items: items([5]) }, admin, 201)
  const pending = await memberOrder([4])
  const hold = await request('POST', '/holds', { items: items([6]) }, member, 201)
  const orders = [confirmed, staff, cancelled, pending]
  const result = {
    baseUrl: base, date, courtId: court.id, courtName: court.name, memberId: users.id,
    orders: orders.map(order => ({ id: order.id, orderNumber: order.orderNumber, status: order.status,
      paymentStatus: order.paymentStatus, refundStatus: order.refundStatus, totalAmount: order.totalAmount })),
    blockId: block.id, holdId: hold.id, holdExpiresAt: hold.expiresAt, pendingExpiresAt: pending.expiresAt,
  }
  mkdirSync(resolve(root, 'tmp'), { recursive: true })
  writeFileSync(resolve(root, 'tmp/local-demo-data.json'), JSON.stringify(result, null, 2))
  console.log(`已新增範例場地「${court.name}」，日期 ${date}。`)
  console.table(result.orders)
  console.log('另有一個維修時段、一個暫時保留；取消的時段已釋放。')
  console.log('保留與待付款訂單約十分鐘後到期，這是實際業務規則。')
  console.log('示範會員：member@booking.local／MemberDemo123!；可登入查詢自己的訂單。')
  console.log('IDs 與示範日期已寫入 tmp/local-demo-data.json。')
} catch (error) {
  console.error(`範例資料建立失敗：${error.message}`)
  console.error('請確認本機後端已啟動，且 DEMO_USERS_ENABLED、DEMO_PAYMENTS_ENABLED 均為 true。失敗前已建立的本機資料會保留。')
  process.exitCode = 1
}
