import { beginApiLoading, endApiLoading } from '../stores/loadingStore'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

let accessTokenGetter = () => null
let refreshHandler = null
let unauthorizedHandler = null
let inFlightRefresh = null

export class ApiRequestError extends Error {
  constructor(message, { status = 0, code = 'UNKNOWN_ERROR', details = null } = {}) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export function configureApiAuth({ getAccessToken, onRefresh, onUnauthorized }) {
  accessTokenGetter = getAccessToken || (() => null)
  refreshHandler = onRefresh || null
  unauthorizedHandler = onUnauthorized || null
}

export function createIdempotencyKey(prefix = 'bo') {
  const raw =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
  return `${prefix}-${raw}`.slice(0, 100)
}

function buildUrl(path, query) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const base = `${API_BASE}/api/v1${normalizedPath}`
  if (!query) return base
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.append(key, String(value))
    }
  }
  const qs = params.toString()
  return qs ? `${base}?${qs}` : base
}

async function parseErrorResponse(response) {
  let payload = null
  try {
    const text = await response.text()
    if (text) payload = JSON.parse(text)
  } catch {
    // 非 JSON 錯誤回應，維持預設訊息
  }

  const fallbackByStatus = {
    400: '請求欄位格式不正確，請檢查輸入內容',
    401: '登入憑證已過期或尚未登入，請重新登入',
    403: '您沒有執行此操作的權限',
    404: '找不到指定的資源或紀錄',
    409: '資料狀態或版本衝突，請重新整理後再試',
    502: '無法連上後端伺服器 (502)，請確認後端已啟動或檢查 Vite Proxy 目標設定',
    503: '後端服務或資料庫暫時無法使用，請稍後再試',
  }

  const message =
    payload?.message ||
    fallbackByStatus[response.status] ||
    `請求失敗 (HTTP ${response.status})`

  return new ApiRequestError(message, {
    status: response.status,
    code: payload?.code || `HTTP_${response.status}`,
    details: payload,
  })
}

async function performSingleRefresh() {
  if (!refreshHandler) return false
  if (!inFlightRefresh) {
    inFlightRefresh = Promise.resolve()
      .then(() => refreshHandler())
      .finally(() => {
        inFlightRefresh = null
      })
  }
  return inFlightRefresh
}

export async function apiRequest(
  path,
  {
    method = 'GET',
    query,
    body,
    headers = {},
    auth = true,
    retryOn401 = true,
    idempotencyKey,
    cookieCsrf = false,
    silent = false,
  } = {},
) {
  if (!silent) beginApiLoading()
  try {
    const finalHeaders = { ...headers }

    if (body !== undefined && !finalHeaders['Content-Type']) {
      finalHeaders['Content-Type'] = 'application/json'
    }
    if (idempotencyKey) {
      finalHeaders['Idempotency-Key'] = idempotencyKey
    }
    if (cookieCsrf) {
      finalHeaders['X-Requested-With'] = 'BookingClient'
    }
    if (auth) {
      const token = accessTokenGetter()
      if (token) {
        finalHeaders.Authorization = `Bearer ${token}`
      }
    }

    let response
    try {
      response = await fetch(buildUrl(path, query), {
        method,
        headers: finalHeaders,
        credentials: 'include',
        body: body !== undefined ? JSON.stringify(body) : undefined,
      })
    } catch {
      throw new ApiRequestError('無法連線至後端 API，請確認後端服務已啟動或檢查代理設定', {
        status: 0,
        code: 'NETWORK_ERROR',
      })
    }

    if (response.status === 401 && auth && retryOn401 && refreshHandler) {
      const refreshed = await performSingleRefresh()
      if (refreshed) {
        return await apiRequest(path, {
          method,
          query,
          body,
          headers,
          auth,
          retryOn401: false,
          idempotencyKey,
          cookieCsrf,
          silent,
        })
      }
      if (unauthorizedHandler) unauthorizedHandler()
    }

    if (!response.ok) {
      throw await parseErrorResponse(response)
    }

    if (response.status === 204) {
      return null
    }

    const contentType = response.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      return await response.json()
    }
    return await response.text()
  } finally {
    if (!silent) endApiLoading()
  }
}
