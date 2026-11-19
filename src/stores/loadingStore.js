import { computed, reactive } from 'vue'

const MIN_VISIBLE_MS = 220

export const loadingState = reactive({
  pendingCount: 0,
  visible: false,
})

let shownAt = 0
let hideTimer = null

export const isApiLoading = computed(() => loadingState.visible)

export function beginApiLoading() {
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
  loadingState.pendingCount += 1
  if (!loadingState.visible) {
    loadingState.visible = true
    shownAt = Date.now()
  }
}

export function endApiLoading() {
  loadingState.pendingCount = Math.max(0, loadingState.pendingCount - 1)
  if (loadingState.pendingCount > 0) return

  const elapsed = Date.now() - shownAt
  const remaining = Math.max(0, MIN_VISIBLE_MS - elapsed)

  if (remaining === 0) {
    loadingState.visible = false
  } else {
    hideTimer = setTimeout(() => {
      hideTimer = null
      if (loadingState.pendingCount === 0) {
        loadingState.visible = false
      }
    }, remaining)
  }
}
