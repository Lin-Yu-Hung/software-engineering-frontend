import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const base = (process.env.NEON_API_URL || 'http://localhost:8081').replace(/\/$/, '')
const run = randomUUID()
const checks = []
let memberToken, otherToken, holdId, orderId, otherHoldId
async function api(method, path, body, token, key) {
  const response = await fetch(`${base}/api/v1${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(key ? { 'Idempotency-Key': key } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(45000),
  })
  const text = await response.text()
  return { status: response.status, body: text ? JSON.parse(text) : null }
}
function expect(response, status, label) {
  assert.equal(response.status, status, `${label}: expected HTTP ${status}, received ${response.status} (${response.body?.code || 'no error code'})`)
  checks.push(label)
  console.log(`PASS ${label}: HTTP ${status}`)
  return response.body
}
function verify(condition, label) { assert.ok(condition, label); checks.push(label); console.log(`PASS ${label}`) }
async function actor(suffix) {
  const email = `neon-proof-${run}-${suffix}@example.test`
  const password = `NeonProof-${randomBytes(18).toString('base64url')}`
  expect(await api('POST', '/auth/register', { email, password, name: `Neon proof ${suffix}`, phone: '0912345678' }), 201, `register ${suffix}`)
  return expect(await api('POST', '/auth/login', { email, password }), 200, `login ${suffix}`).accessToken
}
try {
  const health = expect(await api('GET', '/health'), 200, 'live SQL health')
  verify(health.database === 'UP' && health.schemaVersion === '8', 'database UP with Flyway V8')
  const courts = expect(await api('GET', '/courts'), 200, 'read seeded courts')
  verify(courts.length >= 6, 'six seeded courts exist')
  const venue = expect(await api('GET', '/venue'), 200, 'venue clock')
  const tomorrow = new Date(new Date(venue.serverTime).getTime() + 32 * 3600000).toISOString().slice(0, 10)
  const grid = expect(await api('GET', `/availability?date=${tomorrow}`), 200, 'find future availability')
  const slot = grid.slots.find(item => item.status === 'AVAILABLE')
  assert.ok(slot, 'The chosen date needs one open future slot for this demo proof.')
  const items = [{ courtId: slot.courtId, startAt: slot.startAt }]
  memberToken = await actor('a')
  otherToken = await actor('b')
  const holdKey = randomUUID()
  const held = expect(await api('POST', '/holds', { items }, memberToken, holdKey), 201, 'hold creates persistent inventory claim')
  holdId = held.id
  verify(held.status === 'ACTIVE' && held.totalAmount > 0, 'hold price calculated by server')
  const replay = expect(await api('POST', '/holds', { items }, memberToken, holdKey), 201, 'duplicate click replays hold')
  verify(replay.id === holdId, 'same hold reused without duplicate booking')
  const conflict = expect(await api('POST', '/holds', { items }, otherToken, randomUUID()), 409, 'another member cannot double-book')
  verify(conflict.code === 'SLOT_UNAVAILABLE', 'database collision rejected')
  const created = expect(await api('POST', '/orders', { holdId, customer: { name: 'Neon proof', phone: '0912345678' }, paymentMethod: 'CREDIT_CARD' }, memberToken, randomUUID()), 201, 'hold becomes an order')
  orderId = created.id
  const persisted = expect(await api('GET', `/orders/${orderId}`, undefined, memberToken), 200, 'read persisted order through separate request')
  verify(persisted.status === 'PENDING_PAYMENT' && persisted.totalAmount === held.totalAmount, 'order preserves quoted price')
  expect(await api('GET', `/orders/${orderId}`, undefined, otherToken), 404, 'other member cannot read order')
  const cancelled = expect(await api('POST', `/orders/${orderId}/cancellations`, { reason: 'Neon end-to-end proof complete' }, memberToken), 200, 'cancel order')
  verify(cancelled.status === 'CANCELLED' && cancelled.refundStatus === 'NOT_REQUIRED', 'unpaid cancellation persists correct status')
  const free = expect(await api('GET', `/availability?date=${tomorrow}`), 200, 'read availability after cancellation')
  verify(free.slots.some(item => item.courtId === slot.courtId && item.startAt === slot.startAt && item.status === 'AVAILABLE'), 'cancelled slot is publicly available')
  otherHoldId = expect(await api('POST', '/holds', { items }, otherToken, randomUUID()), 201, 'released slot can be booked by another member').id
  expect(await api('DELETE', `/holds/${otherHoldId}`, undefined, otherToken), 204, 'release final test hold')
  otherHoldId = undefined
  const history = expect(await api('GET', '/orders?scope=past', undefined, memberToken), 200, 'read persisted cancellation history')
  verify(history.content.some(order => order.id === orderId && order.status === 'CANCELLED'), 'cancelled order remains in history')
  mkdirSync('tmp', { recursive: true })
  const proof = { baseUrl: base, databaseName: health.databaseName, schemaVersion: health.schemaVersion, orderId, courtId: slot.courtId, checks: checks.length, checkedAt: new Date().toISOString(), result: 'PASS' }
  writeFileSync(resolve(process.env.NEON_PROOF_FILE || 'tmp/neon-e2e.json'), JSON.stringify(proof, null, 2))
  console.log(`NEON E2E PASS: ${checks.length} checks; database=${health.databaseName}; schema=${health.schemaVersion}`)
} catch (error) {
  console.error(`NEON E2E FAIL: ${error.message}`)
  process.exitCode = 1
} finally {
  // Only release this run's own inventory; keep its cancelled order as persistence evidence.
  if (otherHoldId && otherToken) await api('DELETE', `/holds/${otherHoldId}`, undefined, otherToken).catch(() => {})
  if (orderId && memberToken) await api('POST', `/orders/${orderId}/cancellations`, { reason: 'Neon proof cleanup' }, memberToken).catch(() => {})
  else if (holdId && memberToken) await api('DELETE', `/holds/${holdId}`, undefined, memberToken).catch(() => {})
}
