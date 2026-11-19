<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  cancelBackofficeOrder,
  checkInBackofficeOrder,
  confirmDemoPayment,
  createBlock,
  createStaffOrder,
  editBackofficeOrder,
  getBackofficeOrder,
  getTimetable,
  getVenue,
  listAdminCourts,
  releaseBlock,
  reverseCheckInBackofficeOrder,
} from '../../api/backoffice'
import {
  authState,
  hasBackofficeAccess,
  initAuth,
  isAdmin,
  logout,
} from '../../stores/authStore'
import {
  ORDER_STATUS_META,
  PAYMENT_STATUS_META,
  REFUND_STATUS_META,
  getPaymentMethodLabel,
  getPriceTypeLabel,
  getSourceLabel,
} from '../../utils/backofficeLabels'
import {
  formatTaipeiDate,
  formatTaipeiDateTime,
  formatTaipeiHour,
  getTodayTaipei,
  getWeekdayLabel,
  shiftDateStr,
  slotEpochKey,
} from '../../utils/taipeiTime'
import AdminLoginCard from './components/AdminLoginCard.vue'
import AdminOrdersPanel from './components/AdminOrdersPanel.vue'
import AdminSettingsDialog from './components/AdminSettingsDialog.vue'

// ==============================
// 1. 全域狀態、日期與分頁
// ==============================
const activeView = ref('timetable') // 'timetable' | 'orders'
const todayDate = ref(getTodayTaipei())
const selectedDate = ref(todayDate.value)
const venueInfo = ref(null)
const timetableLoading = ref(false)
const timetable = ref(null)
const ordersRefreshKey = ref(0)

// 場地名稱對照表（包含歷史停用/退役場地）
const allCourtMap = ref({})

// 提示訊息 Toast
const snackbar = ref({
  show: false,
  text: '',
  color: 'success',
})

let toastTimer = null
const showToast = (text, color = 'success') => {
  snackbar.value = { show: true, text, color }
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    snackbar.value.show = false
  }, 3600)
}

const handleChildToast = ({ text, color }) => {
  showToast(text, color)
}

// ==============================
// 2. 日期快捷工具
// ==============================
const weekdayInfo = computed(() =>
  getWeekdayLabel(selectedDate.value, timetable.value?.grid?.dayType),
)

const nextSaturdayDate = computed(() => {
  const [y, m, d] = todayDate.value.split('-').map(Number)
  const dt = new Date(Date.UTC(y, m - 1, d))
  const day = dt.getUTCDay()
  const delta = (6 - day + 7) % 7 || 7
  return shiftDateStr(todayDate.value, delta)
})

const tomorrowDate = computed(() => shiftDateStr(todayDate.value, 1))

const shiftDate = (deltaDays) => {
  selectedDate.value = shiftDateStr(selectedDate.value, deltaDays)
}

const setQuickDate = (targetDateStr) => {
  selectedDate.value = targetDateStr
}

// ==============================
// 3. 資料載入 (Venue + Timetable + Courts)
// ==============================
const loadAdminCourtDirectory = async () => {
  if (!isAdmin.value) return
  try {
    const list = await listAdminCourts()
    const map = { ...allCourtMap.value }
    for (const c of list) {
      map[c.id] = c.name
    }
    allCourtMap.value = map
  } catch {
    // 非關鍵失敗，仍可使用 timetable 回傳的啟用場地名稱
  }
}

const loadTimetable = async () => {
  if (!hasBackofficeAccess.value) return
  timetableLoading.value = true
  try {
    const data = await getTimetable(selectedDate.value)
    timetable.value = data
    const map = { ...allCourtMap.value }
    for (const c of data?.grid?.courts || []) {
      map[c.id] = c.name
    }
    allCourtMap.value = map
  } catch (err) {
    showToast(err?.message || '載入每日排程失敗', 'error')
  } finally {
    timetableLoading.value = false
  }
}

const loadInitialData = async () => {
  if (!hasBackofficeAccess.value) return
  try {
    const venue = await getVenue()
    venueInfo.value = venue
    if (venue?.serverTime) {
      const serverToday = getTodayTaipei(venue.serverTime)
      todayDate.value = serverToday
      selectedDate.value = serverToday
    }
  } catch {
    // 若 /venue 暫時失敗仍以本地台灣日期載入
  }
  await Promise.all([loadTimetable(), loadAdminCourtDirectory()])
}

const refreshAll = async () => {
  await loadTimetable()
  ordersRefreshKey.value += 1
}

onMounted(async () => {
  await initAuth()
  if (hasBackofficeAccess.value) {
    await loadInitialData()
  }
})

watch(selectedDate, () => {
  if (hasBackofficeAccess.value) {
    loadTimetable()
  }
})

const handleLoggedIn = async () => {
  showToast(`歡迎回來，${authState.user?.name || authState.user?.email}！`)
  await loadInitialData()
}

const handleLogout = async () => {
  await logout()
  timetable.value = null
  showToast('已登出後台系統', 'info')
}

// ==============================
// 4. 排程棋盤資料對照與統計
// ==============================
const activeCourts = computed(() => timetable.value?.grid?.courts || [])

// 依後端 slots 動態推導當日時段欄位
const timeSlots = computed(() => {
  const slots = timetable.value?.grid?.slots || []
  const byHour = new Map()
  for (const s of slots) {
    const hour = formatTaipeiHour(s.startAt)
    if (!hour) continue
    if (!byHour.has(hour)) {
      byHour.set(hour, {
        hour,
        startAt: s.startAt,
        priceType: s.priceType,
      })
    }
  }
  return Array.from(byHour.values()).sort((a, b) =>
    a.hour.localeCompare(b.hour),
  )
})

// 建立 courtId + hour -> slot 物件對照
const slotLookup = computed(() => {
  const map = new Map()
  for (const s of timetable.value?.grid?.slots || []) {
    const hour = formatTaipeiHour(s.startAt)
    map.set(`${s.courtId}_${hour}`, s)
  }
  return map
})

// 建立 courtId + epochMs -> 訂單對照（優先顯示非取消/逾期訂單）
const orderBySlotEpoch = computed(() => {
  const map = new Map()
  for (const order of timetable.value?.orders || []) {
    for (const item of order.items || []) {
      const key = slotEpochKey(item.courtId, item.startAt)
      const prev = map.get(key)
      const isCurrentActive =
        order.status === 'CONFIRMED' || order.status === 'PENDING_PAYMENT'
      if (!prev || isCurrentActive) {
        map.set(key, { order, item })
      }
    }
  }
  return map
})

// 建立 courtId + epochMs -> 維修封場對照
const blockBySlotEpoch = computed(() => {
  const map = new Map()
  for (const block of timetable.value?.blocks || []) {
    if (!block.active) continue
    for (const item of block.items || []) {
      map.set(slotEpochKey(item.courtId, item.startAt), block)
    }
  }
  return map
})

const getCellData = (courtId, hour) => {
  const slot = slotLookup.value.get(`${courtId}_${hour}`) || null
  if (!slot) return { slot: null, order: null, item: null, block: null }
  const epochKey = slotEpochKey(courtId, slot.startAt)
  const orderEntry = orderBySlotEpoch.value.get(epochKey) || null
  const block = blockBySlotEpoch.value.get(epochKey) || null
  return {
    slot,
    order: orderEntry?.order || null,
    item: orderEntry?.item || null,
    block,
  }
}

// 統計卡片數據
const operatingSlots = computed(() =>
  (timetable.value?.grid?.slots || []).filter((s) => s.status !== 'CLOSED'),
)

const totalSlots = computed(() => operatingSlots.value.length)

const bookedCount = computed(
  () => operatingSlots.value.filter((s) => s.status === 'BOOKED').length,
)

const checkedInCount = computed(() => {
  let count = 0
  for (const s of operatingSlots.value) {
    if (s.status !== 'BOOKED') continue
    const entry = orderBySlotEpoch.value.get(slotEpochKey(s.courtId, s.startAt))
    if (entry?.order?.checkedInAt) count += 1
  }
  return count
})

const maintenanceCount = computed(
  () => operatingSlots.value.filter((s) => s.status === 'MAINTENANCE').length,
)

