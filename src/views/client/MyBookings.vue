<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const today = '2026-09-29'

// ==============================
// 假資料：歷史訂單 (未來替換為 API /my-bookings)
// ==============================
const bookings = ref([
  {
    id: 'BK20260922',
    date: '2026-09-22',
    courtName: '第 1 場地',
    courtType: 'VIP 木地板',
    slot: '08:00',
    price: 350,
    isPeak: false,
    status: 'confirmed',
    checkedIn: true,
    payment: 'online_paid',
    createdAt: '2026-09-20 14:32',
  },
  {
    id: 'BK20260922B',
    date: '2026-09-22',
    courtName: '第 1 場地',
    courtType: 'VIP 木地板',
    slot: '09:00',
    price: 350,
    isPeak: false,
    status: 'confirmed',
    checkedIn: true,
    payment: 'online_paid',
    createdAt: '2026-09-20 14:32',
  },
  {
    id: 'BK20261001',
    date: '2026-10-01',
    courtName: '第 3 場地',
    courtType: '標準 PU',
    slot: '19:00',
    price: 450,
    isPeak: true,
    status: 'confirmed',
    checkedIn: false,
    payment: 'online_paid',
    createdAt: '2026-09-28 10:15',
  },
  {
    id: 'BK20261005',
    date: '2026-10-05',
    courtName: '第 2 場地',
    courtType: 'VIP 木地板',
    slot: '20:00',
    price: 500,
    isPeak: true,
    status: 'pending',
    checkedIn: false,
    payment: 'pending',
    createdAt: '2026-09-29 08:45',
  },
  {
    id: 'BK20260918',
    date: '2026-09-18',
    courtName: '第 4 場地',
    courtType: '標準 PU',
    slot: '17:00',
    price: 300,
    isPeak: false,
    status: 'cancelled',
    checkedIn: false,
    payment: 'refunded',
    createdAt: '2026-09-15 20:11',
  },
])

// ==============================
// 狀態設定
// ==============================
const statusConfig = {
  confirmed: { color: 'success', label: '預約確認', icon: 'mdi-check-circle' },
  pending:   { color: 'warning', label: '待付款',   icon: 'mdi-clock-outline' },
  cancelled: { color: 'error',   label: '已取消',   icon: 'mdi-close-circle' },
}

const paymentLabel = {
  online_paid: '線上刷卡 (已付)',
  onsite_cash: '現場現金',
  onsite_card: '現場刷卡',
  pending:     '尚未付款',
  refunded:    '已退款',
}

const endSlot = (slot) => `${String(parseInt(slot) + 1).padStart(2, '0')}:00`

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
  return bookings.value
    .filter((b) => {
      if (activeTab.value === 'upcoming') return b.date >= today && b.status !== 'cancelled'
      if (activeTab.value === 'past') return b.date < today || b.status === 'cancelled'
      return true
    })
    .sort((a, b) => a.date.localeCompare(b.date))
})

// ==============================
// 取消預約彈窗 + 退款計算
// ==============================
const cancelDialog = ref(false)
const cancelTarget = ref(null)

const openCancelDialog = (booking) => {
  cancelTarget.value = booking
  cancelDialog.value = true
}

const getRefundInfo = (booking) => {
  const todayDate = new Date(today)
  const bookDate = new Date(booking.date)
  const diffDays = Math.ceil((bookDate - todayDate) / (1000 * 60 * 60 * 24))
  if (diffDays >= 3) {
    return { rate: 100, amount: booking.price, label: '預約日前 3 天以上', color: 'success' }
  }
  if (diffDays >= 1) {
    return { rate: 50, amount: Math.floor(booking.price * 0.5), label: '預約日前 1 ~ 2 天', color: 'warning' }
  }
  return { rate: 0, amount: 0, label: '預約當天', color: 'error' }
}

const confirmCancel = () => {
  if (cancelTarget.value) {
    cancelTarget.value.status = 'cancelled'
    cancelTarget.value.payment = 'refunded'
  }
  cancelDialog.value = false
  cancelTarget.value = null
}

// 是否可取消 (未來的已確認預約)
const canCancel = (b) => b.status === 'confirmed' && b.date >= today
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

    <!-- 空狀態 -->
    <div v-if="filteredBookings.length === 0"
         class="bg-white rounded-2xl border border-dashed border-slate-300 p-12 text-center">
      <v-icon icon="mdi-calendar-blank-outline" size="44" color="grey-lighten-1"></v-icon>
      <p class="text-slate-400 text-sm mt-3 mb-4">目前沒有預約紀錄</p>
      <v-btn color="primary" variant="tonal" rounded="lg" size="small"
             prepend-icon="mdi-plus" @click="router.push('/')">
        立即預約場地
      </v-btn>
    </div>

    <!-- 預約卡片列表 -->
    <div class="space-y-3">
      <div v-for="booking in filteredBookings" :key="booking.id"
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
            <!-- 場地圖示 -->
            <div :class="['w-12 h-12 rounded-xl flex items-center justify-center shrink-0',
                          booking.status === 'cancelled' ? 'bg-slate-100 text-slate-400' : 'bg-emerald-100 text-emerald-600']">
              <v-icon icon="mdi-badminton" size="26"></v-icon>
            </div>

            <!-- 預約詳情 -->
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
                  {{ booking.slot }} ~ {{ endSlot(booking.slot) }}
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
                  {{ paymentLabel[booking.payment] }}
                </v-chip>
                <v-chip v-if="booking.checkedIn" color="success" size="x-small"
                        variant="tonal" class="!px-1.5 font-medium">
                  <v-icon icon="mdi-check" size="11" class="mr-0.5"></v-icon>已報到
                </v-chip>
              </div>
            </div>
          </div>

          <!-- 取消按鈕 -->
          <div v-if="canCancel(booking)" class="shrink-0">
            <v-btn color="error" variant="outlined" size="small" rounded="lg"
                   prepend-icon="mdi-cancel" class="!px-3 font-semibold"
                   @click="openCancelDialog(booking)">
              取消預約
            </v-btn>
          </div>
        </div>
      </div>
    </div>

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
            <p v-if="getRefundInfo(cancelTarget).rate === 0"
               class="text-xs text-rose-500 mt-1.5">
              當天取消依場館規定不予退款
            </p>
          </div>

          <!-- 操作按鈕 -->
          <div class="flex gap-2 pt-1">
            <v-btn variant="outlined" color="grey" rounded="lg" class="flex-1 font-medium"
                   @click="cancelDialog = false">
              保留預約
            </v-btn>
            <v-btn color="error" variant="flat" rounded="lg" class="flex-1 font-semibold"
                   prepend-icon="mdi-check" @click="confirmCancel">
              確認取消
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

  </div>
</template>
