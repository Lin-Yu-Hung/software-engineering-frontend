<script setup>
import { ref } from 'vue'
import {
  authState,
  isAuthenticated,
  hasBackofficeAccess,
  login,
  logout,
} from '../../../stores/authStore'

const emit = defineEmits(['logged-in'])

const email = ref('')
const password = ref('')
const showPassword = ref(false)
const errorMessage = ref('')
const isDev = import.meta.env.DEV

const fillDemoAccount = (demoEmail, demoPassword) => {
  email.value = demoEmail
  password.value = demoPassword
  errorMessage.value = ''
}

const handleSubmit = async () => {
  errorMessage.value = ''
  if (!email.value.trim() || !password.value) {
    errorMessage.value = '請輸入後台帳號 Email 與密碼'
    return
  }

  try {
    await login(email.value, password.value)
    if (hasBackofficeAccess.value) {
      emit('logged-in')
    }
  } catch (err) {
    errorMessage.value = err?.message || '登入失敗，請確認帳號密碼或後端連線'
  }
}

const handleSwitchAccount = async () => {
  await logout()
  errorMessage.value = ''
}
</script>

<template>
  <div class="max-w-md mx-auto py-12 px-4">
    <div
      class="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden"
    >
      <div class="bg-slate-900 text-white p-6">
        <div class="flex items-center gap-3">
          <div
            class="w-11 h-11 rounded-xl bg-emerald-600 flex items-center justify-center shrink-0"
          >
            <v-icon icon="mdi-shield-account" size="26"></v-icon>
          </div>
          <div>
            <h2 class="text-lg font-bold tracking-tight">場館後台管理登入</h2>
            <p class="text-xs text-slate-400 mt-0.5">
              僅限櫃台人員 (FRONT_DESK) 與管理員 (ADMIN) 存取
            </p>
          </div>
        </div>
      </div>

      <!-- 已登入但非後台角色 -->
      <div
        v-if="isAuthenticated && !hasBackofficeAccess"
        class="p-6 space-y-4"
      >
        <div
          class="p-4 rounded-xl bg-amber-50 border border-amber-200 text-xs text-amber-900 space-y-1.5"
        >
          <p class="font-bold flex items-center gap-1.5">
            <v-icon icon="mdi-alert-circle" size="16" color="warning"></v-icon>
            目前帳號無後台存取權限
          </p>
          <p>
            目前登入帳號為 <strong>{{ authState.user?.email }}</strong>（角色：{{
              (authState.user?.roles || []).join(', ') || 'MEMBER'
            }}），請切換為櫃台或管理員帳號。
          </p>
        </div>

        <v-btn
          color="primary"
          variant="flat"
          block
          rounded="lg"
          prepend-icon="mdi-logout"
          class="font-semibold"
          @click="handleSwitchAccount"
        >
          登出並切換後台帳號
        </v-btn>
      </div>

      <!-- 登入表單 -->
      <form v-else class="p-6 space-y-4" @submit.prevent="handleSubmit">
        <div
          v-if="errorMessage"
          class="p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700 font-medium flex items-start gap-2"
        >
          <v-icon
            icon="mdi-alert-circle-outline"
            size="16"
            class="shrink-0 mt-0.5"
          ></v-icon>
          <span>{{ errorMessage }}</span>
        </div>

        <div>
          <label class="text-xs font-semibold text-slate-700 block mb-1">
            帳號 Email
          </label>
          <input
            v-model="email"
            type="email"
            autocomplete="username"
            placeholder="admin@booking.local"
            class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500"
          />
        </div>

        <div>
          <label class="text-xs font-semibold text-slate-700 block mb-1">
            密碼
          </label>
          <div class="relative">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="請輸入密碼"
              class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-emerald-500"
            />
            <button
              type="button"
              class="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              @click="showPassword = !showPassword"
            >
              <v-icon
                :icon="showPassword ? 'mdi-eye-off' : 'mdi-eye'"
                size="18"
              ></v-icon>
            </button>
          </div>
        </div>

        <v-btn
          type="submit"
          color="primary"
          variant="flat"
          block
          rounded="lg"
          size="large"
          :loading="authState.loading"
          prepend-icon="mdi-login"
          class="font-bold"
        >
          登入後台系統
        </v-btn>

        <!-- 僅本機開發模式顯示的快速填入提示 -->
        <div
          v-if="isDev"
          class="pt-4 border-t border-slate-100 space-y-2 text-xs"
        >
          <p class="text-slate-400 font-medium">
            本機 Docker 示範帳號快速填入（不適用於 Azure）：
          </p>
          <div class="grid grid-cols-2 gap-2">
            <button
              type="button"
              class="px-3 py-2 rounded-lg border border-slate-200 bg-slate-50 hover:bg-emerald-50 hover:border-emerald-300 text-left transition-colors"
              @click="fillDemoAccount('admin@booking.local', 'AdminDemo123!')"
            >
              <div class="font-bold text-slate-700">場館管理員</div>
              <div class="text-2xs text-slate-400 truncate">
                admin@booking.local
              </div>
            </button>
            <button
              type="button"
              class="px-3 py-2 rounded-lg border border-slate-200 bg-slate-50 hover:bg-emerald-50 hover:border-emerald-300 text-left transition-colors"
              @click="
                fillDemoAccount('frontdesk@booking.local', 'StaffDemo123!')
              "
            >
              <div class="font-bold text-slate-700">櫃台人員</div>
              <div class="text-2xs text-slate-400 truncate">
                frontdesk@booking.local
              </div>
            </button>
          </div>
        </div>
      </form>
    </div>
  </div>
</template>