const occupancyRate = computed(() => {
  if (totalSlots.value === 0) return 0
  return Math.round((bookedCount.value / totalSlots.value) * 100)
})

const formatCourtName = (courtId) => {
  return allCourtMap.value[courtId] || `場地 (${String(courtId).slice(0, 6)})`
}

// ==============================
// 5. 彈窗 A：訂單詳情、編輯、報到、取消、示範付款
// ==============================
const orderDialogOpen = ref(false)
const activeOrder = ref(null)
const orderActionLoading = ref(false)

const editMode = ref(false)
const editOrderForm = ref({
  name: '',
  phone: '',
  notes: '',
})

const cancelMode = ref(false)
const cancelReason = ref('')

const openOrderDialog = async (order) => {
  activeOrder.value = order
  editMode.value = false
  cancelMode.value = false
  cancelReason.value = ''
  editOrderForm.value = {
    name: order.customer?.name || '',
    phone: order.customer?.phone || '',
    notes: order.notes || '',
  }
  orderDialogOpen.value = true

  // 背景向後端拉取最新單筆明細，確保 version 與狀態最新
  try {
    const fresh = await getBackofficeOrder(order.id)
    activeOrder.value = fresh
    editOrderForm.value = {
      name: fresh.customer?.name || '',
      phone: fresh.customer?.phone || '',
      notes: fresh.notes || '',
    }
  } catch {
    // 若失敗仍維持列表/看板帶入的資料
  }
}

const handleSaveOrderEdit = async () => {
  if (!activeOrder.value) return
  if (!editOrderForm.value.name.trim() || !editOrderForm.value.phone.trim()) {
    showToast('請填寫顧客姓名與聯絡電話', 'warning')
    return
  }
  orderActionLoading.value = true
  try {
    const updated = await editBackofficeOrder(activeOrder.value.id, {
      expectedVersion: activeOrder.value.version,
      customer: {
        name: editOrderForm.value.name.trim(),
        phone: editOrderForm.value.phone.trim(),
      },
      notes: editOrderForm.value.notes || '',
    })
    activeOrder.value = updated
    editMode.value = false
    await refreshAll()
    showToast('訂單聯絡資料與備註已更新！')
  } catch (err) {
    showToast(err?.message || '更新訂單失敗', 'error')
    if (err?.status === 409) {
      await openOrderDialog(activeOrder.value)
    }
  } finally {
    orderActionLoading.value = false
  }
}

const handleToggleCheckIn = async () => {
  if (!activeOrder.value) return
  orderActionLoading.value = true
  try {
    const wasCheckedIn = Boolean(activeOrder.value.checkedInAt)
    const updated = wasCheckedIn
      ? await reverseCheckInBackofficeOrder(
          activeOrder.value.id,
          activeOrder.value.version,
        )
      : await checkInBackofficeOrder(
          activeOrder.value.id,
          activeOrder.value.version,
        )
    activeOrder.value = updated
    await refreshAll()
    showToast(
      wasCheckedIn ? '已撤銷整張訂單的報到狀態' : '整張訂單報到核銷完成！',
      wasCheckedIn ? 'info' : 'success',
    )
  } catch (err) {
    showToast(err?.message || '報到狀態更新失敗', 'error')
    if (err?.status === 409) {
      await openOrderDialog(activeOrder.value)
    }
  } finally {
    orderActionLoading.value = false
  }
}

const handleConfirmCancelOrder = async () => {
  if (!activeOrder.value) return
  if (!cancelReason.value.trim()) {
    showToast('請輸入取消原因（後端必填）', 'warning')
    return
  }
  orderActionLoading.value = true
  try {
    const updated = await cancelBackofficeOrder(
      activeOrder.value.id,
      cancelReason.value.trim(),
    )
    activeOrder.value = updated
    cancelMode.value = false
    cancelReason.value = ''
    await refreshAll()
    showToast(
      updated.refundStatus === 'PENDING_REVIEW'
        ? '訂單已取消，已付款項標記為「待人工審核退款」'
        : '訂單已取消並釋出所有時段',
      'warning',
    )
  } catch (err) {
    showToast(err?.message || '取消訂單失敗', 'error')
  } finally {
    orderActionLoading.value = false
  }
}

const handleDemoConfirmPayment = async () => {
  if (!activeOrder.value) return
  if (
    !confirm(
      `確定要在本機模擬確認訂單 ${activeOrder.value.orderNumber} 付款 $${activeOrder.value.totalAmount} 元嗎？（此功能僅本機開發模式有效，不會連綠界）`,
    )
  ) {
    return
  }
  orderActionLoading.value = true
  try {
    const res = await confirmDemoPayment(
      activeOrder.value.id,
      activeOrder.value.totalAmount,
    )
    activeOrder.value = res.order
    await refreshAll()
    showToast('本機模擬付款成功，訂單已轉為 CONFIRMED / PAID！')
  } catch (err) {
    showToast(err?.message || '模擬付款失敗（Azure 環境預設停用）', 'error')
  } finally {
    orderActionLoading.value = false
  }
}

// ==============================
// 6. 彈窗 B：空場操作 (單格代訂 / 建立封場)
// ==============================
const emptySlotDialogOpen = ref(false)
const selectedEmptySlot = ref(null)
const emptySlotActionTab = ref('book') // 'book' | 'block'
const emptySlotSubmitting = ref(false)

const slotBookingForm = ref({
  source: 'WALK_IN',
  paymentMethod: 'CASH',
  name: '',
  phone: '',
  notes: '',
})

const slotBlockReason = ref('')

const openEmptySlotDialog = (court, slot) => {
  selectedEmptySlot.value = {
    courtId: court.id,
    courtName: court.name,
    courtType: court.type,
    hour: formatTaipeiHour(slot.startAt),
    startAt: slot.startAt,
    price: slot.price,
    priceType: slot.priceType,
  }
  emptySlotActionTab.value = 'book'
  slotBookingForm.value = {
    source: 'WALK_IN',
    paymentMethod: 'CASH',
    name: '',
    phone: '',
    notes: '',
  }
  slotBlockReason.value = ''
  emptySlotDialogOpen.value = true
}

const submitSlotBooking = async () => {
  if (!selectedEmptySlot.value) return
  if (!slotBookingForm.value.name.trim() || !slotBookingForm.value.phone.trim()) {
    showToast('請填寫預約人姓名與聯絡電話', 'warning')
    return
  }
  emptySlotSubmitting.value = true
  try {
    await createStaffOrder({
      source: slotBookingForm.value.source,
      paymentMethod: slotBookingForm.value.paymentMethod,
      customer: {
        name: slotBookingForm.value.name.trim(),
        phone: slotBookingForm.value.phone.trim(),
      },
      notes: slotBookingForm.value.notes || '',
      items: [
        {
          courtId: selectedEmptySlot.value.courtId,
          startAt: selectedEmptySlot.value.startAt,
        },
      ],
    })
    emptySlotDialogOpen.value = false
    await refreshAll()
    showToast(
      `已為 ${slotBookingForm.value.name.trim()} 建立代訂 ${selectedEmptySlot.value.courtName} (${selectedEmptySlot.value.hour})`,
    )
  } catch (err) {
    showToast(err?.message || '建立代訂失敗', 'error')
  } finally {
    emptySlotSubmitting.value = false
  }
}

const submitSlotBlock = async () => {
  if (!selectedEmptySlot.value) return
  if (!slotBlockReason.value.trim()) {
    showToast('請輸入維修封場原因', 'warning')
    return
  }
  emptySlotSubmitting.value = true
  try {
    await createBlock({
      reason: slotBlockReason.value.trim(),
      items: [
        {
          courtId: selectedEmptySlot.value.courtId,
          startAt: selectedEmptySlot.value.startAt,
        },
      ],
    })
    emptySlotDialogOpen.value = false
    await refreshAll()
    showToast('已建立維修封場排程', 'warning')
  } catch (err) {
    showToast(err?.message || '建立維修封場失敗', 'error')
  } finally {
    emptySlotSubmitting.value = false
  }
}

