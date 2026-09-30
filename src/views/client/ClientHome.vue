<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { cartStore, addToCart, removeFromCart } from '../../stores/cartStore.js'

const router = useRouter()

// ==============================
// 1. 日期管理
// ==============================
const selectedDate = ref('2026-09-29')

const getWeekdayInfo = (dateStr) => {
  if (!dateStr) return { label: '', isWeekend: false }
  const [year, month, day] = dateStr.split('-').map(Number)
  const d = new Date(year, month - 1, day)
  const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  const isWeekend = d.getDay() === 0 || d.getDay() === 6
  return { label: `${days[d.getDay()]} (${isWeekend ? '週末假日' : '平日'})`, isWeekend }
}

const shiftDate = (delta) => {
  const [y, m, d] = selectedDate.value.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  date.setDate(date.getDate() + delta)
  const yy = date.getFullYear()
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  selectedDate.value = `${yy}-${mm}-${dd}`
}

const quickDates = [
  { value: '2026-09-29', label: '今天 (9/29)' },
  { value: '2026-09-30', label: '明天 (9/30)' },
  { value: '2026-10-03', label: '週六 (10/03)' },
]

// ==============================
// 2. 場地主檔與費率 (與後台共用相同結構)
// ==============================
const peakHourStart = 18

const courts = ref([
  { id: 'c1', name: '第 1 場地', type: 'VIP 木地板', peakPrice: 500, offPeakPrice: 350, enabled: true },
  { id: 'c2', name: '第 2 場地', type: 'VIP 木地板', peakPrice: 500, offPeakPrice: 350, enabled: true },
  { id: 'c3', name: '第 3 場地', type: '標準 PU',    peakPrice: 450, offPeakPrice: 300, enabled: true },
  { id: 'c4', name: '第 4 場地', type: '標準 PU',    peakPrice: 450, offPeakPrice: 300, enabled: true },
  { id: 'c5', name: '第 5 場地', type: '標準 PU',    peakPrice: 450, offPeakPrice: 300, enabled: true },
  { id: 'c6', name: '第 6 場地 (教學)', type: '訓練用 PU', peakPrice: 400, offPeakPrice: 280, enabled: true },
])

const activeCourts = computed(() => courts.value.filter((c) => c.enabled))

const timeSlots = [
  '08:00', '09:00', '10:00', '11:00', '12:00', '13:00',
  '14:00', '15:00', '16:00', '17:00', '18:00', '19:00', '20:00', '21:00',
]

const isPeakSlot = (slot) => {
  const hour = parseInt(slot.split(':')[0], 10)
  return getWeekdayInfo(selectedDate.value).isWeekend || hour >= peakHourStart
}

const getSlotPrice = (court, slot) =>
  isPeakSlot(slot) ? court.peakPrice : court.offPeakPrice

// ==============================
// 3. 場地排程假資料 (與後台相同結構，未來替換為 API)
// ==============================
const reservationsByDate = ref({
  '2026-09-22': {
    'c1_08:00': { status: 'booked' }, 'c1_09:00': { status: 'booked' },
    'c1_19:00': { status: 'booked' }, 'c1_20:00': { status: 'booked' },
    'c2_10:00': { status: 'holding' }, 'c2_19:00': { status: 'booked' },
    'c3_14:00': { status: 'maintenance' }, 'c3_15:00': { status: 'maintenance' },
    'c4_18:00': { status: 'booked' }, 'c5_20:00': { status: 'booked' },
  },
  '2026-09-23': {
    'c1_18:00': { status: 'booked' }, 'c1_19:00': { status: 'booked' },
    'c2_09:00': { status: 'booked' }, 'c2_10:00': { status: 'booked' },
    'c3_19:00': { status: 'holding' }, 'c3_20:00': { status: 'booked' },
    'c6_10:00': { status: 'booked' }, 'c6_11:00': { status: 'booked' },
  },
  '2026-09-29': {
    'c1_18:00': { status: 'booked' }, 'c1_19:00': { status: 'booked' },
    'c2_20:00': { status: 'booked' }, 'c3_09:00': { status: 'holding' },
    'c4_18:00': { status: 'booked' }, 'c5_10:00': { status: 'maintenance' },
    'c5_11:00': { status: 'maintenance' },
  },
  '2026-09-30': {
    'c1_19:00': { status: 'booked' }, 'c2_09:00': { status: 'booked' },
    'c3_20:00': { status: 'booked' }, 'c6_14:00': { status: 'booked' },
  },
  '2026-10-03': {
    'c1_09:00': { status: 'booked' }, 'c1_10:00': { status: 'booked' },
    'c1_14:00': { status: 'booked' }, 'c1_15:00': { status: 'booked' },
    'c2_09:00': { status: 'booked' }, 'c2_10:00': { status: 'booked' },
    'c2_14:00': { status: 'booked' }, 'c3_10:00': { status: 'booked' },
    'c3_15:00': { status: 'holding' }, 'c4_14:00': { status: 'booked' },
    'c5_19:00': { status: 'booked' }, 'c6_14:00': { status: 'booked' },
    'c6_15:00': { status: 'booked' },
  },
})

