import { computed, reactive } from 'vue'
import { apiRequest, configureApiAuth } from '../api/client'

export const authState = reactive({
  accessToken: null,
  user: null,
  initialized: false,
  loading: false,
})

export const isAuthenticated = computed(() => Boolean(authState.accessToken && authState.user))

export const userRoles = computed(() => authState.user?.roles || [])

export const isAdmin = computed(() => userRoles.value.includes('ADMIN'))

export const isFrontDesk = computed(
  () => userRoles.value.includes('FRONT_DESK') || userRoles.value.includes('ADMIN'),
)

export const hasBackofficeAccess = computed(() => isAuthenticated.value && isFrontDesk.value)

export function clearSession() {
  authState.accessToken = null
  authState.user = null
}

function applyTokenResponse(data) {
  authState.accessToken = data?.accessToken || null
  authState.user = data?.user || null
}

export async function login(email, password) {
  authState.loading = true
  try {
    const data = await apiRequest('/auth/login', {
      method: 'POST',
      auth: false,
      retryOn401: false,
      body: { email: email.trim(), password },
    })
    applyTokenResponse(data)
    authState.initialized = true
    return data
  } finally {
    authState.loading = false
  }
}

export async function refreshSession() {
  try {
    const data = await apiRequest('/auth/refresh', {
      method: 'POST',
      auth: false,
      retryOn401: false,
      cookieCsrf: true,
    })
    applyTokenResponse(data)
    return true
  } catch {
    clearSession()
    return false
  }
}

export async function initAuth() {
  if (authState.initialized) return isAuthenticated.value
  authState.loading = true
  try {
    await refreshSession()
    return isAuthenticated.value
  } finally {
    authState.initialized = true
    authState.loading = false
  }
}

export async function logout() {
  try {
    await apiRequest('/auth/logout', {
      method: 'POST',
      auth: false,
      retryOn401: false,
      cookieCsrf: true,
    })
  } catch {
    // 即使伺服器已失效仍清除本地工作階段
  } finally {
    clearSession()
  }
}

configureApiAuth({
  getAccessToken: () => authState.accessToken,
  onRefresh: refreshSession,
  onUnauthorized: clearSession,
})