// ==============================
// 7. 彈窗 C：封場詳情與解除 (ADMIN)
// ==============================
const blockDialogOpen = ref(false)
const activeBlock = ref(null)
const blockActionLoading = ref(false)

const openBlockDialog = (block) => {
  activeBlock.value = block
  blockDialogOpen.value = true
}

const handleReleaseBlock = async () => {
  if (!activeBlock.value) return
  if (
    !confirm(
      `確定要解除此筆維修封場「${activeBlock.value.reason}」（共 ${activeBlock.value.items?.length || 0} 個時段）嗎？`,
    )
  ) {
    return
  }
  blockActionLoading.value = true
  try {
    await releaseBlock(activeBlock.value.id)
    blockDialogOpen.value = false
    await refreshAll()
    showToast('已解除維修封場並重新開放時段', 'info')
  } catch (err) {
    showToast(err?.message || '解除維修封場失敗', 'error')
  } finally {
    blockActionLoading.value = false
  }
}

// ==============================
// 8. 棋盤格統一點擊處理
// ==============================
const onCellClick = (court, hour) => {
  const { slot, order, block } = getCellData(court.id, hour)
  if (!slot) return

  if (slot.status === 'BOOKED' && order) {
    openOrderDialog(order)
    return
  }
  if (slot.status === 'MAINTENANCE' && block) {
    openBlockDialog(block)
    return
  }
  if (slot.status === 'AVAILABLE') {
    openEmptySlotDialog(court, slot)
    return
  }
  if (slot.status === 'PAST' && order) {
    openOrderDialog(order)
    return
  }
  if (slot.status === 'HOLDING') {
    showToast('會員正在線上結帳鎖定中 (TTL)，暫時無法代訂或封場', 'info')
  }
}

// ==============================
// 9. 彈窗 D：「快速臨櫃 / 電話代訂」專用彈窗（支援多選時段）
// ==============================
const quickBookingDialog = ref(false)
const quickSubmitting = ref(false)

const quickForm = ref({
  courtId: '',
  selectedStartAts: [],
  source: 'WALK_IN',
  paymentMethod: 'CASH',
  name: '',
  phone: '',
  notes: '',
})

const quickAvailableSlots = computed(() => {
  if (!quickForm.value.courtId) return []
  return (timetable.value?.grid?.slots || [])
    .filter(
      (s) =>
        s.courtId === quickForm.value.courtId && s.status === 'AVAILABLE',
    )
    .sort((a, b) => new Date(a.startAt) - new Date(b.startAt))
})

const quickTotalAmount = computed(() => {
  const selectedSet = new Set(quickForm.value.selectedStartAts)
  return quickAvailableSlots.value
    .filter((s) => selectedSet.has(s.startAt))
    .reduce((sum, s) => sum + (Number(s.price) || 0), 0)
})

const toggleQuickSlot = (startAt) => {
  const list = quickForm.value.selectedStartAts
  const idx = list.indexOf(startAt)
  if (idx >= 0) {
    list.splice(idx, 1)
  } else {
    if (list.length >= 12) {
      showToast('單筆代訂最多選擇 12 個時段', 'warning')
      return
    }
    list.push(startAt)
  }
}

const openQuickBookingDialog = () => {
  const firstCourtWithFree = activeCourts.value.find((c) =>
    (timetable.value?.grid?.slots || []).some(
      (s) => s.courtId === c.id && s.status === 'AVAILABLE',
    ),
  )
  const defaultCourtId =
    firstCourtWithFree?.id || activeCourts.value[0]?.id || ''

  quickForm.value = {
    courtId: defaultCourtId,
    selectedStartAts: [],
    source: 'WALK_IN',
    paymentMethod: 'CASH',
    name: '',
    phone: '',
    notes: '',
  }

  const firstFree = (timetable.value?.grid?.slots || []).find(
    (s) => s.courtId === defaultCourtId && s.status === 'AVAILABLE',
  )
  if (firstFree) {
    quickForm.value.selectedStartAts = [firstFree.startAt]
  }
  quickBookingDialog.value = true
}

watch(
  () => quickForm.value.courtId,
  () => {
    quickForm.value.selectedStartAts = []
  },
)

const submitQuickBooking = async () => {
  if (!quickForm.value.courtId || quickForm.value.selectedStartAts.length === 0) {
    showToast('請至少選擇一個可預約時段', 'warning')
    return
  }
  if (!quickForm.value.name.trim() || !quickForm.value.phone.trim()) {
    showToast('請輸入預約人姓名與聯絡電話', 'warning')
    return
  }

  quickSubmitting.value = true
  try {
    const items = quickForm.value.selectedStartAts.map((startAt) => ({
      courtId: quickForm.value.courtId,
      startAt,
    }))
    const order = await createStaffOrder({
      source: quickForm.value.source,
      paymentMethod: quickForm.value.paymentMethod,
      customer: {
        name: quickForm.value.name.trim(),
        phone: quickForm.value.phone.trim(),
      },
      notes: quickForm.value.notes || '',
      items,
    })
    quickBookingDialog.value = false
    await refreshAll()
    showToast(
      `代訂成功！訂單編號 ${order.orderNumber}（共 ${items.length} 小時，金額 $${order.totalAmount}）`,
    )
  } catch (err) {
    showToast(err?.message || '建立代訂失敗', 'error')
  } finally {
    quickSubmitting.value = false
  }
}

// ==============================
// 10. 管理員設定彈窗
// ==============================
const settingsDialogOpen = ref(false)

const handleSettingsUpdated = async () => {
  await Promise.all([loadTimetable(), loadAdminCourtDirectory()])
  ordersRefreshKey.value += 1
}
</script>

