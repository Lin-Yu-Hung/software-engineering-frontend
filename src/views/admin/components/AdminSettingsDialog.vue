<script setup>
import { computed, ref, watch } from 'vue'
import {
  createAdminCourt,
  createCalendarException,
  deleteCalendarException,
  getOperatingHours,
  getPricingRules,
  listAdminCourts,
  listCalendarExceptions,
  replaceOperatingHours,
  replacePricingRules,
  retireAdminCourt,
  updateAdminCourt,
  updateCalendarException,
} from '../../../api/backoffice'
import {
  COURT_STATUS_META,
  DAY_TYPE_LABELS,
  PRICE_TYPE_LABELS,
  WEEKDAY_NAMES,
} from '../../../utils/backofficeLabels'
import { getTodayTaipei, shiftDateStr } from '../../../utils/taipeiTime'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  todayDate: { type: String, default: () => getTodayTaipei() },
})

const emit = defineEmits(['update:modelValue', 'updated', 'toast'])

const dialogOpen = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const activeTab = ref('courts') // 'courts' | 'pricing' | 'hours' | 'exceptions'

const notify = (text, color = 'success') => emit('toast', { text, color })

// ==============================
// Tab 1: 場地主檔管理
// ==============================
const courtsLoading = ref(false)
const courtSavingId = ref(null)
const adminCourts = ref([])

const newCourtForm = ref({
  name: '',
  type: '標準 PU',
  status: 'ACTIVE',
  displayOrder: 1,
})
const creatingCourt = ref(false)

const courtTypePresets = [
  'VIP 木地板',
  '標準 PU',
  '訓練用 PU',
  '國際比賽地墊',
]

const loadAdminCourts = async () => {
  courtsLoading.value = true
  try {
    const list = await listAdminCourts()
    adminCourts.value = list.map((c) => ({ ...c }))
    newCourtForm.value.displayOrder = list.length + 1
  } catch (err) {
    notify(err?.message || '載入場地清單失敗', 'error')
  } finally {
    courtsLoading.value = false
  }
}

const handleCreateCourt = async () => {
  if (!newCourtForm.value.name.trim()) {
    notify('請輸入場地名稱', 'warning')
    return
  }
  creatingCourt.value = true
  try {
    await createAdminCourt({
      name: newCourtForm.value.name.trim(),
      type: newCourtForm.value.type.trim() || '標準 PU',
      status: newCourtForm.value.status,
      displayOrder: Number(newCourtForm.value.displayOrder) || 0,
    })
    newCourtForm.value.name = ''
    await loadAdminCourts()
    emit('updated')
    notify('場地新增成功！')
  } catch (err) {
    notify(err?.message || '新增場地失敗', 'error')
  } finally {
    creatingCourt.value = false
  }
}

const handleSaveCourt = async (court) => {
  if (!court.name?.trim()) {
    notify('場地名稱不可為空', 'warning')
    return
  }
  courtSavingId.value = court.id
  try {
    await updateAdminCourt(court.id, {
      name: court.name.trim(),
      type: court.type.trim(),
      status: court.status,
      displayOrder: Number(court.displayOrder) || 0,
      expectedVersion: court.version,
    })
    await loadAdminCourts()
    emit('updated')
    notify(`場地「${court.name}」已更新！`)
  } catch (err) {
    notify(err?.message || '更新場地失敗', 'error')
    if (err?.status === 409) await loadAdminCourts()
  } finally {
    courtSavingId.value = null
  }
}

const handleRetireCourt = async (court) => {
  if (
    !confirm(
      `確定要永久退役「${court.name}」嗎？退役後無法再修改或重新啟用（歷史訂單仍會保留）。`,
    )
  ) {
    return
  }
  courtSavingId.value = court.id
  try {
    await retireAdminCourt(court.id, court.version)
    await loadAdminCourts()
    emit('updated')
    notify(`場地「${court.name}」已退役停用`, 'warning')
  } catch (err) {
    notify(err?.message || '退役場地失敗', 'error')
    if (err?.status === 409) await loadAdminCourts()
  } finally {
    courtSavingId.value = null
  }
}

// ==============================
// Tab 2: 費率規則引擎
// ==============================
const pricingLoading = ref(false)
const pricingSaving = ref(false)
const pricingVersion = ref(0)
const pricingRules = ref([])

