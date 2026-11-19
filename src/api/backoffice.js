import { apiRequest, createIdempotencyKey } from './client'

// 場館與排程看板
export function getVenue() {
  return apiRequest('/venue', { auth: false })
}

export function getTimetable(date) {
  return apiRequest('/backoffice/timetable', {
    query: { date },
  })
}

// 後台訂單作業 (FRONT_DESK / ADMIN)
export function listBackofficeOrders({ date, page = 0, size = 20 } = {}) {
  return apiRequest('/backoffice/orders', {
    query: { date: date || undefined, page, size },
  })
}

export function getBackofficeOrder(orderId) {
  return apiRequest(`/backoffice/orders/${orderId}`)
}

export function createStaffOrder(payload, idempotencyKey = createIdempotencyKey('staff-order')) {
  return apiRequest('/backoffice/orders', {
    method: 'POST',
    idempotencyKey,
    body: payload,
  })
}

export function editBackofficeOrder(orderId, payload) {
  return apiRequest(`/backoffice/orders/${orderId}`, {
    method: 'PATCH',
    body: payload,
  })
}

export function cancelBackofficeOrder(orderId, reason) {
  return apiRequest(`/backoffice/orders/${orderId}/cancellations`, {
    method: 'POST',
    body: { reason },
  })
}

export function checkInBackofficeOrder(orderId, expectedVersion) {
  return apiRequest(`/backoffice/orders/${orderId}/check-in`, {
    method: 'POST',
    body: { expectedVersion },
  })
}

export function reverseCheckInBackofficeOrder(orderId, expectedVersion) {
  return apiRequest(`/backoffice/orders/${orderId}/check-in-reversals`, {
    method: 'POST',
    body: { expectedVersion },
  })
}

// 維修封場 (ADMIN)
export function listBlocks(date) {
  return apiRequest('/backoffice/blocks', {
    query: { date },
  })
}

export function createBlock(payload, idempotencyKey = createIdempotencyKey('block')) {
  return apiRequest('/backoffice/blocks', {
    method: 'POST',
    idempotencyKey,
    body: payload,
  })
}

export function releaseBlock(blockId) {
  return apiRequest(`/backoffice/blocks/${blockId}`, {
    method: 'DELETE',
  })
}

// 場地主檔管理 (ADMIN)
export function listAdminCourts() {
  return apiRequest('/backoffice/courts')
}

export function createAdminCourt(payload) {
  return apiRequest('/backoffice/courts', {
    method: 'POST',
    body: payload,
  })
}

export function updateAdminCourt(courtId, payload) {
  return apiRequest(`/backoffice/courts/${courtId}`, {
    method: 'PATCH',
    body: payload,
  })
}

export function retireAdminCourt(courtId, expectedVersion) {
  return apiRequest(`/backoffice/courts/${courtId}`, {
    method: 'DELETE',
    query: { expectedVersion },
  })
}

// 費率規則管理 (ADMIN)
export function getPricingRules() {
  return apiRequest('/backoffice/pricing-rules')
}

export function replacePricingRules(payload) {
  return apiRequest('/backoffice/pricing-rules', {
    method: 'PUT',
    body: payload,
  })
}

// 營業時間與例外日管理 (ADMIN)
export function getOperatingHours() {
  return apiRequest('/backoffice/operating-hours')
}

export function replaceOperatingHours(payload) {
  return apiRequest('/backoffice/operating-hours', {
    method: 'PUT',
    body: payload,
  })
}

export function listCalendarExceptions(from, to) {
  return apiRequest('/backoffice/calendar-exceptions', {
    query: { from, to },
  })
}

export function createCalendarException(payload) {
  return apiRequest('/backoffice/calendar-exceptions', {
    method: 'POST',
    body: payload,
  })
}

export function updateCalendarException(exceptionId, payload) {
  return apiRequest(`/backoffice/calendar-exceptions/${exceptionId}`, {
    method: 'PATCH',
    body: payload,
  })
}

export function deleteCalendarException(exceptionId, expectedVersion) {
  return apiRequest(`/backoffice/calendar-exceptions/${exceptionId}`, {
    method: 'DELETE',
    query: { expectedVersion },
  })
}

// 本機示範付款 (ADMIN，僅本機啟用；Azure 預設停用)
export function confirmDemoPayment(orderId, amount) {
  return apiRequest(`/demo/orders/${orderId}/confirm-payment`, {
    method: 'POST',
    body: { amount },
  })
}