<template>
  <div class="max-w-7xl mx-auto px-6 py-6 w-full space-y-6">
    <!-- 初始化檢查中 -->
    <div
      v-if="!authState.initialized"
      class="py-20 text-center text-sm text-slate-400"
    >
      正在驗證後台登入工作階段...
    </div>

    <!-- 未登入或無後台權限：顯示登入卡片 -->
    <AdminLoginCard
      v-else-if="!hasBackofficeAccess"
      @logged-in="handleLoggedIn"
    />

    <!-- 已通過後台權限驗證：顯示統一後台介面 -->
    <template v-else>
      <!-- 頂部控制列：登入者身分、日期選擇、快捷操作 -->
      <div
        class="bg-white rounded-xl border border-slate-200 p-5 shadow-xs flex flex-col xl:flex-row xl:items-center justify-between gap-5"
      >
        <div class="flex items-center gap-5 flex-wrap">
          <!-- 目前登入身分 (真實 RBAC) -->
          <div class="shrink-0">
            <span
              class="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1 whitespace-nowrap"
            >
              目前登入帳號 (RBAC)
            </span>
            <div class="flex items-center gap-2">
              <v-chip
                size="small"
                variant="flat"
                :color="isAdmin ? 'secondary' : 'primary'"
                :prepend-icon="isAdmin ? 'mdi-shield-crown' : 'mdi-desk'"
                class="!px-3 font-semibold"
              >
                {{ isAdmin ? '場館管理員' : '櫃台人員' }}
              </v-chip>
              <span class="text-xs font-bold text-slate-700">
                {{ authState.user?.name || authState.user?.email }}
              </span>
              <v-btn
                size="x-small"
                variant="text"
                color="grey-darken-1"
                prepend-icon="mdi-logout"
                @click="handleLogout"
              >
                登出
              </v-btn>
            </div>
          </div>

          <div class="h-10 w-px bg-slate-200 hidden xl:block"></div>

          <!-- 日期選擇區 -->
          <div class="shrink-0">
            <span
              class="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1 whitespace-nowrap"
            >
              預約排程日期
            </span>
            <div class="flex items-center gap-2 flex-nowrap whitespace-nowrap">
              <button
                type="button"
                class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 hover:text-slate-900 transition-colors shadow-2xs shrink-0"
                title="切換至前一天"
                @click="shiftDate(-1)"
              >
                <v-icon icon="mdi-chevron-left" size="18"></v-icon>
              </button>

              <input
                v-model="selectedDate"
                type="date"
                class="border border-slate-200 rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white shadow-2xs focus:outline-emerald-500 whitespace-nowrap shrink-0 w-36"
              />

              <button
                type="button"
                class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 hover:text-slate-900 transition-colors shadow-2xs shrink-0"
                title="切換至後一天"
                @click="shiftDate(1)"
              >
                <v-icon icon="mdi-chevron-right" size="18"></v-icon>
              </button>

              <v-chip
                size="small"
                variant="flat"
                :color="
                  weekdayInfo.isWeekendOrHoliday ? 'amber-100' : 'slate-100'
                "
                :class="[
                  '!px-3 font-semibold whitespace-nowrap shrink-0',
                  weekdayInfo.isWeekendOrHoliday
                    ? 'text-amber-800'
                    : 'text-slate-600',
                ]"
              >
                <v-icon
                  :icon="
                    weekdayInfo.isWeekendOrHoliday
                      ? 'mdi-star'
                      : 'mdi-calendar-today'
                  "
                  size="14"
                  class="mr-1"
                ></v-icon>
                {{ weekdayInfo.label }}
              </v-chip>

              <div class="hidden sm:flex items-center gap-1.5 shrink-0 pl-1">
                <button
                  type="button"
                  :class="[
                    'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                    selectedDate === todayDate
                      ? 'bg-emerald-50 border-emerald-300 text-emerald-700 font-bold'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                  ]"
                  @click="setQuickDate(todayDate)"
                >
                  今天
                </button>
                <button
                  type="button"
                  :class="[
                    'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                    selectedDate === tomorrowDate
                      ? 'bg-emerald-50 border-emerald-300 text-emerald-700 font-bold'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                  ]"
                  @click="setQuickDate(tomorrowDate)"
                >
                  明天
                </button>
                <button
                  type="button"
                  :class="[
                    'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                    selectedDate === nextSaturdayDate
                      ? 'bg-amber-50 border-amber-300 text-amber-800 font-bold'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                  ]"
                  @click="setQuickDate(nextSaturdayDate)"
                >
                  本週六
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 快捷動作按鈕 -->
        <div class="flex items-center gap-2 flex-wrap shrink-0">
          <v-btn
            variant="outlined"
            color="grey-darken-1"
            prepend-icon="mdi-refresh"
            rounded="lg"
            size="small"
            :loading="timetableLoading"
            @click="refreshAll"
          >
            同步
          </v-btn>

          <v-btn
            color="primary"
            variant="flat"
            prepend-icon="mdi-account-plus"
            rounded="lg"
            size="small"
            class="!px-4 font-semibold shadow-xs"
            @click="openQuickBookingDialog"
          >
            快速臨櫃代訂
          </v-btn>

          <!-- 管理員專用：場館營運設定 -->
          <v-btn
            v-if="isAdmin"
            color="secondary"
            variant="tonal"
            prepend-icon="mdi-tune-variant"
            rounded="lg"
            size="small"
            class="!px-4 font-semibold"
            @click="settingsDialogOpen = true"
          >
            場館營運設定
          </v-btn>
        </div>
      </div>

      <!-- 數據概覽卡片 -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div
          class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
        >
          <div
            class="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center shrink-0"
          >
            <v-icon icon="mdi-percent" size="20"></v-icon>
          </div>
          <div>
            <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
              當日場地使用率
            </p>
            <p class="text-lg font-bold text-slate-800">{{ occupancyRate }}%</p>
          </div>
        </div>

        <div
          class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
        >
          <div
            class="w-10 h-10 rounded-lg bg-blue-50 text-blue-600 flex items-center justify-center shrink-0"
          >
            <v-icon icon="mdi-calendar-check" size="20"></v-icon>
          </div>
          <div>
            <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
              已預約時段數
            </p>
            <p class="text-lg font-bold text-slate-800">
              {{ bookedCount }}
              <span class="text-xs text-slate-400 font-normal">
                / {{ totalSlots }}
              </span>
            </p>
          </div>
        </div>

        <div
          class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
        >
          <div
            class="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center shrink-0"
          >
            <v-icon icon="mdi-check-decagram" size="20"></v-icon>
          </div>
          <div>
            <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
              現場已報到時段
            </p>
            <p class="text-lg font-bold text-slate-800">
              {{ checkedInCount }}
              <span class="text-xs text-slate-400 font-normal">小時</span>
            </p>
          </div>
        </div>

        <div
          class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
        >
          <div
            class="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center shrink-0"
          >
            <v-icon icon="mdi-wrench-clock" size="20"></v-icon>
          </div>
          <div>
            <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
              場地維護時段
            </p>
            <p class="text-lg font-bold text-slate-800">
              {{ maintenanceCount }}
              <span class="text-xs text-slate-400 font-normal">小時</span>
            </p>
          </div>
        </div>
      </div>

      <!-- 主視圖頁籤切換：即時排程棋盤圖 vs 訂單列表 -->
      <div class="flex items-center gap-2">
        <v-btn
          :variant="activeView === 'timetable' ? 'flat' : 'outlined'"
          :color="activeView === 'timetable' ? 'primary' : 'grey-darken-1'"
          rounded="lg"
          size="small"
          prepend-icon="mdi-table-clock"
          class="font-semibold"
          @click="activeView = 'timetable'"
        >
          即時排程棋盤看板
        </v-btn>
        <v-btn
          :variant="activeView === 'orders' ? 'flat' : 'outlined'"
          :color="activeView === 'orders' ? 'primary' : 'grey-darken-1'"
          rounded="lg"
          size="small"
          prepend-icon="mdi-receipt-text-outline"
          class="font-semibold"
          @click="activeView = 'orders'"
        >
          後台訂單管理列表
        </v-btn>
      </div>

      <!-- ============================== -->
      <!-- 視圖 1：每日排程棋盤看板 -->
      <!-- ============================== -->
      <div
        v-if="activeView === 'timetable'"
        class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden"
      >
        <div
          class="p-5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
        >
          <div>
            <h2
              class="text-base font-bold text-slate-800 flex items-center gap-2"
            >
              <v-icon icon="mdi-table-clock" color="primary" size="20"></v-icon>
              即時排程棋盤圖 — {{ selectedDate }}
            </h2>
            <p class="text-xs text-slate-400 mt-0.5">
              點選「可預約」可建立代訂或封場；點選「已預約」可編輯聯絡資料、整張訂單報到核銷或取消
            </p>
          </div>

          <!-- 狀態圖例 -->
          <div class="flex items-center gap-1.5 flex-wrap shrink-0">
            <v-chip
              size="x-small"
              variant="flat"
              color="emerald-50"
              class="!px-2.5 text-emerald-700 font-medium border border-emerald-200"
            >
              <span class="w-2 h-2 rounded-full bg-emerald-500 mr-1.5"></span>
              可預約
            </v-chip>
            <v-chip
              size="x-small"
              variant="flat"
              color="blue-50"
              class="!px-2.5 text-blue-700 font-medium border border-blue-200"
            >
              <span class="w-2 h-2 rounded-full bg-blue-500 mr-1.5"></span>
              已預約
            </v-chip>
            <v-chip
              size="x-small"
              variant="flat"
              color="amber-50"
              class="!px-2.5 text-amber-700 font-medium border border-amber-200"
            >
              <span class="w-2 h-2 rounded-full bg-amber-500 mr-1.5"></span>
              鎖定中 (TTL)
            </v-chip>
            <v-chip
              size="x-small"
              variant="flat"
              color="rose-50"
              class="!px-2.5 text-rose-700 font-medium border border-rose-200"
            >
              <span class="w-2 h-2 rounded-full bg-rose-500 mr-1.5"></span>
              維護封場
            </v-chip>
            <v-chip
              size="x-small"
              variant="flat"
              color="slate-100"
              class="!px-2.5 text-slate-500 font-medium border border-slate-200"
            >
              已過 / 休館
            </v-chip>
          </div>
        </div>

        <div
          v-if="timetableLoading && !timetable"
          class="p-12 text-center text-sm text-slate-400"
        >
          正在載入當日排程資料...
        </div>

        <div
          v-else-if="timeSlots.length === 0"
          class="p-12 text-center text-sm text-slate-400 space-y-1"
        >
          <p class="font-semibold text-slate-600">
            {{ selectedDate }} 無開放營業時段（本日休館或尚無啟用場地）
          </p>
          <p class="text-xs">
            管理員可至「場館營運設定」調整每週營業時間或特殊日曆例外。
          </p>
        </div>

        <div v-else class="overflow-x-auto">
          <table class="w-full border-collapse text-left text-xs">
            <thead>
              <tr
                class="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold"
              >
                <th
                  class="p-3 w-36 min-w-[140px] sticky left-0 bg-slate-50 z-10 border-r border-slate-200 whitespace-nowrap"
                >
                  場地資訊
                </th>
                <th
                  v-for="slotCol in timeSlots"
                  :key="slotCol.hour"
                  class="p-2.5 text-center min-w-[140px] border-r border-slate-100 font-medium whitespace-nowrap"
                >
                  <div class="font-bold text-slate-700">{{ slotCol.hour }}</div>
                  <div class="text-3xs font-normal text-slate-400 mt-0.5">
                    {{ getPriceTypeLabel(slotCol.priceType) }}
                  </div>
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr
                v-for="court in activeCourts"
                :key="court.id"
                class="hover:bg-slate-50/50 transition-colors"
              >
                <td
                  class="p-3 sticky left-0 bg-white z-10 border-r border-slate-200 font-medium whitespace-nowrap"
                >
                  <div class="font-bold text-slate-800">{{ court.name }}</div>
                  <div class="text-2xs text-slate-400 mt-0.5">
                    {{ court.type }}
                  </div>
                </td>

                <td
                  v-for="slotCol in timeSlots"
                  :key="slotCol.hour"
                  class="p-1 border-r border-slate-100 text-center align-middle"
                >
                  <template
                    v-for="cell in [getCellData(court.id, slotCol.hour)]"
                    :key="`${court.id}_${slotCol.hour}`"
                  >
                    <!-- 1. 已預約 (BOOKED) -->
                    <button
                      v-if="cell.slot?.status === 'BOOKED'"
                      type="button"
                      :class="[
                        'w-full h-16 rounded-lg p-2 text-left transition-all border flex flex-col justify-between hover:shadow-2xs select-none',
                        cell.order?.status === 'PENDING_PAYMENT'
                          ? 'bg-amber-50/80 border-amber-300 hover:border-amber-500'
                          : 'bg-blue-50/90 border-blue-200 hover:border-blue-400',
                      ]"
                      @click="onCellClick(court, slotCol.hour)"
                    >
                      <div
                        class="flex items-center justify-between gap-1 w-full"
                      >
                        <span
                          class="font-bold text-slate-900 truncate whitespace-nowrap text-xs"
                        >
                          {{ cell.order?.customer?.name || '已預約' }}
                        </span>
                        <v-icon
                          v-if="cell.order?.checkedInAt"
                          icon="mdi-check-circle"
                          color="success"
                          size="15"
                          class="shrink-0"
                          title="已到場報到"
                        ></v-icon>
                      </div>
                      <div
                        class="flex items-center justify-between text-2xs font-medium w-full whitespace-nowrap gap-1"
                      >
                        <span
                          :class="
                            cell.order?.paymentStatus === 'PAID'
                              ? 'text-emerald-700'
                              : 'text-amber-700'
                          "
                        >
                          {{
                            cell.order?.status === 'PENDING_PAYMENT'
                              ? '待付款'
                              : cell.order?.paymentStatus === 'PAID'
                                ? '已付款'
                                : '未收款'
                          }}
                        </span>
                        <span
                          v-if="cell.order?.checkedInAt"
                          class="text-emerald-700 font-bold"
                        >
                          已報到
                        </span>
                        <span v-else class="text-slate-400">未報到</span>
                      </div>
                    </button>

                    <!-- 2. 線上結帳鎖定中 (HOLDING) -->
                    <button
                      v-else-if="cell.slot?.status === 'HOLDING'"
                      type="button"
                      class="w-full h-16 rounded-lg p-2 text-left border bg-amber-50/90 border-amber-200 hover:border-amber-400 flex flex-col justify-between select-none"
                      @click="onCellClick(court, slotCol.hour)"
                    >
                      <span
                        class="text-xs font-bold text-amber-900 truncate whitespace-nowrap"
                      >
                        線上結帳中
                      </span>
                      <div
                        class="flex items-center gap-1 text-2xs text-amber-700 whitespace-nowrap"
                      >
                        <v-icon icon="mdi-timer-sand" size="13"></v-icon>
                        <span>暫存鎖定中</span>
                      </div>
                    </button>

                    <!-- 3. 維護封場 (MAINTENANCE) -->
                    <button
                      v-else-if="cell.slot?.status === 'MAINTENANCE'"
                      type="button"
                      class="w-full h-16 rounded-lg p-2 text-left border bg-rose-50/90 border-rose-200 hover:border-rose-400 flex flex-col justify-between select-none"
                      @click="onCellClick(court, slotCol.hour)"
                    >
                      <span
                        class="text-xs font-bold text-rose-900 whitespace-nowrap truncate"
                      >
                        維修封場
                      </span>
                      <span
                        class="text-2xs text-rose-600 whitespace-nowrap truncate"
                        :title="cell.block?.reason"
                      >
                        {{ cell.block?.reason || '暫停預約' }}
                      </span>
                    </button>

                    <!-- 4. 可預約空場 (AVAILABLE) -->
                    <button
                      v-else-if="cell.slot?.status === 'AVAILABLE'"
                      type="button"
                      class="w-full h-16 rounded-lg border border-dashed border-slate-200 hover:border-emerald-400 hover:bg-emerald-50/50 transition-all flex flex-col items-center justify-center text-slate-400 hover:text-emerald-600 group select-none"
                      @click="onCellClick(court, slotCol.hour)"
                    >
                      <span
                        class="text-xs font-bold text-slate-500 group-hover:text-emerald-700"
                      >
                        ${{ cell.slot.price }}
                      </span>
                      <span
                        class="text-2xs text-slate-400 group-hover:text-emerald-600 font-medium whitespace-nowrap"
                      >
                        空場代訂
                      </span>
                    </button>

                    <!-- 5. 已過時段 (PAST) -->
                    <button
                      v-else-if="cell.slot?.status === 'PAST'"
                      type="button"
                      :disabled="!cell.order"
                      :class="[
                        'w-full h-16 rounded-lg p-2 border flex flex-col items-center justify-center select-none',
                        cell.order
                          ? 'bg-slate-100 border-slate-300 hover:border-slate-400 text-slate-600 cursor-pointer'
                          : 'bg-slate-50 border-slate-100 text-slate-300 cursor-not-allowed',
                      ]"
                      @click="cell.order && onCellClick(court, slotCol.hour)"
                    >
                      <span
                        v-if="cell.order"
                        class="text-2xs font-bold text-slate-700 truncate w-full text-center"
                      >
                        {{ cell.order.customer?.name }}
                      </span>
                      <span class="text-2xs">
                        {{ cell.order ? '歷史預約' : '已過時段' }}
                      </span>
                    </button>

                    <!-- 6. 非營業 (CLOSED) -->
                    <div
                      v-else
                      class="w-full h-16 rounded-lg bg-slate-50/60 border border-slate-100 flex items-center justify-center text-2xs text-slate-300 select-none"
                    >
                      非營業
                    </div>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ============================== -->
      <!-- 視圖 2：後台訂單列表 (分頁) -->
      <!-- ============================== -->
      <AdminOrdersPanel
        v-else
        :selected-date="selectedDate"
        :court-name-map="allCourtMap"
        :refresh-key="ordersRefreshKey"
        @select-order="openOrderDialog"
        @toast="handleChildToast"
      />

      <!-- ============================================== -->
      <!-- 彈窗 A：訂單詳情、編輯、報到核銷、取消、示範付款 -->
      <!-- ============================================== -->
      <v-dialog v-model="orderDialogOpen" max-width="580">
        <v-card v-if="activeOrder" rounded="xl" class="overflow-hidden">
          <div
            class="bg-slate-900 text-white p-5 flex items-center justify-between"
          >
            <div>
              <h3 class="text-base font-bold flex items-center gap-2">
                <v-icon icon="mdi-receipt-text" color="primary" size="20" />
                訂單 {{ activeOrder.orderNumber }}
              </h3>
              <p class="text-xs text-slate-400 mt-0.5">
                {{ getSourceLabel(activeOrder.source) }} ・ 建立於
                {{ formatTaipeiDateTime(activeOrder.createdAt) }}
              </p>
            </div>
            <v-btn
              icon="mdi-close"
              variant="text"
              size="small"
              color="grey-lighten-1"
              @click="orderDialogOpen = false"
            ></v-btn>
          </div>

          <v-card-text class="p-6 space-y-4 max-h-[75vh] overflow-y-auto">
            <!-- 狀態標籤列 -->
            <div class="flex flex-wrap gap-2">
              <v-chip
                size="small"
                variant="flat"
                :color="
                  ORDER_STATUS_META[activeOrder.status]?.chipColor || 'grey'
                "
                class="!px-3 font-semibold"
              >
                {{
                  ORDER_STATUS_META[activeOrder.status]?.label ||
                  activeOrder.status
                }}
              </v-chip>

              <v-chip
                size="small"
                variant="tonal"
                :color="
                  PAYMENT_STATUS_META[activeOrder.paymentStatus]?.chipColor ||
                  'grey'
                "
                class="!px-3 font-semibold"
              >
                {{
                  PAYMENT_STATUS_META[activeOrder.paymentStatus]?.label ||
                  activeOrder.paymentStatus
                }}
                ({{ getPaymentMethodLabel(activeOrder.paymentMethod) }})
              </v-chip>

              <v-chip
                v-if="activeOrder.checkedInAt"
                size="small"
                variant="flat"
                color="success"
                class="!px-3 font-semibold"
              >
                已報到 ({{ formatTaipeiDateTime(activeOrder.checkedInAt) }})
              </v-chip>

              <v-chip
                v-if="activeOrder.refundStatus === 'PENDING_REVIEW'"
                size="small"
                variant="flat"
                color="error"
                class="!px-3 font-semibold"
              >
                {{ REFUND_STATUS_META.PENDING_REVIEW.label }}
              </v-chip>
            </div>

            <!-- 顧客聯絡與備註（可切換編輯模式） -->
            <div
              class="bg-slate-50 rounded-xl p-4 border border-slate-200 space-y-2.5 text-sm"
            >
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-slate-500 uppercase">
                  顧客資料與備註 (v{{ activeOrder.version }})
                </span>
                <v-btn
                  v-if="
                    !editMode &&
                    activeOrder.status !== 'CANCELLED' &&
                    activeOrder.status !== 'EXPIRED'
                  "
                  size="x-small"
                  variant="text"
                  color="primary"
                  prepend-icon="mdi-pencil"
                  @click="editMode = true"
                >
                  編輯聯絡資料
                </v-btn>
              </div>

              <template v-if="!editMode">
                <div class="flex justify-between">
                  <span class="text-slate-500">預約顧客：</span>
                  <span class="font-bold text-slate-800">
                    {{ activeOrder.customer?.name }}
                  </span>
                </div>
                <div class="flex justify-between">
                  <span class="text-slate-500">聯絡電話：</span>
                  <span class="font-medium text-slate-700">
                    {{ activeOrder.customer?.phone }}
                  </span>
                </div>
                <div class="flex justify-between">
                  <span class="text-slate-500">備註說明：</span>
                  <span class="text-slate-700 text-right max-w-xs">
                    {{ activeOrder.notes || '無' }}
                  </span>
                </div>
                <div
                  class="flex justify-between pt-2 border-t border-slate-200"
                >
                  <span class="text-slate-500">訂單總金額：</span>
                  <span class="font-bold text-emerald-700">
                    ${{ activeOrder.totalAmount }} ({{ activeOrder.currency }})
                  </span>
                </div>
              </template>

              <div v-else class="space-y-3 pt-1">
                <div>
                  <label class="text-xs font-semibold text-slate-600 block mb-1"
                    >顧客姓名</label
                  >
                  <input
                    v-model="editOrderForm.name"
                    type="text"
                    class="w-full border border-slate-200 rounded-lg px-3 py-1.5 text-sm bg-white"
                  />
                </div>
                <div>
                  <label class="text-xs font-semibold text-slate-600 block mb-1"
                    >聯絡電話</label
                  >
                  <input
                    v-model="editOrderForm.phone"
                    type="tel"
                    class="w-full border border-slate-200 rounded-lg px-3 py-1.5 text-sm bg-white"
                  />
                </div>
                <div>
                  <label class="text-xs font-semibold text-slate-600 block mb-1"
                    >備註</label
                  >
                  <input
                    v-model="editOrderForm.notes"
                    type="text"
                    class="w-full border border-slate-200 rounded-lg px-3 py-1.5 text-sm bg-white"
                  />
                </div>
                <div class="flex justify-end gap-2">
                  <v-btn
                    size="small"
                    variant="text"
                    color="grey"
                    @click="editMode = false"
                  >
                    取消
                  </v-btn>
                  <v-btn
                    size="small"
                    color="primary"
                    variant="flat"
                    :loading="orderActionLoading"
                    @click="handleSaveOrderEdit"
                  >
                    儲存修改
                  </v-btn>
                </div>
              </div>
            </div>

            <!-- 訂單涵蓋的所有時段 -->
            <div class="space-y-1.5">
              <div class="text-xs font-bold text-slate-500">
                預約時段明細（共 {{ activeOrder.items?.length || 0 }} 小時，操作影響整張訂單）
              </div>
              <div
                class="rounded-xl border border-slate-200 divide-y divide-slate-100 text-xs"
              >
                <div
                  v-for="item in activeOrder.items"
                  :key="item.id"
                  class="px-3.5 py-2.5 flex items-center justify-between"
                >
                  <div>
                    <span class="font-bold text-slate-800">
                      {{ formatCourtName(item.courtId) }}
                    </span>
                    <span class="text-slate-500 ml-2">
                      {{ formatTaipeiDate(item.startAt) }}
                      {{ formatTaipeiHour(item.startAt) }}–{{
                        formatTaipeiHour(item.endAt)
                      }}
                    </span>
                  </div>
                  <span class="font-semibold text-emerald-700">
                    ${{ item.unitPrice }}
                  </span>
                </div>
              </div>
            </div>

            <!-- 取消原因顯示（若已取消） -->
            <div
              v-if="activeOrder.status === 'CANCELLED'"
              class="p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-800"
            >
              <strong>取消原因：</strong>
              {{ activeOrder.cancellationReason || '—' }}（{{
                formatTaipeiDateTime(activeOrder.cancelledAt)
              }}）
            </div>

            <!-- 取消確認輸入區塊 -->
            <div
              v-if="cancelMode"
              class="p-4 rounded-xl bg-rose-50 border border-rose-200 space-y-3"
            >
              <label class="text-xs font-bold text-rose-900 block">
                請輸入取消原因（將取消整張訂單並釋出所有時段）
              </label>
              <input
                v-model="cancelReason"
                type="text"
                placeholder="例如：顧客電話通知取消 / 臨時異動"
                class="w-full border border-rose-200 rounded-lg px-3 py-2 text-sm bg-white focus:outline-rose-500"
              />
              <div class="flex justify-end gap-2">
                <v-btn
                  size="small"
                  variant="text"
                  color="grey-darken-1"
                  @click="cancelMode = false"
                >
                  返回
                </v-btn>
                <v-btn
                  size="small"
                  color="error"
                  variant="flat"
                  :loading="orderActionLoading"
                  @click="handleConfirmCancelOrder"
                >
                  確認取消整張訂單
                </v-btn>
              </div>
            </div>

            <!-- 操作按鈕列 -->
            <div
              v-if="
                activeOrder.status === 'CONFIRMED' ||
                activeOrder.status === 'PENDING_PAYMENT'
              "
              class="flex gap-2 flex-wrap pt-1"
            >
              <v-btn
                v-if="activeOrder.status === 'CONFIRMED'"
                :color="activeOrder.checkedInAt ? 'grey-darken-1' : 'success'"
                variant="flat"
                rounded="lg"
                class="flex-1 !px-4 font-semibold"
                :prepend-icon="
                  activeOrder.checkedInAt ? 'mdi-undo' : 'mdi-check-all'
                "
                :loading="orderActionLoading"
                @click="handleToggleCheckIn"
              >
                {{
                  activeOrder.checkedInAt
                    ? '撤銷整張訂單報到'
                    : '確認整張訂單報到 (Check-in)'
                }}
              </v-btn>

              <v-btn
                v-if="isAdmin && activeOrder.status === 'PENDING_PAYMENT'"
                color="secondary"
                variant="tonal"
                rounded="lg"
                class="flex-1 !px-4 font-semibold"
                prepend-icon="mdi-credit-card-check-outline"
                :loading="orderActionLoading"
                @click="handleDemoConfirmPayment"
              >
                本機模擬付款確認
              </v-btn>

              <v-btn
                v-if="!cancelMode"
                color="error"
                variant="outlined"
                rounded="lg"
                class="!px-4 font-semibold"
                prepend-icon="mdi-trash-can-outline"
                :disabled="Boolean(activeOrder.checkedInAt)"
                :title="
                  activeOrder.checkedInAt
                    ? '已報到訂單須先撤銷報到才能取消'
                    : ''
                "
                @click="cancelMode = true"
              >
                取消整張訂單
              </v-btn>
            </div>
          </v-card-text>
        </v-card>
      </v-dialog>

      <!-- ============================================== -->
      <!-- 彈窗 B：空場操作（單格快速代訂 / 管理員封場） -->
      <!-- ============================================== -->
      <v-dialog v-model="emptySlotDialogOpen" max-width="520">
        <v-card v-if="selectedEmptySlot" rounded="xl" class="overflow-hidden">
          <div
            class="bg-slate-900 text-white p-5 flex items-center justify-between"
          >
            <div>
              <h3 class="text-base font-bold flex items-center gap-2">
                <v-icon icon="mdi-badminton" color="primary" size="20" />
                {{ selectedEmptySlot.courtName }} ({{ selectedEmptySlot.hour }})
              </h3>
              <p class="text-xs text-slate-400 mt-0.5">
                日期：{{ selectedDate }} ・ 即時費率 ${{
                  selectedEmptySlot.price
                }}
                ({{ getPriceTypeLabel(selectedEmptySlot.priceType) }})
              </p>
            </div>
            <v-btn
              icon="mdi-close"
              variant="text"
              size="small"
              color="grey-lighten-1"
              @click="emptySlotDialogOpen = false"
            ></v-btn>
          </div>

          <!-- 切換頁籤（管理員可切封場） -->
          <div
            v-if="isAdmin"
            class="flex border-b border-slate-200 bg-slate-50 px-5"
          >
            <button
              type="button"
              :class="[
                'py-2.5 px-4 text-xs font-bold border-b-2 transition-colors',
                emptySlotActionTab === 'book'
                  ? 'border-emerald-500 text-emerald-600 bg-white'
                  : 'border-transparent text-slate-500',
              ]"
              @click="emptySlotActionTab = 'book'"
            >
              臨櫃 / 電話代訂
            </button>
            <button
              type="button"
              :class="[
                'py-2.5 px-4 text-xs font-bold border-b-2 transition-colors',
                emptySlotActionTab === 'block'
                  ? 'border-rose-500 text-rose-600 bg-white'
                  : 'border-transparent text-slate-500',
              ]"
              @click="emptySlotActionTab = 'block'"
            >
              設定維修封場 (ADMIN)
            </button>
          </div>

          <v-card-text class="p-6 space-y-4">
            <!-- 代訂表單 -->
            <template v-if="emptySlotActionTab === 'book'">
              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="text-xs font-semibold text-slate-700 block mb-1"
                    >預約來源</label
                  >
                  <select
                    v-model="slotBookingForm.source"
                    class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm bg-white"
                  >
                    <option value="WALK_IN">現場臨櫃 (WALK_IN)</option>
                    <option value="PHONE">電話代訂 (PHONE)</option>
                  </select>
                </div>
                <div>
                  <label class="text-xs font-semibold text-slate-700 block mb-1"
                    >預計收款方式</label
                  >
                  <select
                    v-model="slotBookingForm.paymentMethod"
                    class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm bg-white"
                  >
                    <option value="CASH">現場現金 (CASH)</option>
                    <option value="ONSITE_CARD">現場刷卡 (ONSITE_CARD)</option>
                  </select>
                </div>
              </div>

              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >預約人姓名 / 稱謂 <span class="text-rose-500">*</span></label
                >
                <input
                  v-model="slotBookingForm.name"
                  type="text"
                  placeholder="例如：王小明"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
                />
              </div>

              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >聯絡電話 <span class="text-rose-500">*</span></label
                >
                <input
                  v-model="slotBookingForm.phone"
                  type="tel"
                  placeholder="例如：0912-345-678"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
                />
              </div>

              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >備註（選填）</label
                >
                <input
                  v-model="slotBookingForm.notes"
                  type="text"
                  placeholder="例如：需租借球拍"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
                />
              </div>

              <p class="text-2xs text-slate-400">
                代訂建立後狀態為「已確認 / 未收款 (CONFIRMED / UNPAID)」。
              </p>

              <v-btn
                color="primary"
                variant="flat"
                block
                rounded="lg"
                prepend-icon="mdi-check"
                :loading="emptySlotSubmitting"
                class="font-semibold"
                @click="submitSlotBooking"
              >
                確認建立代訂 (${{ selectedEmptySlot.price }})
              </v-btn>
            </template>

            <!-- 維修封場表單 -->
            <template v-else>
              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >封場原因 <span class="text-rose-500">*</span></label
                >
                <input
                  v-model="slotBlockReason"
                  type="text"
                  placeholder="例如：燈具檢修 / 地板打蠟"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-rose-500"
                />
              </div>
              <v-btn
                color="error"
                variant="flat"
                block
                rounded="lg"
                prepend-icon="mdi-wrench"
                :loading="emptySlotSubmitting"
                class="font-semibold"
                @click="submitSlotBlock"
              >
                確認建立維修封場
              </v-btn>
            </template>
          </v-card-text>
        </v-card>
      </v-dialog>

      <!-- ============================================== -->
      <!-- 彈窗 C：維修封場詳情與解除 -->
      <!-- ============================================== -->
      <v-dialog v-model="blockDialogOpen" max-width="480">
        <v-card v-if="activeBlock" rounded="xl" class="overflow-hidden">
          <div
            class="bg-rose-900 text-white p-5 flex items-center justify-between"
          >
            <div>
              <h3 class="text-base font-bold flex items-center gap-2">
                <v-icon icon="mdi-wrench-clock" size="20" />
                維修封場紀錄
              </h3>
              <p class="text-xs text-rose-200 mt-0.5">
                建立於 {{ formatTaipeiDateTime(activeBlock.createdAt) }}
              </p>
            </div>
            <v-btn
              icon="mdi-close"
              variant="text"
              size="small"
              color="white"
              @click="blockDialogOpen = false"
            ></v-btn>
          </div>

          <v-card-text class="p-6 space-y-4">
            <div
              class="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm"
            >
              <div class="text-xs text-rose-600 font-semibold">封場原因</div>
              <div class="font-bold text-rose-950 mt-1">
                {{ activeBlock.reason }}
              </div>
            </div>

            <div class="space-y-1.5">
              <div class="text-xs font-bold text-slate-500">
                封場涵蓋時段（解除將一併釋出）
              </div>
              <div
                class="rounded-xl border border-slate-200 divide-y divide-slate-100 text-xs"
              >
                <div
                  v-for="(item, idx) in activeBlock.items"
                  :key="idx"
                  class="px-3.5 py-2 flex justify-between"
                >
                  <span class="font-bold text-slate-800">
                    {{ formatCourtName(item.courtId) }}
                  </span>
                  <span class="text-slate-600">
                    {{ formatTaipeiDate(item.startAt) }}
                    {{ formatTaipeiHour(item.startAt) }}–{{
                      formatTaipeiHour(item.endAt)
                    }}
                  </span>
                </div>
              </div>
            </div>

            <v-btn
              v-if="isAdmin"
              color="error"
              variant="flat"
              block
              rounded="lg"
              prepend-icon="mdi-lock-open-variant-outline"
              :loading="blockActionLoading"
              class="font-semibold"
              @click="handleReleaseBlock"
            >
              解除整筆維修封場
            </v-btn>
            <p v-else class="text-xs text-slate-400 text-center">
              僅場館管理員 (ADMIN) 可解除維修封場。
            </p>
          </v-card-text>
        </v-card>
      </v-dialog>

      <!-- ============================================== -->
      <!-- 彈窗 D：「快速臨櫃 / 電話代訂」（支援多選時段） -->
      <!-- ============================================== -->
      <v-dialog v-model="quickBookingDialog" max-width="580">
        <v-card rounded="xl" class="overflow-hidden">
          <div
            class="bg-emerald-700 text-white p-5 flex items-center justify-between"
          >
            <div class="flex items-center gap-3">
              <div
                class="w-9 h-9 rounded-lg bg-emerald-600 flex items-center justify-center"
              >
                <v-icon icon="mdi-account-plus" size="22"></v-icon>
              </div>
              <div>
                <h3 class="text-base font-bold">快速臨櫃 / 電話代訂</h3>
                <p class="text-xs text-emerald-100 mt-0.5">
                  預約日期：{{ selectedDate }}（單筆可選 1–12 個空場時段）
                </p>
              </div>
            </div>
            <v-btn
              icon="mdi-close"
              variant="text"
              size="small"
              color="white"
              @click="quickBookingDialog = false"
            ></v-btn>
          </div>

          <v-card-text class="p-6 space-y-4 max-h-[75vh] overflow-y-auto">
            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >選擇場地</label
              >
              <select
                v-model="quickForm.courtId"
                class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm bg-white"
              >
                <option v-for="c in activeCourts" :key="c.id" :value="c.id">
                  {{ c.name }} ({{ c.type }})
                </option>
              </select>
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1.5">
                選擇可預約時段（已選 {{ quickForm.selectedStartAts.length }} 小時）
              </label>
              <div
                v-if="quickAvailableSlots.length === 0"
                class="p-3 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-400 text-center"
              >
                此場地於 {{ selectedDate }} 目前無可預約空場
              </div>
              <div v-else class="grid grid-cols-3 sm:grid-cols-4 gap-2">
                <button
                  v-for="s in quickAvailableSlots"
                  :key="s.startAt"
                  type="button"
                  :class="[
                    'px-2.5 py-2 rounded-lg border text-xs font-semibold transition-all text-center',
                    quickForm.selectedStartAts.includes(s.startAt)
                      ? 'bg-emerald-600 border-emerald-600 text-white shadow-xs'
                      : 'bg-white border-slate-200 text-slate-700 hover:border-emerald-400',
                  ]"
                  @click="toggleQuickSlot(s.startAt)"
                >
                  <div>{{ formatTaipeiHour(s.startAt) }}</div>
                  <div
                    :class="[
                      'text-2xs',
                      quickForm.selectedStartAts.includes(s.startAt)
                        ? 'text-emerald-100'
                        : 'text-slate-400',
                    ]"
                  >
                    ${{ s.price }}
                  </div>
                </button>
              </div>
            </div>

            <div
              class="bg-emerald-50 border border-emerald-200 rounded-lg p-3 flex items-center justify-between text-xs"
            >
              <span class="text-emerald-800">
                預計總金額：<strong class="text-base text-emerald-700">
                  ${{ quickTotalAmount }}
                </strong>
                元
              </span>
              <span class="text-emerald-700 font-medium">
                共 {{ quickForm.selectedStartAts.length }} 小時
              </span>
            </div>

            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >預約來源</label
                >
                <select
                  v-model="quickForm.source"
                  class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm bg-white"
                >
                  <option value="WALK_IN">現場臨櫃 (WALK_IN)</option>
                  <option value="PHONE">電話代訂 (PHONE)</option>
                </select>
              </div>
              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >收費方式</label
                >
                <select
                  v-model="quickForm.paymentMethod"
                  class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm bg-white"
                >
                  <option value="CASH">現場現金 (CASH)</option>
                  <option value="ONSITE_CARD">現場刷卡 (ONSITE_CARD)</option>
                </select>
              </div>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >預約人姓名 / 稱謂 <span class="text-rose-500">*</span></label
                >
                <input
                  v-model="quickForm.name"
                  type="text"
                  placeholder="例如：郭先生"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
                />
              </div>
              <div>
                <label class="text-xs font-semibold text-slate-700 block mb-1"
                  >聯絡電話 <span class="text-rose-500">*</span></label
                >
                <input
                  v-model="quickForm.phone"
                  type="tel"
                  placeholder="例如：0988-123-456"
                  class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
                />
              </div>
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >備註說明 (選填)</label
              >
              <input
                v-model="quickForm.notes"
                type="text"
                placeholder="例如：需要租借球拍 2 支"
                class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
              />
            </div>

            <div class="pt-3 border-t border-slate-100 flex gap-2">
              <v-btn
                variant="outlined"
                color="grey"
                rounded="lg"
                class="!px-4 font-medium"
                @click="quickBookingDialog = false"
              >
                取消
              </v-btn>
              <v-btn
                color="primary"
                variant="flat"
                rounded="lg"
                class="flex-1 !px-4 font-semibold"
                prepend-icon="mdi-check-circle"
                :loading="quickSubmitting"
                @click="submitQuickBooking"
              >
                確認建立代訂
              </v-btn>
            </div>
          </v-card-text>
        </v-card>
      </v-dialog>

      <!-- ============================================== -->
      <!-- 彈窗 E：場館營運設定（場地 / 費率 / 營業時間 / 例外日） -->
      <!-- ============================================== -->
      <AdminSettingsDialog
        v-if="isAdmin"
        v-model="settingsDialogOpen"
        :today-date="todayDate"
        @updated="handleSettingsUpdated"
        @toast="handleChildToast"
      />
    </template>

    <!-- 操作提示 Toast -->
    <teleport to="body">
      <transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="transform -translate-y-4 opacity-0 scale-95"
        enter-to-class="transform translate-y-0 opacity-100 scale-100"
        leave-active-class="transition duration-200 ease-in"
        leave-from-class="transform translate-y-0 opacity-100 scale-100"
        leave-to-class="transform -translate-y-4 opacity-0 scale-95"
      >
        <div
          v-if="snackbar.show"
          class="fixed top-6 right-6 z-[99999] flex items-center gap-3.5 px-5 py-3.5 rounded-xl shadow-2xl text-white font-medium text-sm pointer-events-auto border max-w-md"
          :class="[
            snackbar.color === 'success'
              ? 'bg-emerald-600 border-emerald-400/50 shadow-emerald-950/25'
              : snackbar.color === 'warning'
                ? 'bg-amber-600 border-amber-400/50 shadow-amber-950/25'
                : snackbar.color === 'error'
                  ? 'bg-rose-600 border-rose-400/50 shadow-rose-950/25'
                  : 'bg-slate-800 border-slate-600 shadow-slate-950/25',
          ]"
        >
          <div
            class="w-6 h-6 rounded-full bg-white/20 flex items-center justify-center shrink-0"
          >
            <v-icon
              :icon="
                snackbar.color === 'success'
                  ? 'mdi-check'
                  : snackbar.color === 'warning'
                    ? 'mdi-alert'
                    : snackbar.color === 'error'
                      ? 'mdi-close-circle'
                      : 'mdi-information'
              "
              size="16"
              color="white"
            ></v-icon>
          </div>

          <span class="font-bold tracking-wide text-xs sm:text-sm">
            {{ snackbar.text }}
          </span>

          <button
            type="button"
            class="ml-2 w-5 h-5 rounded-full flex items-center justify-center text-white/70 hover:text-white hover:bg-white/20 transition-colors shrink-0"
            title="關閉提示"
            @click="snackbar.show = false"
          >
            <v-icon icon="mdi-close" size="14"></v-icon>
          </button>
        </div>
      </transition>
    </teleport>
  </div>
</template>