const loadPricingRules = async () => {
  pricingLoading.value = true
  try {
    const res = await getPricingRules()
    pricingVersion.value = res.version
    pricingRules.value = (res.rules || []).map((r, idx) => ({
      _key: `${Date.now()}_${idx}`,
      courtId: r.courtId || '',
      dayType: r.dayType,
      startHour: r.startHour,
      endHour: r.endHour,
      price: r.price,
      priceType: r.priceType,
      priority: r.priority,
    }))
  } catch (err) {
    notify(err?.message || '載入費率規則失敗', 'error')
  } finally {
    pricingLoading.value = false
  }
}

const addPricingRule = () => {
  pricingRules.value.push({
    _key: `${Date.now()}_${Math.random()}`,
    courtId: '',
    dayType: 'WEEKDAY',
    startHour: 18,
    endHour: 22,
    price: 500,
    priceType: 'PEAK',
    priority: 10,
  })
}

const removePricingRule = (index) => {
  pricingRules.value.splice(index, 1)
}

const validatePricingClientSide = (rules) => {
  if (!rules.length) return '至少需要一條費率規則'
  for (const r of rules) {
    if (r.startHour >= r.endHour) return '每條規則的開始時間須早於結束時間'
    if (!r.price || r.price < 1) return '價格必須為大於 0 的整數'
  }
  for (const dayType of ['WEEKDAY', 'WEEKEND', 'HOLIDAY']) {
    for (let h = 0; h < 24; h++) {
      const covered = rules.some(
        (r) =>
          !r.courtId &&
          r.dayType === dayType &&
          Number(r.startHour) <= h &&
          h < Number(r.endHour),
      )
      if (!covered) {
        return `${DAY_TYPE_LABELS[dayType]} 的全場預設費率未涵蓋 ${String(h).padStart(2, '0')}:00（需完整涵蓋 00:00–24:00）`
      }
    }
  }
  return null
}

const handleSavePricing = async () => {
  const payloadRules = pricingRules.value.map((r) => ({
    courtId: r.courtId || null,
    dayType: r.dayType,
    startHour: Number(r.startHour),
    endHour: Number(r.endHour),
    price: Math.round(Number(r.price)),
    priceType: r.priceType,
    priority: Number(r.priority) || 0,
  }))

  const validationError = validatePricingClientSide(payloadRules)
  if (validationError) {
    notify(validationError, 'warning')
    return
  }

  pricingSaving.value = true
  try {
    const res = await replacePricingRules({
      expectedVersion: pricingVersion.value,
      rules: payloadRules,
    })
    pricingVersion.value = res.version
    await loadPricingRules()
    emit('updated')
    notify('費率規則已更新並立即生效！')
  } catch (err) {
    notify(err?.message || '更新費率規則失敗', 'error')
    if (err?.status === 409) await loadPricingRules()
  } finally {
    pricingSaving.value = false
  }
}

// ==============================
// Tab 3: 每週營業時間
// ==============================
const hoursLoading = ref(false)
const hoursSaving = ref(false)
const hoursVersion = ref(0)
const advanceBookingDays = ref(30)
const weeklyDays = ref([])

const loadOperatingHours = async () => {
  hoursLoading.value = true
  try {
    const res = await getOperatingHours()
    hoursVersion.value = res.version
    advanceBookingDays.value = res.advanceBookingDays
    const byDay = new Map((res.days || []).map((d) => [d.dayOfWeek, d.periods]))
    weeklyDays.value = WEEKDAY_NAMES.map((w) => ({
      dayOfWeek: w.value,
      label: w.label,
      periods: (byDay.get(w.value) || []).map((p) => ({
        startHour: p.startHour,
        endHour: p.endHour,
      })),
    }))
  } catch (err) {
    notify(err?.message || '載入每週營業時間失敗', 'error')
  } finally {
    hoursLoading.value = false
  }
}

const addDayPeriod = (day) => {
  const lastEnd = day.periods.length
    ? day.periods[day.periods.length - 1].endHour
    : 8
  const start = Math.min(lastEnd, 22)
  const end = Math.min(Math.max(start + 1, 22), 24)
  day.periods.push({ startHour: start, endHour: end })
}

const removeDayPeriod = (day, idx) => {
  day.periods.splice(idx, 1)
}

const handleSaveHours = async () => {
  hoursSaving.value = true
  try {
    const res = await replaceOperatingHours({
      expectedVersion: hoursVersion.value,
      advanceBookingDays: Number(advanceBookingDays.value),
      days: weeklyDays.value.map((d) => ({
        dayOfWeek: d.dayOfWeek,
        periods: d.periods.map((p) => ({
          startHour: Number(p.startHour),
          endHour: Number(p.endHour),
        })),
      })),
    })
    hoursVersion.value = res.version
    await loadOperatingHours()
    emit('updated')
    notify('每週營業時間設定已儲存！')
  } catch (err) {
    notify(err?.message || '更新營業時間失敗', 'error')
    if (err?.status === 409) await loadOperatingHours()
  } finally {
    hoursSaving.value = false
  }
}

