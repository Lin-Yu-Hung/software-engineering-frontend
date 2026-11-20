<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { cartStore, clearCart } from '../../stores/cartStore.js'
import { createHold, createOrder, initiateEcpayPayment } from '../../api/booking.js'
import { isAuthenticated, userRoles, login } from '../../stores/authStore.js'

const router = useRouter()

onMounted(() => {
  if (cartStore.items.length === 0) {
    router.replace('/')
  }
})

// ==============================
// 預約明細
// ==============================
const totalPrice = computed(() => cartStore.items.reduce((sum, i) => sum + i.price, 0))

const itemsByDate = computed(() => {
  const map = {}
  for (const item of cartStore.items) {
    if (!map[item.date]) map[item.date] = []
    map[item.date].push(item)
  }
  return Object.entries(map).sort(([a], [b]) => a.localeCompare(b))
})

const endSlot = (slot) => `${String(parseInt(slot) + 1).padStart(2, '0')}:00`

// ==============================
// 顧客資料表單
// ==============================
const form = ref({
  name: '',
  phone: '',
  payment: 'CREDIT_CARD',
})

const paymentOptions = [
  { value: 'CREDIT_CARD', label: '線上信用卡',  icon: 'mdi-credit-card',        sub: 'ECPay 綠界線上刷卡' },
  { value: 'CASH',        label: '現場現金',    icon: 'mdi-cash',               sub: '到場後付款' },
  { value: 'ONSITE_CARD', label: '現場刷卡',    icon: 'mdi-contactless-payment', sub: '到場後付款' },
]

// ==============================
// 未登入狀態：顯示登入表單
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
  } catch (err) {
    loginError.value = err?.message || '帳號或密碼錯誤'
  } finally {
    loginLoading.value = false
  }
}

// ==============================
// 鎖定時段 Hold（進結帳頁時自動建立）
// ==============================
const holdId = ref(null)
const holdExpiresAt = ref(null)
const holdError = ref(null)
const holdLoading = ref(false)
const holdCountdown = ref('')

let countdownTimer = null

const startCountdown = () => {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    if (!holdExpiresAt.value) return
    const diff = new Date(holdExpiresAt.value) - new Date()
    if (diff <= 0) {
      holdCountdown.value = '保留已過期'
      holdId.value = null
      clearInterval(countdownTimer)
      return
    }
    const mins = Math.floor(diff / 60000)
    const secs = Math.floor((diff % 60000) / 1000)
    holdCountdown.value = `${mins}:${String(secs).padStart(2, '0')}`
  }, 1000)
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

const createHoldForCart = async () => {
  if (!isMember.value) return
  holdLoading.value = true
  holdError.value = null
  try {
    const items = cartStore.items.map((i) => ({
      courtId: i.courtId,
      startAt: i.startAt,
    }))
    const hold = await createHold(items)
    holdId.value = hold.id
    holdExpiresAt.value = hold.expiresAt
    startCountdown()
  } catch (err) {
    holdError.value = err?.message || '時段鎖定失敗，可能已被其他人預約，請返回重新選擇'
    holdId.value = null
  } finally {
    holdLoading.value = false
  }
}

// 當登入後自動嘗試建立 Hold
watch(isMember, (val) => {
  if (val && !holdId.value && !holdLoading.value) {
    createHoldForCart()
  }
}, { immediate: true })

// ==============================
// 送出預約
// ==============================
const submitted = ref(false)
const orderNumber = ref('')
const submitting = ref(false)
const submitError = ref(null)

