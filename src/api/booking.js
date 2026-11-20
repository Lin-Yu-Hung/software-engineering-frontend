import { apiRequest, createIdempotencyKey } from './client'

export function getCourts() {
  return apiRequest('/courts', { auth: false })
}

export function getAvailability(date) {
  return apiRequest('/availability', { auth: false, query: { date } })
}

export function createHold(items, idempotencyKey = createIdempotencyKey('hold')) {
  return apiRequest('/holds', {
    method: 'POST',
    idempotencyKey,
    body: { items },
  })
}

export function releaseHold(holdId) {
  return apiRequest(`/holds/${holdId}`, { method: 'DELETE' })
}

export function createOrder(payload, idempotencyKey = createIdempotencyKey('order')) {
  return apiRequest('/orders', {
    method: 'POST',
    idempotencyKey,
    body: payload,
  })
}

export function getMyOrders({ scope, page = 0, size = 20 } = {}) {
  return apiRequest('/orders', {
    query: { scope: scope || undefined, page, size },
  })
}

export function cancelMyOrder(orderId, reason = '') {
  return apiRequest(`/orders/${orderId}/cancellations`, {
    method: 'POST',
    body: { reason },
  })
}

export function initiateEcpayPayment(orderId) {
  return apiRequest(`/orders/${orderId}/payments/ecpay`, {
    method: 'POST',
  })
}

export function getOrder(orderId) {
  return apiRequest(`/orders/${orderId}`)
}
