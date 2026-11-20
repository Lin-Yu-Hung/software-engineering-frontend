<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getMyOrders, cancelMyOrder, getCourts, getOrder } from '../../api/booking.js'
import { isAuthenticated, userRoles, login } from '../../stores/authStore.js'
import { getTodayTaipei, formatTaipeiDate, formatTaipeiHour, formatTaipeiDateTime } from '../../utils/taipeiTime.js'

const router = useRouter()
const route = useRoute()

const today = getTodayTaipei()

// ==============================
// ECPay 付款回傳處理
// ==============================
const paymentResult = ref(null) // 'success' | 'failed' | 'unknown' | null
const paymentOrderId = ref(null)
const paymentOrderStatus = ref(null) // 從 API 查到的真實狀態

const handleEcpayReturn = async () => {
  const { orderId, payment } = route.query
  if (!orderId || !payment) return

  paymentOrderId.value = orderId
  paymentResult.value = payment

  // 清除網址中的查詢參數，避免重新整理時重複觸發
  router.replace({ path: '/my-bookings' })

  // 查詢訂單真實狀態，若還是 PENDING_PAYMENT 則最多重試 3 次（每次間隔 2 秒）
  let attempts = 0
  const maxAttempts = 3
  const poll = async () => {
    try {
      const order = await getOrder(orderId)
      paymentOrderStatus.value = order.status
      if (order.status === 'PENDING_PAYMENT' && attempts < maxAttempts) {
        attempts++
        setTimeout(poll, 2000)
      }
    } catch {
      // 查不到就沿用 URL 傳來的 payment 結果顯示
    }
  }
  await poll()
}

// ==============================
// 未登入狀態
// ==============================
const isMember = computed(() =>
  isAuthenticated.value && userRoles.value.includes('MEMBER'),
)

const loginForm = ref({ email: '', password: '' })
const loginError = ref('')
const loginLoading = ref(false)

const handleLogin = async () => {
  if (!loginForm.value.email || !loginForm.value.password) {
    loginError.value = '請輸入帳號與密碼'
    return
  }
  loginLoading.value = true
  loginError.value = ''
  try {
    await login(loginForm.value.email, loginForm.value.password)
    await loadData()
  } catch (err) {
    loginError.value = err?.message || '帳號或密碼錯誤'
  } finally {
    loginLoading.value = false
  }
}

// ==============================
// 資料載入
// ==============================
const ordersData = ref([])
const courtMap = ref({})
const loading = ref(false)
const error = ref(null)

const loadData = async () => {
  if (!isMember.value) return
  loading.value = true
  error.value = null
  try {
    const [ordersRes, courtsRes] = await Promise.all([
      getMyOrders({ size: 50 }),
      getCourts(),
    ])
    ordersData.value = ordersRes.content || []
    courtMap.value = courtsRes.reduce((m, c) => { m[c.id] = c; return m }, {})
  } catch (err) {
    error.value = err?.message || '訂單載入失敗，請重新整理'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await handleEcpayReturn()
  await loadData()
})

// ==============================
// 資料轉換（order + items → 顯示用 flat 清單）
// ==============================
const STATUS_MAP = {
  CONFIRMED: 'confirmed',
  PENDING_PAYMENT: 'pending',
  CANCELLED: 'cancelled',
  EXPIRED: 'cancelled',
}

const getPaymentLabel = (order) => {
  if (order.paymentStatus === 'PAID') {
    if (order.paymentMethod === 'CREDIT_CARD') return '線上刷卡 (已付)'
    if (order.paymentMethod === 'ONSITE_CARD') return '現場刷卡 (已付)'
    return '現金 (已付)'
  }
  if (order.paymentMethod === 'CASH') return '現場現金'
  if (order.paymentMethod === 'ONSITE_CARD') return '現場刷卡'
  if (order.status === 'CANCELLED' || order.status === 'EXPIRED') return '已退款 / 無需付款'
  return '尚未付款'
}

const isSlotPeak = (startAtStr) => {
  const hour = parseInt(formatTaipeiHour(startAtStr).split(':')[0], 10)
  const dt = new Date(startAtStr)
  const dow = dt.toLocaleString('en-US', { weekday: 'short', timeZone: 'Asia/Taipei' })
  return hour >= 18 || dow === 'Sat' || dow === 'Sun'
}

