<script setup>
import { computed, ref, watch } from 'vue'
import { listBackofficeOrders } from '../../../api/backoffice'
import {
  ORDER_STATUS_META,
  PAYMENT_STATUS_META,
  REFUND_STATUS_META,
  getPaymentMethodLabel,
  getSourceLabel,
} from '../../../utils/backofficeLabels'
import {
  formatTaipeiDate,
  formatTaipeiDateTime,
  formatTaipeiHour,
} from '../../../utils/taipeiTime'

const props = defineProps({
  selectedDate: { type: String, required: true },
  courtNameMap: { type: Object, default: () => ({}) },
  refreshKey: { type: Number, default: 0 },
})

const emit = defineEmits(['select-order', 'toast'])

const filterByDate = ref(true)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const orderPage = ref({
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
})

const orders = computed(() => orderPage.value.content || [])

const fetchOrders = async () => {
  loading.value = true
  try {
    const res = await listBackofficeOrders({
      date: filterByDate.value ? props.selectedDate : undefined,
      page: page.value,
      size: size.value,
    })
    orderPage.value = res
  } catch (err) {
    emit('toast', {
      text: err?.message || '載入訂單列表失敗',
      color: 'error',
    })
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.selectedDate, filterByDate.value, props.refreshKey],
  () => {
    page.value = 0
    fetchOrders()
  },
  { immediate: true },
)

const goToPage = (nextPage) => {
  if (nextPage < 0 || nextPage >= (orderPage.value.totalPages || 1)) return
  page.value = nextPage
  fetchOrders()
}

const formatCourtName = (courtId) => {
  return props.courtNameMap[courtId] || `場地 (${String(courtId).slice(0, 6)})`
}

const formatItemSummary = (item) => {
  const d = formatTaipeiDate(item.startAt)
  const start = formatTaipeiHour(item.startAt)
  const end = formatTaipeiHour(item.endAt)
  return `${formatCourtName(item.courtId)} ${d} ${start}–${end}`
}
</script>