const submit = async () => {
  if (!form.value.name.trim()) {
    submitError.value = '請填寫預約人姓名'
    return
  }
  if (!form.value.phone.trim()) {
    submitError.value = '請填寫聯絡電話'
    return
  }
  if (!holdId.value) {
    submitError.value = '時段尚未鎖定，請稍候再試或重新整理頁面'
    return
  }

  submitting.value = true
  submitError.value = null

  try {
    const order = await createOrder({
      holdId: holdId.value,
      customer: {
        name: form.value.name.trim(),
        phone: form.value.phone.trim(),
      },
      paymentMethod: form.value.payment,
    })

    clearCart()

    if (form.value.payment === 'CREDIT_CARD') {
      // 跳轉至綠界付款頁
      const checkout = await initiateEcpayPayment(order.id)
      const formEl = document.createElement('form')
      formEl.method = 'POST'
      formEl.action = checkout.action
      for (const [key, value] of Object.entries(checkout.fields)) {
        const input = document.createElement('input')
        input.type = 'hidden'
        input.name = key
        input.value = value
        formEl.appendChild(input)
      }
      document.body.appendChild(formEl)
      formEl.submit()
    } else {
      orderNumber.value = order.orderNumber
      submitted.value = true
    }
  } catch (err) {
    submitError.value = err?.message || '預約送出失敗，請再試一次'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="max-w-2xl mx-auto px-6 py-8 space-y-6">

    <!-- 返回按鈕 -->
    <v-btn variant="text" color="grey-darken-1" prepend-icon="mdi-arrow-left"
           class="!px-0 font-medium -ml-1" @click="router.push('/')">
      返回場地查詢
    </v-btn>

    <!-- ===== 預約成功畫面 ===== -->
    <div v-if="submitted"
         class="bg-white rounded-2xl border border-emerald-200 p-10 text-center shadow-xs space-y-5">
      <div class="w-16 h-16 rounded-full bg-emerald-100 text-emerald-600 flex items-center justify-center mx-auto shadow-inner">
        <v-icon icon="mdi-check-circle" size="40"></v-icon>
      </div>
      <div>
        <h2 class="text-2xl font-bold text-slate-800 mb-2">預約建立成功！</h2>
        <p class="text-slate-500 text-sm">
          訂單編號：<span class="font-bold text-slate-700 text-base font-mono">{{ orderNumber }}</span>
        </p>
        <p class="text-xs text-slate-400 mt-2 max-w-sm mx-auto leading-relaxed">
          請於打球前出示此訂單編號至現場櫃台核銷入場。
        </p>
      </div>
      <div class="border-2 border-dashed border-emerald-300 rounded-xl p-5 bg-emerald-50 inline-block mx-auto">
        <div class="w-20 h-20 bg-slate-200 rounded-lg flex items-center justify-center mx-auto mb-2">
          <v-icon icon="mdi-qrcode" size="48" color="grey-darken-1"></v-icon>
        </div>
        <p class="text-xs text-slate-500 font-mono font-bold">{{ orderNumber }}</p>
        <p class="text-2xs text-slate-400 mt-0.5">掃描或出示此憑證核銷入場</p>
      </div>
      <div class="flex gap-3 justify-center flex-wrap">
        <v-btn variant="outlined" color="grey-darken-1" rounded="lg"
               prepend-icon="mdi-bookmark-multiple" @click="router.push('/my-bookings')">
          查看我的預約
        </v-btn>
        <v-btn color="primary" variant="flat" rounded="lg"
               prepend-icon="mdi-calendar-plus" @click="router.push('/')">
          繼續預約場地
        </v-btn>
      </div>
    </div>

    <!-- ===== 預約流程 ===== -->
    <template v-else>
      <!-- 頁面標題 -->
      <div>
        <h1 class="text-xl font-bold text-slate-800 flex items-center gap-2">
          <v-icon icon="mdi-calendar-check" color="primary" size="24"></v-icon>
          確認預約明細
        </h1>
        <p class="text-xs text-slate-400 mt-1">請確認以下已選時段，填寫資料後完成預約</p>
      </div>

      <!-- 預約明細卡 -->
      <div class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden">
        <div class="px-5 py-3.5 border-b border-slate-100 bg-slate-50 flex items-center gap-2">
          <v-icon icon="mdi-format-list-bulleted" size="17" color="primary"></v-icon>
          <span class="text-sm font-bold text-slate-700">預約時段明細</span>
          <v-chip size="x-small" color="primary" variant="flat" class="!px-2 font-bold ml-auto">
            共 {{ cartStore.items.length }} 個時段
          </v-chip>
        </div>

        <div class="divide-y divide-slate-100">
          <div v-for="[date, items] in itemsByDate" :key="date">
            <div class="px-5 py-2 bg-slate-50/60 text-xs font-bold text-slate-500 flex items-center gap-1.5">
              <v-icon icon="mdi-calendar" size="13"></v-icon>
              {{ date }}
            </div>
            <div v-for="item in items" :key="`${item.courtId}_${item.slot}`"
                 class="px-5 py-3.5 flex items-center justify-between gap-3">
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-9 h-9 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center shrink-0">
                  <v-icon icon="mdi-badminton" size="20"></v-icon>
                </div>
                <div class="min-w-0">
                  <p class="text-sm font-semibold text-slate-800 truncate">{{ item.courtName }}</p>
                  <p class="text-xs text-slate-400 whitespace-nowrap">
                    {{ item.slot }} ~ {{ endSlot(item.slot) }} · {{ item.courtType }}
                  </p>
                </div>
              </div>
              <div class="flex items-center gap-3 shrink-0">
                <div class="text-right">
                  <p class="text-sm font-bold text-emerald-600">${{ item.price }}</p>
                  <v-chip :color="item.isPeak ? 'amber' : 'slate'" size="x-small"
                          variant="tonal" class="!px-1.5 font-semibold">
                    {{ item.isPeak ? '尖峰' : '離峰' }}
                  </v-chip>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 合計列 -->
        <div class="px-5 py-4 border-t border-slate-200 bg-emerald-50 flex items-center justify-between">
          <span class="text-sm font-bold text-slate-700">預約總金額</span>
          <span class="text-xl font-bold text-emerald-600">${{ totalPrice }} 元</span>
        </div>
      </div>

      <!-- 時段鎖定狀態 -->
      <div v-if="isMember">
        <div v-if="holdLoading"
             class="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-700 flex items-center gap-2">
          <v-progress-circular size="16" width="2" indeterminate color="amber"></v-progress-circular>
          正在為您鎖定時段，請稍候...
        </div>
        <div v-else-if="holdError"
             class="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm text-rose-700 flex items-center gap-2">
          <v-icon icon="mdi-alert-circle" size="18"></v-icon>
          <span>{{ holdError }}</span>
          <v-btn size="x-small" variant="text" color="error" class="ml-auto" @click="createHoldForCart">重試</v-btn>
        </div>
        <div v-else-if="holdId"
             class="bg-emerald-50 border border-emerald-200 rounded-xl p-4 text-sm text-emerald-700 flex items-center gap-2">
          <v-icon icon="mdi-lock-check" size="18"></v-icon>
          時段已鎖定，請在 <span class="font-bold font-mono mx-1">{{ holdCountdown }}</span> 內完成付款
        </div>
      </div>

      <!-- 未登入提示 -->
      <div v-if="!isMember" class="bg-white rounded-xl border border-amber-200 shadow-xs p-6 space-y-4">
        <h3 class="text-sm font-bold text-slate-700 flex items-center gap-2">
          <v-icon icon="mdi-login" size="18" color="primary"></v-icon>
          請先登入會員才能完成預約
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
          登入並繼續預約
        </v-btn>
      </div>

      <!-- 顧客資料表單 (已登入才顯示) -->
      <div v-if="isMember" class="bg-white rounded-xl border border-slate-200 shadow-xs p-6 space-y-5">
        <h3 class="text-sm font-bold text-slate-700 flex items-center gap-2">
          <v-icon icon="mdi-account-edit" size="18" color="primary"></v-icon>
          預約人資料
        </h3>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="text-xs font-semibold text-slate-600 block mb-1.5">
              姓名 / 稱謂 <span class="text-rose-500">*</span>
            </label>
            <input v-model="form.name" type="text" placeholder="例如：王小明"
                   class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white" />
          </div>
          <div>
            <label class="text-xs font-semibold text-slate-600 block mb-1.5">
              聯絡電話 <span class="text-rose-500">*</span>
            </label>
            <input v-model="form.phone" type="tel" placeholder="例如：0912345678"
                   class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white" />
          </div>
        </div>

        <!-- 付款方式 -->
        <div>
          <label class="text-xs font-semibold text-slate-600 block mb-2">付款方式</label>
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
            <button v-for="opt in paymentOptions" :key="opt.value" type="button"
                    :class="['p-3.5 rounded-xl border-2 text-left transition-all',
                             form.payment === opt.value
                               ? 'border-emerald-500 bg-emerald-50'
                               : 'border-slate-200 bg-white hover:border-emerald-200']"
                    @click="form.payment = opt.value">
              <div class="flex items-center gap-2 mb-1">
                <v-icon :icon="opt.icon" size="16"
                        :color="form.payment === opt.value ? 'primary' : 'grey'"></v-icon>
                <span :class="['text-xs font-bold',
                               form.payment === opt.value ? 'text-emerald-700' : 'text-slate-700']">
                  {{ opt.label }}
                </span>
                <v-icon v-if="form.payment === opt.value"
                        icon="mdi-check-circle" size="14" color="primary" class="ml-auto"></v-icon>
              </div>
              <p class="text-2xs text-slate-400 leading-tight">{{ opt.sub }}</p>
            </button>
          </div>
        </div>
      </div>

      <!-- 取消退款政策說明 -->
      <div class="bg-amber-50 border border-amber-200 rounded-xl p-4 text-xs space-y-1.5">
        <p class="font-bold text-amber-800 flex items-center gap-1.5">
          <v-icon icon="mdi-information-outline" size="16" color="amber-darken-2"></v-icon>
          取消與退款政策
        </p>
        <ul class="text-amber-700 ml-5 list-disc space-y-0.5">
          <li>預約日前 <strong>3 天以上</strong>取消：全額退款</li>
          <li>預約日前 <strong>1 ~ 2 天</strong>取消：退款 50%</li>
          <li>預約<strong>當天</strong>取消：不予退款</li>
        </ul>
      </div>

      <!-- 錯誤訊息 -->
      <div v-if="submitError"
           class="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm text-rose-700 flex items-center gap-2">
        <v-icon icon="mdi-alert-circle" size="18"></v-icon>
        {{ submitError }}
      </div>

      <!-- 送出按鈕 -->
      <v-btn v-if="isMember"
             color="primary" variant="flat" rounded="lg" size="large" block
             prepend-icon="mdi-check-circle" class="font-bold text-base !py-4"
             :loading="submitting"
             :disabled="holdLoading || !!holdError || !holdId"
             @click="submit">
        {{ form.payment === 'CREDIT_CARD' ? '前往綠界線上付款' : '確認送出預約' }}
        <span class="ml-2 text-emerald-200 font-normal text-sm">（共 ${{ totalPrice }} 元）</span>
      </v-btn>
    </template>
  </div>
</template>