const bookings = computed(() => {
  const result = []
  for (const order of ordersData.value) {
    for (const item of order.items || []) {
      const court = courtMap.value[item.courtId] || {}
      result.push({
        // 訂單層級
        orderId: order.id,
        id: order.orderNumber,
        status: STATUS_MAP[order.status] || 'cancelled',
        payment: getPaymentLabel(order),
        checkedIn: Boolean(order.checkedInAt),
        createdAt: formatTaipeiDateTime(order.createdAt),
        canCancel: (order.status === 'CONFIRMED' || order.status === 'PENDING_PAYMENT'),
        // 時段層級
        itemId: item.id,
        date: formatTaipeiDate(item.startAt),
        courtName: court.name || `場地 (${item.courtId.slice(0, 6)})`,
        courtType: court.type || '',
        slot: formatTaipeiHour(item.startAt),
        endSlotStr: formatTaipeiHour(item.endAt),
        price: item.unitPrice,
        isPeak: isSlotPeak(item.startAt),
      })
    }
  }
  return result.sort((a, b) => a.date.localeCompare(b.date))
})

// ==============================
// Tab 過濾
// ==============================
const activeTab = ref('upcoming')

const tabs = [
  { value: 'upcoming', label: '即將到來', icon: 'mdi-calendar-arrow-right' },
  { value: 'past',     label: '歷史紀錄', icon: 'mdi-history' },
  { value: 'all',      label: '全部',     icon: 'mdi-format-list-bulleted' },
]

const filteredBookings = computed(() => {
  return bookings.value.filter((b) => {
    if (activeTab.value === 'upcoming') return b.date >= today && b.status !== 'cancelled'
    if (activeTab.value === 'past') return b.date < today || b.status === 'cancelled'
    return true
  })
})

// ==============================
// 取消預約
// ==============================
const cancelDialog = ref(false)
const cancelTarget = ref(null)
const cancelReason = ref('')
const cancelling = ref(false)
const cancelError = ref('')

const statusConfig = {
  confirmed: { color: 'success', label: '預約確認',  icon: 'mdi-check-circle' },
  pending:   { color: 'warning', label: '待付款',    icon: 'mdi-clock-outline' },
  cancelled: { color: 'error',   label: '已取消/逾期', icon: 'mdi-close-circle' },
}

const openCancelDialog = (booking) => {
  cancelTarget.value = booking
  cancelReason.value = ''
  cancelError.value = ''
  cancelDialog.value = true
}

const getRefundInfo = (booking) => {
  const bookDate = new Date(booking.date)
  const todayDate = new Date(today)
  const diffDays = Math.ceil((bookDate - todayDate) / (1000 * 60 * 60 * 24))
  if (diffDays >= 3) return { rate: 100, amount: booking.price, label: '預約日前 3 天以上', color: 'success' }
  if (diffDays >= 1) return { rate: 50, amount: Math.floor(booking.price * 0.5), label: '預約日前 1 ~ 2 天', color: 'warning' }
  return { rate: 0, amount: 0, label: '預約當天', color: 'error' }
}

const confirmCancel = async () => {
  if (!cancelTarget.value) return
  cancelling.value = true
  cancelError.value = ''
  try {
    await cancelMyOrder(cancelTarget.value.orderId, cancelReason.value.trim())
    cancelDialog.value = false
    await loadData()
  } catch (err) {
    cancelError.value = err?.message || '取消失敗，請再試一次'
  } finally {
    cancelling.value = false
  }
}
</script>