// ==============================
// Tab 4: 特殊日曆例外 (國定假日 / 休館 / 特別營業)
// ==============================
const exceptionsLoading = ref(false)
const exceptionSaving = ref(false)
const exceptionRange = ref({
  from: props.todayDate,
  to: shiftDateStr(props.todayDate, 90),
})
const calendarExceptions = ref([])
const editingExceptionId = ref(null)
const editingExceptionVersion = ref(0)

const exceptionForm = ref({
  date: props.todayDate,
  label: '',
  dayType: 'HOLIDAY',
  isClosed: false,
  periods: [{ startHour: 8, endHour: 22 }],
})

const resetExceptionForm = () => {
  editingExceptionId.value = null
  editingExceptionVersion.value = 0
  exceptionForm.value = {
    date: props.todayDate,
    label: '',
    dayType: 'HOLIDAY',
    isClosed: false,
    periods: [{ startHour: 8, endHour: 22 }],
  }
}

const loadCalendarExceptions = async () => {
  exceptionsLoading.value = true
  try {
    calendarExceptions.value = await listCalendarExceptions(
      exceptionRange.value.from,
      exceptionRange.value.to,
    )
  } catch (err) {
    notify(err?.message || '載入例外日清單失敗', 'error')
  } finally {
    exceptionsLoading.value = false
  }
}

const startEditException = (ex) => {
  editingExceptionId.value = ex.id
  editingExceptionVersion.value = ex.version
  exceptionForm.value = {
    date: ex.date,
    label: ex.label,
    dayType: ex.dayType,
    isClosed: !ex.periods || ex.periods.length === 0,
    periods:
      ex.periods && ex.periods.length > 0
        ? ex.periods.map((p) => ({
            startHour: p.startHour,
            endHour: p.endHour,
          }))
        : [{ startHour: 8, endHour: 22 }],
  }
}

const handleSaveException = async () => {
  if (!exceptionForm.value.date || !exceptionForm.value.label.trim()) {
    notify('請填寫日期與說明標籤', 'warning')
    return
  }

  const periods = exceptionForm.value.isClosed
    ? []
    : exceptionForm.value.periods.map((p) => ({
        startHour: Number(p.startHour),
        endHour: Number(p.endHour),
      }))

  exceptionSaving.value = true
  try {
    if (editingExceptionId.value) {
      await updateCalendarException(editingExceptionId.value, {
        expectedVersion: editingExceptionVersion.value,
        date: exceptionForm.value.date,
        label: exceptionForm.value.label.trim(),
        dayType: exceptionForm.value.dayType,
        periods,
      })
      notify('特殊營業日設定已更新！')
    } else {
      await createCalendarException({
        date: exceptionForm.value.date,
        label: exceptionForm.value.label.trim(),
        dayType: exceptionForm.value.dayType,
        periods,
      })
      notify('特殊營業日設定已新增！')
    }
    resetExceptionForm()
    await loadCalendarExceptions()
    emit('updated')
  } catch (err) {
    notify(err?.message || '儲存例外日失敗', 'error')
    if (err?.status === 409) await loadCalendarExceptions()
  } finally {
    exceptionSaving.value = false
  }
}

const handleDeleteException = async (ex) => {
  if (!confirm(`確定要刪除「${ex.date} ${ex.label}」並恢復一般營業規則嗎？`)) {
    return
  }
  try {
    await deleteCalendarException(ex.id, ex.version)
    if (editingExceptionId.value === ex.id) resetExceptionForm()
    await loadCalendarExceptions()
    emit('updated')
    notify('已刪除例外日設定', 'info')
  } catch (err) {
    notify(err?.message || '刪除例外日失敗', 'error')
  }
}

// ==============================
// 開啟彈窗 / 切換頁籤自動載入
// ==============================
const loadCurrentTab = async () => {
  if (activeTab.value === 'courts') {
    await loadAdminCourts()
  } else if (activeTab.value === 'pricing') {
    if (adminCourts.value.length === 0) await loadAdminCourts()
    await loadPricingRules()
  } else if (activeTab.value === 'hours') {
    await loadOperatingHours()
  } else if (activeTab.value === 'exceptions') {
    await loadCalendarExceptions()
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      exceptionRange.value = {
        from: props.todayDate,
        to: shiftDateStr(props.todayDate, 90),
      }
      resetExceptionForm()
      loadCurrentTab()
    }
  },
)

