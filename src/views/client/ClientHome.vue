<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { cartStore, addToCart, removeFromCart } from '../../stores/cartStore.js'
import { getCourts, getAvailability } from '../../api/booking.js'
import { getTodayTaipei, shiftDateStr, formatTaipeiHour } from '../../utils/taipeiTime.js'

const router = useRouter()

// ==============================
// 1. 日期管理
// ==============================
const todayStr = getTodayTaipei()

const getNextSaturday = (fromDate) => {
  const [y, m, d] = fromDate.split('-').map(Number)
  const dt = new Date(Date.UTC(y, m - 1, d))
  const delta = (6 - dt.getUTCDay() + 7) % 7 || 7
  return shiftDateStr(fromDate, delta)
}

const fmt = (s) => {
  const [, mm, dd] = s.split('-')
  return `${parseInt(mm)}/${parseInt(dd)}`
}

const quickDates = [
  { value: todayStr,                      label: `今天 (${fmt(todayStr)})` },
  { value: shiftDateStr(todayStr, 1),     label: `明天 (${fmt(shiftDateStr(todayStr, 1))})` },
  { value: getNextSaturday(todayStr),     label: `週六 (${fmt(getNextSaturday(todayStr))})` },
]

const selectedDate = ref(todayStr)

const getWeekdayInfo = (dateStr) => {
  if (!dateStr) return { label: '', isWeekend: false }
  const [year, month, day] = dateStr.split('-').map(Number)
  const d = new Date(Date.UTC(year, month - 1, day))
  const days = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
  const isWeekend = d.getUTCDay() === 0 || d.getUTCDay() === 6
  return { label: `${days[d.getUTCDay()]} (${isWeekend ? '週末假日' : '平日'})`, isWeekend }
}

const shiftDate = (delta) => {
  selectedDate.value = shiftDateStr(selectedDate.value, delta)
}

// ==============================
// 2. API 資料載入
// ==============================
const courts = ref([])
const availabilityData = ref(null)
const loading = ref(false)
const error = ref(null)

const loadCourts = async () => {
  try {
    courts.value = await getCourts()
  } catch {
    // 非關鍵錯誤，availability API 也會回傳場地清單
  }
}

const loadAvailability = async () => {
  loading.value = true
  error.value = null
  try {
    availabilityData.value = await getAvailability(selectedDate.value)
  } catch (err) {
    error.value = err?.message || '時段資料載入失敗，請重新整理'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadCourts()
  await loadAvailability()
})

watch(selectedDate, loadAvailability)

// ==============================
// 3. 從 API 資料推導出場地與時段
// ==============================
const activeCourts = computed(() => {
  const avCourts = availabilityData.value?.courts
  if (avCourts && avCourts.length > 0) return avCourts.filter((c) => c.enabled !== false)
  return courts.value.filter((c) => c.enabled !== false)
})

// slotLookup: `${courtId}_${hour}` → slot 物件
const slotLookup = computed(() => {
  const map = new Map()
  for (const slot of availabilityData.value?.slots || []) {
    const hour = formatTaipeiHour(slot.startAt)
    if (hour) map.set(`${slot.courtId}_${hour}`, slot)
  }
  return map
})

// 當日時段欄位（依 API 回傳的 slots 動態推導）
const timeSlots = computed(() => {
  const hourMap = new Map()
  for (const slot of availabilityData.value?.slots || []) {
    const hour = formatTaipeiHour(slot.startAt)
    if (hour && !hourMap.has(hour)) hourMap.set(hour, slot.priceType)
  }
  return Array.from(hourMap.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([hour, priceType]) => ({ hour, priceType }))
})

const isWeekendOrHoliday = computed(() =>
  availabilityData.value?.dayType === 'WEEKEND' ||
  availabilityData.value?.dayType === 'HOLIDAY' ||
  getWeekdayInfo(selectedDate.value).isWeekend
)

// ==============================
// 4. 格子狀態與價格
// ==============================
const getSlotData = (courtId, hour) =>
  slotLookup.value.get(`${courtId}_${hour}`) || null

const getSlotStatus = (courtId, hour) => {
  const slot = getSlotData(courtId, hour)
  if (!slot) return 'closed'
  return slot.status.toLowerCase() // AVAILABLE → available
}

const getSlotPrice = (courtId, hour) =>
  getSlotData(courtId, hour)?.price || 0

// ==============================
// 5. 購物車邏輯
// ==============================
const isSelected = (courtId, hour) =>
  cartStore.items.some(
    (i) => i.courtId === courtId && i.slot === hour && i.date === selectedDate.value,
  )

const toggleSlot = (court, hour) => {
  const slotData = getSlotData(court.id, hour)
  if (!slotData || slotData.status !== 'AVAILABLE') return
  if (isSelected(court.id, hour)) {
    removeFromCart(court.id, hour, selectedDate.value)
  } else {
    addToCart({
      courtId: court.id,
      courtName: court.name,
      courtType: court.type,
      slot: hour,
      date: selectedDate.value,
      startAt: slotData.startAt,
      price: slotData.price,
      isPeak: slotData.priceType !== 'OFF_PEAK',
    })
  }
}