const currentDateSchedule = computed(() =>
  reservationsByDate.value[selectedDate.value] || {}
)

const getSlotStatus = (courtId, slot) =>
  currentDateSchedule.value[`${courtId}_${slot}`]?.status || 'available'

// ==============================
// 4. 購物車邏輯
// ==============================
const isSelected = (courtId, slot) =>
  cartStore.items.some(
    (i) => i.courtId === courtId && i.slot === slot && i.date === selectedDate.value
  )

const toggleSlot = (court, slot) => {
  if (getSlotStatus(court.id, slot) !== 'available') return
  if (isSelected(court.id, slot)) {
    removeFromCart(court.id, slot, selectedDate.value)
  } else {
    addToCart({
      courtId: court.id,
      courtName: court.name,
      courtType: court.type,
      slot,
      date: selectedDate.value,
      price: getSlotPrice(court, slot),
      isPeak: isPeakSlot(slot),
    })
  }
}

const cartTotal = computed(() => cartStore.items.reduce((sum, i) => sum + i.price, 0))

const currentDateCartCount = computed(() =>
  cartStore.items.filter((i) => i.date === selectedDate.value).length
)
</script>

<template>
  <div class="max-w-7xl mx-auto px-6 py-6 w-full space-y-5 pb-28">

    <!-- 頂部：日期選擇 -->
    <div class="bg-white rounded-xl border border-slate-200 p-5 shadow-xs flex flex-col lg:flex-row lg:items-center justify-between gap-5">
      <div>
        <span class="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1.5">
          選擇預約日期
        </span>
        <div class="flex items-center gap-2 flex-wrap">
          <button type="button"
                  class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 transition-colors shadow-2xs shrink-0"
                  @click="shiftDate(-1)">
            <v-icon icon="mdi-chevron-left" size="18"></v-icon>
          </button>
          <input type="date" v-model="selectedDate"
                 class="border border-slate-200 rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white focus:outline-emerald-500 w-36 shrink-0" />
          <button type="button"
                  class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 transition-colors shadow-2xs shrink-0"
                  @click="shiftDate(1)">
            <v-icon icon="mdi-chevron-right" size="18"></v-icon>
          </button>
          <v-chip size="small" variant="flat"
                  :color="getWeekdayInfo(selectedDate).isWeekend ? 'amber-100' : 'slate-100'"
                  :class="['!px-3 font-semibold whitespace-nowrap shrink-0',
                           getWeekdayInfo(selectedDate).isWeekend ? 'text-amber-800' : 'text-slate-600']">
            <v-icon :icon="getWeekdayInfo(selectedDate).isWeekend ? 'mdi-star' : 'mdi-calendar-today'"
                    size="14" class="mr-1"></v-icon>
            {{ getWeekdayInfo(selectedDate).label }}
          </v-chip>
          <!-- 快捷日期 -->
          <div class="hidden sm:flex items-center gap-1.5 shrink-0 pl-1">
            <button v-for="q in quickDates" :key="q.value" type="button"
                    :class="['text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                             selectedDate === q.value
                               ? 'bg-emerald-50 border-emerald-300 text-emerald-700 font-bold'
                               : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50']"
                    @click="selectedDate = q.value">
              {{ q.label }}
            </button>
          </div>
        </div>
      </div>

      <!-- 週末提示 -->
      <v-chip v-if="getWeekdayInfo(selectedDate).isWeekend"
              color="amber" variant="tonal" size="small"
              prepend-icon="mdi-lightning-bolt" class="!px-3 font-bold shrink-0">
        週末假日費率全天適用
      </v-chip>
    </div>

    <!-- 圖例說明 -->
    <div class="flex items-center gap-2 flex-wrap">
      <span class="text-xs text-slate-400 font-medium mr-1 shrink-0">狀態說明：</span>
      <v-chip size="small" variant="flat"
              class="!px-3 font-medium whitespace-nowrap border border-emerald-200 text-emerald-700 bg-emerald-50">
        <span class="w-2 h-2 rounded-full bg-emerald-500 mr-1.5 shrink-0"></span>可預約 (點選加入)
      </v-chip>
      <v-chip size="small" variant="flat"
              class="!px-3 font-medium whitespace-nowrap bg-emerald-500 text-white">
        <v-icon icon="mdi-check-circle" size="12" class="mr-1"></v-icon>已選取 (再點取消)
      </v-chip>
      <v-chip size="small" variant="flat"
              class="!px-3 font-medium whitespace-nowrap border border-slate-200 text-slate-400 bg-slate-100">
        <span class="w-2 h-2 rounded-full bg-slate-400 mr-1.5 shrink-0"></span>已預約
      </v-chip>
      <v-chip size="small" variant="flat"
              class="!px-3 font-medium whitespace-nowrap border border-amber-200 text-amber-700 bg-amber-50">
        <span class="w-2 h-2 rounded-full bg-amber-400 mr-1.5 shrink-0"></span>鎖定中
      </v-chip>
      <v-chip size="small" variant="flat"
              class="!px-3 font-medium whitespace-nowrap border border-rose-200 text-rose-700 bg-rose-50">
        <span class="w-2 h-2 rounded-full bg-rose-500 mr-1.5 shrink-0"></span>維護封場
      </v-chip>
    </div>

    <!-- 場地時段棋盤 -->
    <div class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">
      <div class="p-5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 class="text-base font-bold text-slate-800 flex items-center gap-2">
            <v-icon icon="mdi-table-clock" color="primary" size="20"></v-icon>
            場地即時空位查詢 — {{ selectedDate }}
          </h2>
          <p class="text-xs text-slate-400 mt-0.5">
            點選綠色格子加入預約清單，可同時選取多個時段與場地
          </p>
        </div>
        <div v-if="currentDateCartCount > 0" class="shrink-0">
          <v-chip color="primary" variant="flat" size="small" class="!px-3 font-bold">
            <v-icon icon="mdi-check-circle" size="14" class="mr-1"></v-icon>
            已選 {{ currentDateCartCount }} 個時段
          </v-chip>
        </div>
      </div>

      <div class="overflow-x-auto">
        <table class="w-full border-collapse text-left text-xs">
          <thead>
            <tr class="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold">
              <th class="p-3 w-36 min-w-[140px] sticky left-0 bg-slate-50 z-10 border-r border-slate-200 whitespace-nowrap">
                場地資訊
              </th>
              <th v-for="slot in timeSlots" :key="slot"
                  class="p-2.5 text-center min-w-[110px] border-r border-slate-100 font-medium whitespace-nowrap">
                <div class="font-bold text-slate-700">{{ slot }}</div>
                <div :class="['text-2xs font-semibold mt-0.5',
                              isPeakSlot(slot) ? 'text-amber-500' : 'text-slate-400']">
                  {{ isPeakSlot(slot) ? '⚡ 尖峰' : '離峰' }}
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr v-for="court in activeCourts" :key="court.id"
                class="hover:bg-slate-50/30 transition-colors">
              <!-- 場地名稱 (sticky) -->
              <td class="p-3 sticky left-0 bg-white z-10 border-r border-slate-200 whitespace-nowrap">
                <div class="font-bold text-slate-800">{{ court.name }}</div>
                <div class="text-2xs text-slate-400 mt-0.5">{{ court.type }}</div>
              </td>

              <!-- 時段格子 -->
              <td v-for="slot in timeSlots" :key="slot"
                  class="p-1 border-r border-slate-100 text-center align-middle">

                <!-- 已選取 -->
                <button v-if="isSelected(court.id, slot)"
                        type="button"
                        class="w-full h-15 rounded-lg border-2 border-emerald-500 bg-emerald-500 flex flex-col items-center justify-center gap-0.5 transition-all shadow-sm select-none hover:bg-emerald-600"
                        @click="toggleSlot(court, slot)">
                  <v-icon icon="mdi-check-circle" size="16" color="white"></v-icon>
                  <span class="text-xs font-bold text-white">${{ getSlotPrice(court, slot) }}</span>
                </button>

                <!-- 已預約 (不可點) -->
                <div v-else-if="getSlotStatus(court.id, slot) === 'booked'"
                     class="w-full h-15 rounded-lg bg-slate-100 border border-slate-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-lock-outline" size="14" color="grey"></v-icon>
                  <span class="text-2xs text-slate-400 font-medium">已預約</span>
                </div>

                <!-- 鎖定中 (不可點) -->
                <div v-else-if="getSlotStatus(court.id, slot) === 'holding'"
                     class="w-full h-15 rounded-lg bg-amber-50 border border-amber-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-timer-sand" size="14" color="amber-darken-2"></v-icon>
                  <span class="text-2xs text-amber-600 font-medium">鎖定中</span>
                </div>

                <!-- 維護封場 (不可點) -->
                <div v-else-if="getSlotStatus(court.id, slot) === 'maintenance'"
                     class="w-full h-15 rounded-lg bg-rose-50 border border-rose-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-wrench" size="14" color="rose-darken-2"></v-icon>
                  <span class="text-2xs text-rose-500 font-medium">維護封場</span>
                </div>

                <!-- 可預約 (空場) -->
                <button v-else
                        type="button"
                        class="w-full h-15 rounded-lg border border-dashed border-emerald-200 hover:border-emerald-500 hover:bg-emerald-50 flex flex-col items-center justify-center gap-0.5 transition-all group select-none"
                        @click="toggleSlot(court, slot)">
                  <span class="text-xs font-bold text-emerald-500 group-hover:text-emerald-700">
                    ${{ getSlotPrice(court, slot) }}
                  </span>
                  <span class="text-2xs text-slate-300 group-hover:text-emerald-600 font-medium whitespace-nowrap">
                    點選預約
                  </span>
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 底部浮動購物車列 -->
    <teleport to="body">
      <transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="transform translate-y-full opacity-0"
        enter-to-class="transform translate-y-0 opacity-100"
        leave-active-class="transition duration-200 ease-in"
        leave-from-class="transform translate-y-0 opacity-100"
        leave-to-class="transform translate-y-full opacity-0">
        <div v-if="cartStore.items.length > 0"
             class="fixed bottom-0 left-0 right-0 bg-white border-t-2 border-emerald-400 shadow-2xl z-50">
          <div class="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between gap-4 flex-wrap">
            <!-- 左側：已選資訊 -->
            <div class="flex items-center gap-4 flex-wrap min-w-0">
              <div class="flex items-center gap-2 shrink-0">
                <div class="w-9 h-9 rounded-full bg-emerald-500 text-white flex items-center justify-center font-bold text-sm shadow-sm">
                  {{ cartStore.items.length }}
                </div>
                <div>
                  <p class="text-sm font-bold text-slate-800 leading-tight">個時段已加入</p>
                  <p class="text-xs text-slate-400 leading-tight">
                    合計 <span class="font-bold text-emerald-600 text-base">${{ cartTotal }}</span> 元
                  </p>
                </div>
              </div>
              <div class="h-8 w-px bg-slate-200 hidden sm:block shrink-0"></div>
              <!-- 已選清單 chip 預覽 (最多顯示 3 個) -->
              <div class="hidden sm:flex items-center gap-1.5 flex-wrap min-w-0">
                <v-chip v-for="item in cartStore.items.slice(0, 3)"
                        :key="`${item.courtId}_${item.slot}_${item.date}`"
                        size="x-small" color="emerald" variant="tonal"
                        class="!px-2 font-medium whitespace-nowrap">
                  {{ item.courtName }} {{ item.slot }}
                  <button class="ml-1 opacity-60 hover:opacity-100 transition-opacity font-bold"
                          @click.stop="removeFromCart(item.courtId, item.slot, item.date)">×</button>
                </v-chip>
                <span v-if="cartStore.items.length > 3"
                      class="text-xs text-slate-400 whitespace-nowrap">
                  ...還有 {{ cartStore.items.length - 3 }} 項
                </span>
              </div>
            </div>
            <!-- 右側：操作按鈕 -->
            <div class="flex items-center gap-2 shrink-0">
              <v-btn variant="text" color="grey-darken-1" size="small"
                     class="font-medium"
                     @click="cartStore.items.splice(0)">
                清除全部
              </v-btn>
              <v-btn color="primary" variant="flat" rounded="lg"
                     prepend-icon="mdi-check-circle" class="!px-6 font-bold shadow-sm"
                     @click="router.push('/booking')">
                確認預約 →
              </v-btn>
            </div>
          </div>
        </div>
      </transition>
    </teleport>
  </div>
</template>