watch(activeTab, () => {
  if (props.modelValue) loadCurrentTab()
})

const activeCourtOptions = computed(() =>
  adminCourts.value.filter((c) => c.status !== 'RETIRED'),
)
</script>

<template>
  <v-dialog v-model="dialogOpen" max-width="860">
    <v-card rounded="xl" class="overflow-hidden">
      <!-- 標頭 -->
      <div
        class="bg-slate-900 text-white p-5 flex items-center justify-between"
      >
        <div class="flex items-center gap-3">
          <div
            class="w-9 h-9 rounded-lg bg-slate-800 flex items-center justify-center"
          >
            <v-icon icon="mdi-tune-variant" color="primary" size="22"></v-icon>
          </div>
          <div>
            <h3 class="text-base font-bold">場館營運設定</h3>
            <p class="text-xs text-slate-400 mt-0.5">
              場館管理員專屬 (ADMIN) — 即時同步後端資料庫
            </p>
          </div>
        </div>
        <v-btn
          icon="mdi-close"
          variant="text"
          size="small"
          color="grey-lighten-1"
          @click="dialogOpen = false"
        ></v-btn>
      </div>

      <!-- 頁籤切換 -->
      <div class="flex border-b border-slate-200 bg-slate-50 px-4 overflow-x-auto">
        <button
          type="button"
          :class="[
            'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-1.5 whitespace-nowrap',
            activeTab === 'courts'
              ? 'border-emerald-500 text-emerald-600 bg-white'
              : 'border-transparent text-slate-500 hover:text-slate-800',
          ]"
          @click="activeTab = 'courts'"
        >
          <v-icon icon="mdi-badminton" size="16"></v-icon>
          場地主檔 ({{ adminCourts.length }})
        </button>

        <button
          type="button"
          :class="[
            'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-1.5 whitespace-nowrap',
            activeTab === 'pricing'
              ? 'border-emerald-500 text-emerald-600 bg-white'
              : 'border-transparent text-slate-500 hover:text-slate-800',
          ]"
          @click="activeTab = 'pricing'"
        >
          <v-icon icon="mdi-currency-usd" size="16"></v-icon>
          費率規則引擎
        </button>

        <button
          type="button"
          :class="[
            'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-1.5 whitespace-nowrap',
            activeTab === 'hours'
              ? 'border-emerald-500 text-emerald-600 bg-white'
              : 'border-transparent text-slate-500 hover:text-slate-800',
          ]"
          @click="activeTab = 'hours'"
        >
          <v-icon icon="mdi-clock-outline" size="16"></v-icon>
          每週營業時間
        </button>

        <button
          type="button"
          :class="[
            'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-1.5 whitespace-nowrap',
            activeTab === 'exceptions'
              ? 'border-emerald-500 text-emerald-600 bg-white'
              : 'border-transparent text-slate-500 hover:text-slate-800',
          ]"
          @click="activeTab = 'exceptions'"
        >
          <v-icon icon="mdi-calendar-star" size="16"></v-icon>
          特殊日曆例外
        </button>
      </div>

      <v-card-text class="p-6 max-h-[68vh] overflow-y-auto space-y-5">
        <!-- ============================== -->
        <!-- 頁籤 1: 場地主檔管理 -->
        <!-- ============================== -->
        <div v-if="activeTab === 'courts'" class="space-y-4">
          <!-- 新增場地表單 -->
          <div
            class="bg-emerald-50/70 rounded-xl border border-emerald-200 p-4 space-y-3"
          >
            <div class="text-xs font-bold text-emerald-900">新增場地</div>
            <div class="grid grid-cols-1 sm:grid-cols-4 gap-2.5 text-xs">
              <input
                v-model="newCourtForm.name"
                type="text"
                placeholder="場地名稱 (例：第 7 場地)"
                class="border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white focus:outline-emerald-500"
              />
              <input
                v-model="newCourtForm.type"
                list="court-type-presets"
                type="text"
                placeholder="材質規格"
                class="border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white focus:outline-emerald-500"
              />
              <datalist id="court-type-presets">
                <option v-for="t in courtTypePresets" :key="t" :value="t" />
              </datalist>
              <div class="flex items-center gap-1.5">
                <span class="text-slate-500 shrink-0">排序</span>
                <input
                  v-model.number="newCourtForm.displayOrder"
                  type="number"
                  min="0"
                  class="w-full border border-slate-200 rounded-lg px-2 py-1.5 bg-white focus:outline-emerald-500"
                />
              </div>
              <v-btn
                color="primary"
                size="small"
                variant="flat"
                rounded="lg"
                prepend-icon="mdi-plus"
                :loading="creatingCourt"
                class="font-semibold"
                @click="handleCreateCourt"
              >
                新增場地
              </v-btn>
            </div>
          </div>

          <div
            v-if="courtsLoading"
            class="py-8 text-center text-xs text-slate-400"
          >
            載入場地清單中...
          </div>

          <!-- 既有場地列表 -->
          <div v-else class="space-y-3">
            <div
              v-for="court in adminCourts"
              :key="court.id"
              :class="[
                'rounded-xl border p-4 space-y-3',
                court.status === 'RETIRED'
                  ? 'bg-slate-100/70 border-slate-200 opacity-75'
                  : 'bg-slate-50 border-slate-200',
              ]"
            >
              <div class="flex items-center justify-between gap-2 flex-wrap">
                <div class="flex items-center gap-2">
                  <v-chip
                    size="x-small"
                    variant="flat"
                    :color="COURT_STATUS_META[court.status]?.chipColor || 'grey'"
                    class="!px-2 font-semibold"
                  >
                    {{ COURT_STATUS_META[court.status]?.label || court.status }}
                  </v-chip>
                  <span class="text-2xs text-slate-400">
                    v{{ court.version }}
                  </span>
                </div>

                <div
                  v-if="court.status !== 'RETIRED'"
                  class="flex items-center gap-2"
                >
                  <v-btn
                    size="x-small"
                    color="primary"
                    variant="flat"
                    rounded="lg"
                    prepend-icon="mdi-content-save"
                    :loading="courtSavingId === court.id"
                    @click="handleSaveCourt(court)"
                  >
                    儲存
                  </v-btn>
                  <v-btn
                    size="x-small"
                    color="error"
                    variant="tonal"
                    rounded="lg"
                    prepend-icon="mdi-archive-remove-outline"
                    :loading="courtSavingId === court.id"
                    @click="handleRetireCourt(court)"
                  >
                    退役
                  </v-btn>
                </div>
                <span v-else class="text-2xs text-slate-400">
                  已退役場地不可再修改
                </span>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-4 gap-3 text-xs">
                <div>
                  <label class="text-slate-400 block mb-0.5">場地名稱</label>
                  <input
                    v-model="court.name"
                    type="text"
                    :disabled="court.status === 'RETIRED'"
                    class="w-full border border-slate-200 rounded-md px-2.5 py-1.5 bg-white font-semibold disabled:bg-slate-100"
                  />
                </div>
                <div>
                  <label class="text-slate-400 block mb-0.5">材質規格</label>
                  <input
                    v-model="court.type"
                    list="court-type-presets"
                    type="text"
                    :disabled="court.status === 'RETIRED'"
                    class="w-full border border-slate-200 rounded-md px-2.5 py-1.5 bg-white disabled:bg-slate-100"
                  />
                </div>
                <div>
                  <label class="text-slate-400 block mb-0.5">狀態</label>
                  <select
                    v-model="court.status"
                    :disabled="court.status === 'RETIRED'"
                    class="w-full border border-slate-200 rounded-md px-2 py-1.5 bg-white disabled:bg-slate-100"
                  >
                    <option value="ACTIVE">開放預約 (ACTIVE)</option>
                    <option value="DISABLED">暫停開放 (DISABLED)</option>
                    <option v-if="court.status === 'RETIRED'" value="RETIRED">
                      已退役 (RETIRED)
                    </option>
                  </select>
                </div>
                <div>
                  <label class="text-slate-400 block mb-0.5">顯示排序</label>
                  <input
                    v-model.number="court.displayOrder"
                    type="number"
                    min="0"
                    :disabled="court.status === 'RETIRED'"
                    class="w-full border border-slate-200 rounded-md px-2.5 py-1.5 bg-white disabled:bg-slate-100"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- ============================== -->
        <!-- 頁籤 2: 費率規則引擎 -->
        <!-- ============================== -->
        <div v-else-if="activeTab === 'pricing'" class="space-y-4">
          <div
            class="p-3.5 bg-emerald-50 border border-emerald-200 rounded-xl text-xs text-emerald-800 space-y-1"
          >
            <p class="font-bold">
              費率引擎規則說明（目前版本：v{{ pricingVersion }}）
            </p>
            <p class="text-emerald-700">
              「平日 / 週末 / 國定假日」皆須有涵蓋 00:00–24:00
              的全場預設費率；個別場地規則優先於全場預設，同範圍則優先序數字大者勝出且不可重疊。
            </p>
          </div>

          <div class="flex justify-between items-center">
            <span class="text-xs text-slate-500">
              共 {{ pricingRules.length }} 條費率規則
            </span>
            <v-btn
              size="small"
              color="primary"
              variant="tonal"
              rounded="lg"
              prepend-icon="mdi-plus"
              @click="addPricingRule"
            >
              新增費率規則
            </v-btn>
          </div>

          <div
            v-if="pricingLoading"
            class="py-8 text-center text-xs text-slate-400"
          >
            載入費率規則中...
          </div>

          <div v-else class="overflow-x-auto border border-slate-200 rounded-xl">
            <table class="w-full border-collapse text-left text-xs">
              <thead>
                <tr
                  class="bg-slate-50 border-b border-slate-200 text-slate-600"
                >
                  <th class="p-2.5">適用場地</th>
                  <th class="p-2.5">日期類型</th>
                  <th class="p-2.5">起時</th>
                  <th class="p-2.5">迄時</th>
                  <th class="p-2.5">價格 (元)</th>
                  <th class="p-2.5">費率類型</th>
                  <th class="p-2.5">優先序</th>
                  <th class="p-2.5 text-center">刪</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                <tr v-for="(rule, idx) in pricingRules" :key="rule._key">
                  <td class="p-2">
                    <select
                      v-model="rule.courtId"
                      class="border border-slate-200 rounded-md px-2 py-1 bg-white w-32"
                    >
                      <option value="">全場預設</option>
                      <option
                        v-for="c in activeCourtOptions"
                        :key="c.id"
                        :value="c.id"
                      >
                        {{ c.name }}
                      </option>
                    </select>
                  </td>
                  <td class="p-2">
                    <select
                      v-model="rule.dayType"
                      class="border border-slate-200 rounded-md px-2 py-1 bg-white"
                    >
                      <option value="WEEKDAY">平日</option>
                      <option value="WEEKEND">週末</option>
                      <option value="HOLIDAY">國定假日</option>
                    </select>
                  </td>
                  <td class="p-2">
                    <input
                      v-model.number="rule.startHour"
                      type="number"
                      min="0"
                      max="23"
                      class="w-16 border border-slate-200 rounded-md px-2 py-1 bg-white"
                    />
                  </td>
                  <td class="p-2">
                    <input
                      v-model.number="rule.endHour"
                      type="number"
                      min="1"
                      max="24"
                      class="w-16 border border-slate-200 rounded-md px-2 py-1 bg-white"
                    />
                  </td>
                  <td class="p-2">
                    <input
                      v-model.number="rule.price"
                      type="number"
                      min="1"
                      max="100000"
                      class="w-20 border border-slate-200 rounded-md px-2 py-1 bg-white font-bold text-emerald-700"
                    />
                  </td>
                  <td class="p-2">
                    <select
                      v-model="rule.priceType"
                      class="border border-slate-200 rounded-md px-2 py-1 bg-white"
                    >
                      <option
                        v-for="(label, key) in PRICE_TYPE_LABELS"
                        :key="key"
                        :value="key"
                      >
                        {{ label }}
                      </option>
                    </select>
                  </td>
                  <td class="p-2">
                    <input
                      v-model.number="rule.priority"
                      type="number"
                      min="0"
                      max="100"
                      class="w-16 border border-slate-200 rounded-md px-2 py-1 bg-white"
                    />
                  </td>
                  <td class="p-2 text-center">
                    <v-btn
                      icon="mdi-delete-outline"
                      size="x-small"
                      variant="text"
                      color="error"
                      @click="removePricingRule(idx)"
                    ></v-btn>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="flex justify-end">
            <v-btn
              color="primary"
              variant="flat"
              rounded="lg"
              prepend-icon="mdi-content-save-check"
              :loading="pricingSaving"
              class="font-bold"
              @click="handleSavePricing"
            >
              儲存費率規則 (PUT)
            </v-btn>
          </div>
        </div>

        <!-- ============================== -->
        <!-- 頁籤 3: 每週營業時間 -->
        <!-- ============================== -->
        <div v-else-if="activeTab === 'hours'" class="space-y-4">
          <div
            v-if="hoursLoading"
            class="py-8 text-center text-xs text-slate-400"
          >
            載入營業時間中...
          </div>

          <template v-else>
            <div
              class="bg-slate-50 rounded-xl border border-slate-200 p-4 flex flex-wrap items-center justify-between gap-3 text-xs"
            >
              <div>
                <div class="font-bold text-slate-800">
                  可提前預約天數 (advanceBookingDays)
                </div>
                <div class="text-slate-400">
                  目前設定版本：v{{ hoursVersion }}（縮減營業時間不可排除既有未來預約）
                </div>
              </div>
              <div class="flex items-center gap-2">
                <input
                  v-model.number="advanceBookingDays"
                  type="number"
                  min="1"
                  max="365"
                  class="w-24 border border-slate-200 rounded-lg px-3 py-1.5 bg-white font-bold text-slate-800"
                />
                <span class="text-slate-500">天</span>
              </div>
            </div>

            <div class="space-y-2.5">
              <div
                v-for="day in weeklyDays"
                :key="day.dayOfWeek"
                class="bg-white rounded-xl border border-slate-200 p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs"
              >
                <div class="font-bold text-slate-700 w-20 shrink-0">
                  {{ day.label }}
                </div>

                <div class="flex-1 flex flex-wrap items-center gap-2">
                  <span
                    v-if="day.periods.length === 0"
                    class="text-rose-500 font-semibold"
                  >
                    本日休館 (periods = [])
                  </span>
                  <div
                    v-for="(period, pIdx) in day.periods"
                    :key="pIdx"
                    class="inline-flex items-center gap-1.5 bg-slate-50 border border-slate-200 rounded-lg px-2 py-1"
                  >
                    <input
                      v-model.number="period.startHour"
                      type="number"
                      min="0"
                      max="23"
                      class="w-12 border border-slate-200 rounded px-1.5 py-0.5 bg-white text-center"
                    />
                    <span>:00 –</span>
                    <input
                      v-model.number="period.endHour"
                      type="number"
                      min="1"
                      max="24"
                      class="w-12 border border-slate-200 rounded px-1.5 py-0.5 bg-white text-center"
                    />
                    <span>:00</span>
                    <button
                      type="button"
                      class="text-rose-400 hover:text-rose-600 ml-1"
                      title="移除此時段"
                      @click="removeDayPeriod(day, pIdx)"
                    >
                      <v-icon icon="mdi-close" size="14"></v-icon>
                    </button>
                  </div>
                </div>

                <v-btn
                  size="x-small"
                  variant="tonal"
                  color="primary"
                  prepend-icon="mdi-plus"
                  @click="addDayPeriod(day)"
                >
                  加時段
                </v-btn>
              </div>
            </div>

            <div class="flex justify-end">
              <v-btn
                color="primary"
                variant="flat"
                rounded="lg"
                prepend-icon="mdi-content-save-check"
                :loading="hoursSaving"
                class="font-bold"
                @click="handleSaveHours"
              >
                儲存每週營業時間 (PUT)
              </v-btn>
            </div>
          </template>
        </div>

        <!-- ============================== -->
        <!-- 頁籤 4: 特殊日曆例外 -->
        <!-- ============================== -->
        <div v-else-if="activeTab === 'exceptions'" class="space-y-4">
          <!-- 新增 / 編輯表單 -->
          <div
            class="bg-slate-50 rounded-xl border border-slate-200 p-4 space-y-3 text-xs"
          >
            <div class="flex items-center justify-between">
              <span class="font-bold text-slate-800">
                {{ editingExceptionId ? '編輯特殊營業日' : '新增特殊營業日 / 國定假日' }}
              </span>
              <v-btn
                v-if="editingExceptionId"
                size="x-small"
                variant="text"
                color="grey"
                @click="resetExceptionForm"
              >
                取消編輯
              </v-btn>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label class="text-slate-500 block mb-1">日期（不可為過去日期）</label>
                <input
                  v-model="exceptionForm.date"
                  type="date"
                  class="w-full border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white"
                />
              </div>
              <div>
                <label class="text-slate-500 block mb-1">說明標籤</label>
                <input
                  v-model="exceptionForm.label"
                  type="text"
                  placeholder="例：元旦連假 / 館內消毒公休"
                  class="w-full border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white"
                />
              </div>
              <div>
                <label class="text-slate-500 block mb-1">套用費率日期類型</label>
                <select
                  v-model="exceptionForm.dayType"
                  class="w-full border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white"
                >
                  <option value="HOLIDAY">國定假日 (HOLIDAY)</option>
                  <option value="WEEKEND">週末 (WEEKEND)</option>
                  <option value="WEEKDAY">平日 (WEEKDAY)</option>
                </select>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-4">
              <label class="flex items-center gap-1.5 cursor-pointer font-semibold text-slate-700">
                <input
                  v-model="exceptionForm.isClosed"
                  type="checkbox"
                  class="rounded text-emerald-600"
                />
                <span>本日休館 (不開放預約)</span>
              </label>

              <div
                v-if="!exceptionForm.isClosed"
                class="flex items-center gap-2 flex-wrap"
              >
                <span class="text-slate-500">營業區間：</span>
                <div
                  v-for="(p, idx) in exceptionForm.periods"
                  :key="idx"
                  class="inline-flex items-center gap-1 bg-white border border-slate-200 rounded-lg px-2 py-1"
                >
                  <input
                    v-model.number="p.startHour"
                    type="number"
                    min="0"
                    max="23"
                    class="w-12 border border-slate-200 rounded px-1 py-0.5 text-center"
                  />
                  <span>:00 –</span>
                  <input
                    v-model.number="p.endHour"
                    type="number"
                    min="1"
                    max="24"
                    class="w-12 border border-slate-200 rounded px-1 py-0.5 text-center"
                  />
                  <span>:00</span>
                </div>
              </div>
            </div>

            <div class="flex justify-end">
              <v-btn
                color="primary"
                size="small"
                variant="flat"
                rounded="lg"
                :loading="exceptionSaving"
                @click="handleSaveException"
              >
                {{ editingExceptionId ? '更新例外日' : '新增例外日' }}
              </v-btn>
            </div>
          </div>

          <!-- 查詢範圍與清單 -->
          <div class="flex items-center gap-2 flex-wrap text-xs">
            <span class="text-slate-500">查詢區間：</span>
            <input
              v-model="exceptionRange.from"
              type="date"
              class="border border-slate-200 rounded-lg px-2.5 py-1 bg-white"
            />
            <span>至</span>
            <input
              v-model="exceptionRange.to"
              type="date"
              class="border border-slate-200 rounded-lg px-2.5 py-1 bg-white"
            />
            <v-btn
              size="x-small"
              variant="outlined"
              color="grey-darken-1"
              :loading="exceptionsLoading"
              @click="loadCalendarExceptions"
            >
              查詢
            </v-btn>
          </div>

          <div
            v-if="calendarExceptions.length === 0"
            class="py-6 text-center text-xs text-slate-400 border border-dashed border-slate-200 rounded-xl"
          >
            此日期區間內尚無特殊營業日例外
          </div>

          <div v-else class="space-y-2">
            <div
              v-for="ex in calendarExceptions"
              :key="ex.id"
              class="bg-white rounded-xl border border-slate-200 p-3.5 flex items-center justify-between gap-3 text-xs"
            >
              <div>
                <div class="flex items-center gap-2">
                  <span class="font-bold text-slate-800">{{ ex.date }}</span>
                  <span class="font-semibold text-emerald-700">{{ ex.label }}</span>
                  <v-chip size="x-small" variant="tonal" color="secondary" class="!px-2">
                    {{ DAY_TYPE_LABELS[ex.dayType] || ex.dayType }}
                  </v-chip>
                </div>
                <div class="text-2xs text-slate-500 mt-1">
                  <span v-if="!ex.periods || ex.periods.length === 0" class="text-rose-600 font-semibold">
                    全日休館
                  </span>
                  <span v-else>
                    營業時段：{{
                      ex.periods
                        .map(
                          (p) =>
                            `${String(p.startHour).padStart(2, '0')}:00–${String(p.endHour).padStart(2, '0')}:00`,
                        )
                        .join('、')
                    }}
                  </span>
                </div>
              </div>

              <div class="flex items-center gap-1.5 shrink-0">
                <v-btn
                  size="x-small"
                  variant="tonal"
                  color="primary"
                  @click="startEditException(ex)"
                >
                  編輯
                </v-btn>
                <v-btn
                  size="x-small"
                  variant="tonal"
                  color="error"
                  @click="handleDeleteException(ex)"
                >
                  刪除
                </v-btn>
              </div>
            </div>
          </div>
        </div>
      </v-card-text>

      <!-- 彈窗底部 -->
      <div
        class="p-4 border-t border-slate-200 bg-slate-50 flex items-center justify-between"
      >
        <span class="text-xs text-slate-400">
          各分頁儲存後皆會立即同步至排程看板
        </span>
        <v-btn
          variant="outlined"
          color="grey-darken-1"
          rounded="lg"
          size="small"
          @click="dialogOpen = false"
        >
          關閉
        </v-btn>
      </div>
    </v-card>
  </v-dialog>
</template>