const cartTotal = computed(() => cartStore.items.reduce((sum, i) => sum + i.price, 0))

const currentDateCartCount = computed(() =>
  cartStore.items.filter((i) => i.date === selectedDate.value).length,
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
      <v-chip v-if="isWeekendOrHoliday"
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

    <!-- 錯誤提示 -->
    <div v-if="error" class="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm text-rose-700 flex items-center gap-2">
      <v-icon icon="mdi-alert-circle" size="18"></v-icon>
      {{ error }}
      <v-btn size="x-small" variant="text" color="error" class="ml-auto" @click="loadAvailability">重試</v-btn>
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

      <!-- 載入中 -->
      <div v-if="loading && !availabilityData" class="p-12 text-center text-sm text-slate-400">
        正在載入場地時段資料...
      </div>

      <!-- 無時段（當日休館） -->
      <div v-else-if="!loading && timeSlots.length === 0 && !error"
           class="p-12 text-center text-sm text-slate-400 space-y-1">
        <p class="font-semibold text-slate-600">{{ selectedDate }} 無開放營業時段</p>
        <p class="text-xs">請選擇其他日期，或確認場館是否當日休館</p>
      </div>

      <!-- 棋盤表格 -->
      <div v-else-if="timeSlots.length > 0" class="overflow-x-auto">
        <table class="w-full border-collapse text-left text-xs">
          <thead>
            <tr class="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold">
              <th class="p-3 w-36 min-w-[140px] sticky left-0 bg-slate-50 z-10 border-r border-slate-200 whitespace-nowrap">
                場地資訊
              </th>
              <th v-for="slotCol in timeSlots" :key="slotCol.hour"
                  class="p-2.5 text-center min-w-[110px] border-r border-slate-100 font-medium whitespace-nowrap">
                <div class="font-bold text-slate-700">{{ slotCol.hour }}</div>
                <div :class="['text-2xs font-semibold mt-0.5',
                              slotCol.priceType !== 'OFF_PEAK' ? 'text-amber-500' : 'text-slate-400']">
                  {{ slotCol.priceType !== 'OFF_PEAK' ? '⚡ 尖峰' : '離峰' }}
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
              <td v-for="slotCol in timeSlots" :key="slotCol.hour"
                  class="p-1 border-r border-slate-100 text-center align-middle">

                <!-- 已選取 -->
                <button v-if="isSelected(court.id, slotCol.hour)"
                        type="button"
                        class="w-full h-15 rounded-lg border-2 border-emerald-500 bg-emerald-500 flex flex-col items-center justify-center gap-0.5 transition-all shadow-sm select-none hover:bg-emerald-600"
                        @click="toggleSlot(court, slotCol.hour)">
                  <v-icon icon="mdi-check-circle" size="16" color="white"></v-icon>
                  <span class="text-xs font-bold text-white">${{ getSlotPrice(court.id, slotCol.hour) }}</span>
                </button>

                <!-- 已預約 -->
                <div v-else-if="getSlotStatus(court.id, slotCol.hour) === 'booked'"
                     class="w-full h-15 rounded-lg bg-slate-100 border border-slate-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-lock-outline" size="14" color="grey"></v-icon>
                  <span class="text-2xs text-slate-400 font-medium">已預約</span>
                </div>

                <!-- 鎖定中 -->
                <div v-else-if="getSlotStatus(court.id, slotCol.hour) === 'holding'"
                     class="w-full h-15 rounded-lg bg-amber-50 border border-amber-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-timer-sand" size="14" color="amber-darken-2"></v-icon>
                  <span class="text-2xs text-amber-600 font-medium">鎖定中</span>
                </div>

                <!-- 維護封場 -->
                <div v-else-if="getSlotStatus(court.id, slotCol.hour) === 'maintenance'"
                     class="w-full h-15 rounded-lg bg-rose-50 border border-rose-200 flex flex-col items-center justify-center gap-0.5 cursor-not-allowed">
                  <v-icon icon="mdi-wrench" size="14" color="rose-darken-2"></v-icon>
                  <span class="text-2xs text-rose-500 font-medium">維護封場</span>
                </div>

                <!-- 已過時段 / 休館 -->
                <div v-else-if="getSlotStatus(court.id, slotCol.hour) === 'past' || getSlotStatus(court.id, slotCol.hour) === 'closed'"
                     class="w-full h-15 rounded-lg bg-slate-50 border border-slate-100 flex items-center justify-center cursor-not-allowed">
                  <span class="text-2xs text-slate-300 font-medium">
                    {{ getSlotStatus(court.id, slotCol.hour) === 'past' ? '已過' : '休館' }}
                  </span>
                </div>

                <!-- 可預約 (空場) -->
                <button v-else
                        type="button"
                        class="w-full h-15 rounded-lg border border-dashed border-emerald-200 hover:border-emerald-500 hover:bg-emerald-50 flex flex-col items-center justify-center gap-0.5 transition-all group select-none"
                        @click="toggleSlot(court, slotCol.hour)">
                  <span class="text-xs font-bold text-emerald-500 group-hover:text-emerald-700">
                    ${{ getSlotPrice(court.id, slotCol.hour) }}
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