<template>
  <div class="max-w-3xl mx-auto px-6 py-8 space-y-6">

    <!-- 頂部標題列 -->
    <div class="flex items-center justify-between gap-3 flex-wrap">
      <div class="flex items-center gap-3">
        <v-btn variant="text" color="grey-darken-1" icon="mdi-arrow-left" size="small"
               @click="router.push('/')"></v-btn>
        <div>
          <h1 class="text-xl font-bold text-slate-800 flex items-center gap-2">
            <v-icon icon="mdi-bookmark-multiple" color="primary" size="22"></v-icon>
            我的預約紀錄
          </h1>
          <p class="text-xs text-slate-400 mt-0.5">查看與管理您的所有羽球場地預約</p>
        </div>
      </div>
      <v-btn color="primary" variant="flat" rounded="lg" size="small"
             prepend-icon="mdi-plus" @click="router.push('/')">
        新增預約
      </v-btn>
    </div>

    <!-- ECPay 付款結果橫幅 -->
    <div v-if="paymentResult">
      <!-- 付款成功 -->
      <div v-if="paymentResult === 'success' && paymentOrderStatus !== 'PENDING_PAYMENT'"
           class="bg-emerald-50 border border-emerald-200 rounded-xl p-4 flex items-start gap-3">
        <v-icon icon="mdi-check-circle" color="success" size="22" class="mt-0.5 shrink-0"></v-icon>
        <div>
          <p class="text-sm font-bold text-emerald-700">付款成功！</p>
          <p class="text-xs text-emerald-600 mt-0.5">
            訂單已確認，訂單編號：<span class="font-mono font-semibold">{{ paymentOrderId }}</span>
          </p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="x-small" color="grey" class="ml-auto shrink-0"
               @click="paymentResult = null"></v-btn>
      </div>

      <!-- 付款成功但訂單還在 PENDING_PAYMENT（通知未到） -->
      <div v-else-if="paymentResult === 'success' && paymentOrderStatus === 'PENDING_PAYMENT'"
           class="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-start gap-3">
        <v-icon icon="mdi-clock-outline" color="warning" size="22" class="mt-0.5 shrink-0"></v-icon>
        <div>
          <p class="text-sm font-bold text-amber-700">付款已收到，訂單確認中...</p>
          <p class="text-xs text-amber-600 mt-0.5">系統正在確認付款結果，請稍候並重新整理頁面。</p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="x-small" color="grey" class="ml-auto shrink-0"
               @click="paymentResult = null"></v-btn>
      </div>

      <!-- 付款失敗 -->
      <div v-else-if="paymentResult === 'failed'"
           class="bg-rose-50 border border-rose-200 rounded-xl p-4 flex items-start gap-3">
        <v-icon icon="mdi-close-circle" color="error" size="22" class="mt-0.5 shrink-0"></v-icon>
        <div>
          <p class="text-sm font-bold text-rose-700">付款未成功</p>
          <p class="text-xs text-rose-600 mt-0.5">
            訂單 <span class="font-mono font-semibold">{{ paymentOrderId }}</span> 付款失敗，請重新嘗試或選擇其他付款方式。
          </p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="x-small" color="grey" class="ml-auto shrink-0"
               @click="paymentResult = null"></v-btn>
      </div>

      <!-- 付款結果不明 -->
      <div v-else-if="paymentResult === 'unknown'"
           class="bg-slate-50 border border-slate-200 rounded-xl p-4 flex items-start gap-3">
        <v-icon icon="mdi-help-circle" color="grey" size="22" class="mt-0.5 shrink-0"></v-icon>
        <div>
          <p class="text-sm font-bold text-slate-700">付款結果待確認</p>
          <p class="text-xs text-slate-500 mt-0.5">
            系統尚未收到付款通知，請稍候後重新整理，或聯絡客服確認訂單狀態。
          </p>
        </div>
        <v-btn icon="mdi-close" variant="text" size="x-small" color="grey" class="ml-auto shrink-0"
               @click="paymentResult = null"></v-btn>
      </div>
    </div>

    <!-- 未登入 -->
    <div v-if="!isMember" class="bg-white rounded-xl border border-amber-200 shadow-xs p-6 space-y-4">
      <h3 class="text-sm font-bold text-slate-700 flex items-center gap-2">
        <v-icon icon="mdi-login" size="18" color="primary"></v-icon>
        請先登入會員才能查看預約記錄
      </h3>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label class="text-xs font-semibold text-slate-600 block mb-1.5">電子郵件</label>
          <input v-model="loginForm.email" type="email" placeholder="example@mail.com"
                 class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white"
                 @keyup.enter="handleLogin" />
        </div>
        <div>
          <label class="text-xs font-semibold text-slate-600 block mb-1.5">密碼</label>
          <input v-model="loginForm.password" type="password" placeholder="••••••••"
                 class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white"
                 @keyup.enter="handleLogin" />
        </div>
      </div>
      <div v-if="loginError" class="text-xs text-rose-600 font-medium">{{ loginError }}</div>
      <v-btn color="primary" variant="flat" rounded="lg" :loading="loginLoading"
             class="font-semibold" @click="handleLogin">
        登入查看我的預約
      </v-btn>
    </div>

    <!-- 已登入：訂單列表 -->
    <template v-else>
      <!-- Tab 切換 -->
      <div class="flex border-b border-slate-200">
        <button v-for="tab in tabs" :key="tab.value" type="button"
                :class="['py-2.5 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-1.5 whitespace-nowrap',
                         activeTab === tab.value
                           ? 'border-emerald-500 text-emerald-600'
                           : 'border-transparent text-slate-500 hover:text-slate-800']"
                @click="activeTab = tab.value">
          <v-icon :icon="tab.icon" size="15"></v-icon>
          {{ tab.label }}
          <span v-if="tab.value === 'upcoming'"
                class="ml-0.5 bg-emerald-100 text-emerald-700 text-2xs font-bold px-1.5 py-0.5 rounded-full">
            {{ bookings.filter(b => b.date >= today && b.status !== 'cancelled').length }}
          </span>
        </button>
      </div>

      <!-- 載入中 -->
      <div v-if="loading" class="py-12 text-center text-sm text-slate-400">
        正在載入訂單資料...
      </div>

      <!-- 錯誤 -->
      <div v-else-if="error"
           class="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm text-rose-700 flex items-center gap-2">
        <v-icon icon="mdi-alert-circle" size="18"></v-icon>
        {{ error }}
        <v-btn size="x-small" variant="text" color="error" class="ml-auto" @click="loadData">重試</v-btn>
      </div>

      <!-- 空狀態 -->
      <div v-else-if="filteredBookings.length === 0"
           class="bg-white rounded-2xl border border-dashed border-slate-300 p-12 text-center">
        <v-icon icon="mdi-calendar-blank-outline" size="44" color="grey-lighten-1"></v-icon>
        <p class="text-slate-400 text-sm mt-3 mb-4">目前沒有預約紀錄</p>
        <v-btn color="primary" variant="tonal" rounded="lg" size="small"
               prepend-icon="mdi-plus" @click="router.push('/')">
          立即預約場地
        </v-btn>
      </div>

      <!-- 預約卡片列表 -->
      <div v-else class="space-y-3">
        <div v-for="booking in filteredBookings" :key="`${booking.orderId}_${booking.itemId}`"
             class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">

          <!-- 卡片頂部狀態列 -->
          <div :class="['px-5 py-2.5 flex items-center justify-between border-b',
                        booking.status === 'cancelled' ? 'bg-slate-50 border-slate-100' :
                        booking.status === 'pending'   ? 'bg-amber-50 border-amber-100' :
                                                         'bg-emerald-50 border-emerald-100']">
            <div class="flex items-center gap-2 text-xs text-slate-400">
              <v-icon icon="mdi-receipt-text" size="13"></v-icon>
              <span class="font-mono font-medium">{{ booking.id }}</span>
              <span class="text-slate-300">·</span>
              <span>{{ booking.createdAt }} 建立</span>
            </div>
            <v-chip :color="statusConfig[booking.status].color" variant="flat" size="x-small"
                    :prepend-icon="statusConfig[booking.status].icon" class="!px-2.5 font-bold">
              {{ statusConfig[booking.status].label }}
            </v-chip>
          </div>

          <!-- 卡片主體 -->
          <div class="p-5 flex items-center justify-between gap-4">
            <div class="flex items-center gap-4 min-w-0">
              <div :class="['w-12 h-12 rounded-xl flex items-center justify-center shrink-0',
                            booking.status === 'cancelled' ? 'bg-slate-100 text-slate-400' : 'bg-emerald-100 text-emerald-600']">
                <v-icon icon="mdi-badminton" size="26"></v-icon>
              </div>

              <div class="min-w-0">
                <p :class="['font-bold text-base truncate',
                            booking.status === 'cancelled' ? 'text-slate-400' : 'text-slate-800']">
                  {{ booking.courtName }}
                </p>
                <div class="flex items-center gap-2 mt-0.5 flex-wrap text-xs text-slate-500">
                  <span class="flex items-center gap-1 whitespace-nowrap">
                    <v-icon icon="mdi-calendar" size="12"></v-icon>
                    {{ booking.date }}
                  </span>
                  <span class="flex items-center gap-1 whitespace-nowrap">
                    <v-icon icon="mdi-clock-outline" size="12"></v-icon>
                    {{ booking.slot }} ~ {{ booking.endSlotStr }}
                  </span>
                  <span class="text-slate-400 whitespace-nowrap">{{ booking.courtType }}</span>
                </div>
                <div class="mt-1.5 flex items-center gap-2 flex-wrap">
                  <span class="text-sm font-bold text-emerald-600">${{ booking.price }}</span>
                  <v-chip :color="booking.isPeak ? 'amber' : 'slate'" size="x-small"
                          variant="tonal" class="!px-1.5 font-semibold">
                    {{ booking.isPeak ? '尖峰' : '離峰' }}
                  </v-chip>
                  <v-chip size="x-small" color="grey" variant="tonal" class="!px-1.5 font-medium">
                    {{ booking.payment }}
                  </v-chip>
                  <v-chip v-if="booking.checkedIn" color="success" size="x-small"
                          variant="tonal" class="!px-1.5 font-medium">
                    <v-icon icon="mdi-check" size="11" class="mr-0.5"></v-icon>已報到
                  </v-chip>
                </div>
              </div>
            </div>

            <!-- 取消按鈕 -->
            <div v-if="booking.canCancel && booking.date >= today" class="shrink-0">
              <v-btn color="error" variant="outlined" size="small" rounded="lg"
                     prepend-icon="mdi-cancel" class="!px-3 font-semibold"
                     @click="openCancelDialog(booking)">
                取消預約
              </v-btn>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 取消確認彈窗 -->
    <v-dialog v-model="cancelDialog" max-width="440">
      <v-card v-if="cancelTarget" rounded="xl" class="overflow-hidden">
        <div class="bg-rose-600 text-white p-5 flex items-center justify-between">
          <h3 class="text-base font-bold flex items-center gap-2">
            <v-icon icon="mdi-alert-circle" size="20"></v-icon>
            確認取消預約
          </h3>
          <v-btn icon="mdi-close" variant="text" size="small" color="white"
                 @click="cancelDialog = false"></v-btn>
        </div>

        <v-card-text class="p-6 space-y-4">
          <!-- 預約資訊摘要 -->
          <div class="bg-slate-50 rounded-xl p-4 border border-slate-200 text-sm space-y-2">
            <div class="flex justify-between">
              <span class="text-slate-500">場地</span>
              <span class="font-bold text-slate-800">{{ cancelTarget.courtName }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-slate-500">預約時段</span>
              <span class="font-medium text-slate-700">
                {{ cancelTarget.date }} {{ cancelTarget.slot }}
              </span>
            </div>
            <div class="flex justify-between">
              <span class="text-slate-500">已付金額</span>
              <span class="font-bold text-emerald-600">${{ cancelTarget.price }}</span>
            </div>
          </div>

          <!-- 退款計算 -->
          <div :class="['rounded-xl p-4 border text-sm',
                        getRefundInfo(cancelTarget).rate === 100 ? 'bg-emerald-50 border-emerald-200' :
                        getRefundInfo(cancelTarget).rate === 50  ? 'bg-amber-50 border-amber-200' :
                                                                   'bg-rose-50 border-rose-200']">
            <p class="font-bold text-slate-700 mb-2 flex items-center gap-1.5">
              <v-icon icon="mdi-calculator-variant" size="16"></v-icon>
              退款計算（{{ getRefundInfo(cancelTarget).label }}取消）
            </p>
            <div class="flex justify-between items-center">
              <span class="text-slate-500 text-sm">退款比例：{{ getRefundInfo(cancelTarget).rate }}%</span>
              <span :class="['font-bold text-xl',
                             getRefundInfo(cancelTarget).rate > 0 ? 'text-emerald-600' : 'text-rose-600']">
                退款 ${{ getRefundInfo(cancelTarget).amount }}
              </span>
            </div>
          </div>

          <!-- 取消原因（選填） -->
          <div>
            <label class="text-xs font-semibold text-slate-600 block mb-1.5">取消原因（選填）</label>
            <input v-model="cancelReason" type="text" placeholder="例如：臨時有事無法出席"
                   class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-rose-500 bg-white" />
          </div>

          <div v-if="cancelError" class="text-xs text-rose-600 font-medium">{{ cancelError }}</div>

          <!-- 操作按鈕 -->
          <div class="flex gap-2 pt-1">
            <v-btn variant="outlined" color="grey" rounded="lg" class="flex-1 font-medium"
                   @click="cancelDialog = false">
              保留預約
            </v-btn>
            <v-btn color="error" variant="flat" rounded="lg" class="flex-1 font-semibold"
                   prepend-icon="mdi-check" :loading="cancelling" @click="confirmCancel">
              確認取消
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

  </div>
</template>