<template>
  <div
    class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden"
  >
    <!-- 標頭與篩選列 -->
    <div
      class="p-5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
    >
      <div>
        <h2 class="text-base font-bold text-slate-800 flex items-center gap-2">
          <v-icon icon="mdi-receipt-text-outline" color="primary" size="20" />
          後台訂單管理列表
          <span class="text-xs font-normal text-slate-400">
            （共 {{ orderPage.totalElements }} 筆）
          </span>
        </h2>
        <p class="text-xs text-slate-400 mt-0.5">
          可檢視所有狀態訂單，包含已取消（待人工審核退款）、待付款與已逾期紀錄
        </p>
      </div>

      <div class="flex items-center gap-2 flex-wrap">
        <div
          class="inline-flex rounded-lg border border-slate-200 p-0.5 bg-slate-50"
        >
          <button
            type="button"
            :class="[
              'px-3 py-1 rounded-md text-xs font-semibold transition-colors',
              filterByDate
                ? 'bg-emerald-600 text-white'
                : 'text-slate-600 hover:text-slate-900',
            ]"
            @click="filterByDate = true"
          >
            僅看 {{ selectedDate }}
          </button>
          <button
            type="button"
            :class="[
              'px-3 py-1 rounded-md text-xs font-semibold transition-colors',
              !filterByDate
                ? 'bg-emerald-600 text-white'
                : 'text-slate-600 hover:text-slate-900',
            ]"
            @click="filterByDate = false"
          >
            全部日期
          </button>
        </div>

        <v-btn
          size="small"
          variant="outlined"
          color="grey-darken-1"
          rounded="lg"
          prepend-icon="mdi-refresh"
          :loading="loading"
          @click="fetchOrders"
        >
          重新整理
        </v-btn>
      </div>
    </div>

    <!-- 訂單表格 -->
    <div class="overflow-x-auto">
      <table class="w-full border-collapse text-left text-xs">
        <thead>
          <tr
            class="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold"
          >
            <th class="p-3 whitespace-nowrap">訂單編號 / 建立時間</th>
            <th class="p-3 whitespace-nowrap">預約顧客 / 來源</th>
            <th class="p-3 whitespace-nowrap">預約時段</th>
            <th class="p-3 whitespace-nowrap">金額 / 付款</th>
            <th class="p-3 whitespace-nowrap">訂單 / 報到 / 退款狀態</th>
            <th class="p-3 text-right whitespace-nowrap">操作</th>
          </tr>
        </thead>

        <tbody v-if="loading && orders.length === 0">
          <tr>
            <td colspan="6" class="p-10 text-center text-slate-400">
              正在載入訂單資料...
            </td>
          </tr>
        </tbody>

        <tbody v-else-if="orders.length === 0">
          <tr>
            <td colspan="6" class="p-10 text-center text-slate-400">
              目前篩選條件下沒有訂單紀錄
            </td>
          </tr>
        </tbody>

        <tbody v-else class="divide-y divide-slate-100">
          <tr
            v-for="order in orders"
            :key="order.id"
            class="hover:bg-slate-50/70 transition-colors"
          >
            <td class="p-3 whitespace-nowrap">
              <div class="font-bold text-slate-800">{{ order.orderNumber }}</div>
              <div class="text-2xs text-slate-400 mt-0.5">
                {{ formatTaipeiDateTime(order.createdAt) }}
              </div>
            </td>

            <td class="p-3 whitespace-nowrap">
              <div class="font-semibold text-slate-800">
                {{ order.customer?.name }}
              </div>
              <div class="text-2xs text-slate-500">
                {{ order.customer?.phone }}
              </div>
              <div class="text-2xs text-slate-400 mt-0.5">
                {{ getSourceLabel(order.source) }}
              </div>
            </td>

            <td class="p-3">
              <div class="space-y-0.5 max-w-xs">
                <div
                  v-for="item in order.items"
                  :key="item.id"
                  class="text-2xs text-slate-700 whitespace-nowrap"
                >
                  {{ formatItemSummary(item) }}
                </div>
              </div>
            </td>

            <td class="p-3 whitespace-nowrap">
              <div class="font-bold text-emerald-700">
                ${{ order.totalAmount }}
              </div>
              <div class="text-2xs text-slate-500">
                {{ getPaymentMethodLabel(order.paymentMethod) }}
              </div>
              <v-chip
                size="x-small"
                variant="tonal"
                :color="
                  PAYMENT_STATUS_META[order.paymentStatus]?.chipColor || 'grey'
                "
                class="mt-1 !px-2 font-semibold"
              >
                {{
                  PAYMENT_STATUS_META[order.paymentStatus]?.label ||
                  order.paymentStatus
                }}
              </v-chip>
            </td>

            <td class="p-3 whitespace-nowrap">
              <div class="flex flex-wrap gap-1">
                <v-chip
                  size="x-small"
                  variant="flat"
                  :color="
                    ORDER_STATUS_META[order.status]?.chipColor || 'grey'
                  "
                  class="!px-2 font-semibold"
                >
                  {{ ORDER_STATUS_META[order.status]?.label || order.status }}
                </v-chip>

                <v-chip
                  v-if="order.checkedInAt"
                  size="x-small"
                  variant="tonal"
                  color="success"
                  class="!px-2 font-semibold"
                >
                  已報到
                </v-chip>

                <v-chip
                  v-if="order.refundStatus === 'PENDING_REVIEW'"
                  size="x-small"
                  variant="flat"
                  :color="REFUND_STATUS_META.PENDING_REVIEW.chipColor"
                  class="!px-2 font-semibold"
                >
                  {{ REFUND_STATUS_META.PENDING_REVIEW.label }}
                </v-chip>
              </div>
              <div
                v-if="order.cancellationReason"
                class="text-2xs text-rose-600 mt-1 max-w-[200px] truncate"
                :title="order.cancellationReason"
              >
                取消原因：{{ order.cancellationReason }}
              </div>
            </td>

            <td class="p-3 text-right whitespace-nowrap">
              <v-btn
                size="x-small"
                color="primary"
                variant="tonal"
                rounded="lg"
                prepend-icon="mdi-open-in-new"
                class="font-semibold"
                @click="emit('select-order', order)"
              >
                詳情 / 操作
              </v-btn>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分頁列 -->
    <div
      v-if="orderPage.totalPages > 1"
      class="px-5 py-3 border-t border-slate-200 bg-slate-50 flex items-center justify-between text-xs"
    >
      <span class="text-slate-500">
        第 {{ orderPage.page + 1 }} / {{ orderPage.totalPages }} 頁（共
        {{ orderPage.totalElements }} 筆）
      </span>
      <div class="flex items-center gap-2">
        <v-btn
          size="x-small"
          variant="outlined"
          color="grey-darken-1"
          :disabled="orderPage.page <= 0 || loading"
          @click="goToPage(orderPage.page - 1)"
        >
          上一頁
        </v-btn>
        <v-btn
          size="x-small"
          variant="outlined"
          color="grey-darken-1"
          :disabled="orderPage.page + 1 >= orderPage.totalPages || loading"
          @click="goToPage(orderPage.page + 1)"
        >
          下一頁
        </v-btn>
      </div>
    </div>
  </div>
</template>
