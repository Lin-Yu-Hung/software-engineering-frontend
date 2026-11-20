<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState, isAuthenticated, login, logout } from '../stores/authStore'

const route = useRoute()
const router = useRouter()

const navigateTo = (path) => {
  router.push(path)
}

// 登入 dialog
const loginDialog = ref(false)
const loginForm = ref({ email: '', password: '' })
const loginError = ref('')
const loginLoading = ref(false)

const openLoginDialog = () => {
  loginForm.value = { email: '', password: '' }
  loginError.value = ''
  loginDialog.value = true
}

const handleLogin = async () => {
  if (!loginForm.value.email || !loginForm.value.password) {
    loginError.value = '請輸入帳號與密碼'
    return
  }
  loginLoading.value = true
  loginError.value = ''
  try {
    await login(loginForm.value.email, loginForm.value.password)
    loginDialog.value = false
  } catch (err) {
    loginError.value = err?.message || '帳號或密碼錯誤'
  } finally {
    loginLoading.value = false
  }
}

const handleLogout = async () => {
  await logout()
}
</script>

<template>
  <header class="bg-white border-b border-slate-200 px-6 py-3.5 shadow-xs sticky top-0 z-30">
    <div class="max-w-7xl mx-auto flex items-center justify-between flex-wrap gap-4">
      <!-- 品牌 Logo -->
      <div
        class="flex items-center gap-3 cursor-pointer group select-none"
        @click="navigateTo('/')"
      >
        <div class="w-10 h-10 rounded-xl bg-emerald-600 flex items-center justify-center text-white shadow-sm transition-transform group-hover:scale-105">
          <v-icon icon="mdi-badminton" size="24"></v-icon>
        </div>
        <div>
          <h1 class="text-lg font-bold text-slate-800 tracking-tight leading-tight group-hover:text-emerald-700 transition-colors">
            羽球場館線上預約系統
          </h1>
          <p class="text-xs text-slate-400 font-medium">Court Reservation & Management</p>
        </div>
      </div>

      <!-- 導覽切換 -->
      <nav class="flex items-center gap-2 flex-wrap">
        <v-btn
          :variant="route.path === '/' ? 'flat' : 'text'"
          :color="route.path === '/' ? 'primary' : 'grey-darken-2'"
          rounded="lg"
          prepend-icon="mdi-calendar-check-outline"
          class="font-medium !px-4"
          @click="navigateTo('/')"
        >
          場地預約
        </v-btn>

        <v-btn
          v-if="!route.path.startsWith('/admin')"
          :variant="route.path === '/my-bookings' ? 'flat' : 'text'"
          :color="route.path === '/my-bookings' ? 'primary' : 'grey-darken-2'"
          rounded="lg"
          prepend-icon="mdi-bookmark-multiple-outline"
          class="font-medium !px-4"
          @click="navigateTo('/my-bookings')"
        >
          我的預約
        </v-btn>

        <v-btn
          :variant="route.path.startsWith('/admin') ? 'flat' : 'text'"
          :color="route.path.startsWith('/admin') ? 'primary' : 'grey-darken-2'"
          rounded="lg"
          prepend-icon="mdi-view-dashboard-outline"
          class="font-medium !px-4"
          @click="navigateTo('/admin')"
        >
          後台管理看板
        </v-btn>

        <div class="h-5 w-px bg-slate-200 mx-1"></div>

        <!-- 登入狀態 -->
        <template v-if="!route.path.startsWith('/admin')">
          <template v-if="isAuthenticated">
            <v-chip
              color="success"
              variant="tonal"
              size="small"
              prepend-icon="mdi-account-check"
              class="font-medium !px-3"
            >
              {{ authState.user?.name || authState.user?.email }}
            </v-chip>
            <v-btn
              size="small"
              variant="text"
              color="grey-darken-1"
              prepend-icon="mdi-logout"
              class="font-medium"
              @click="handleLogout"
            >
              登出
            </v-btn>
          </template>
          <template v-else>
            <v-btn
              size="small"
              variant="tonal"
              color="primary"
              prepend-icon="mdi-login"
              class="font-medium !px-3"
              @click="openLoginDialog"
            >
              會員登入
            </v-btn>
          </template>
        </template>

        <v-chip
          v-if="route.path.startsWith('/admin')"
          color="secondary"
          variant="tonal"
          size="small"
          prepend-icon="mdi-shield-account"
          class="font-medium !px-3"
        >
          後台運營模式
        </v-chip>
      </nav>
    </div>
  </header>

  <!-- 會員登入 Dialog -->
  <v-dialog v-model="loginDialog" max-width="400">
    <v-card rounded="xl" class="overflow-hidden">
      <div class="bg-emerald-600 text-white p-5 flex items-center justify-between">
        <h3 class="text-base font-bold flex items-center gap-2">
          <v-icon icon="mdi-login" size="20"></v-icon>
          會員登入
        </h3>
        <v-btn icon="mdi-close" variant="text" size="small" color="white" @click="loginDialog = false"></v-btn>
      </div>
      <v-card-text class="p-6 space-y-4">
        <div>
          <label class="text-xs font-semibold text-slate-600 block mb-1.5">電子郵件</label>
          <input
            v-model="loginForm.email"
            type="email"
            placeholder="example@mail.com"
            class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white"
            @keyup.enter="handleLogin"
          />
        </div>
        <div>
          <label class="text-xs font-semibold text-slate-600 block mb-1.5">密碼</label>
          <input
            v-model="loginForm.password"
            type="password"
            placeholder="••••••••"
            class="w-full border border-slate-200 rounded-lg px-3.5 py-2.5 text-sm focus:outline-emerald-500 bg-white"
            @keyup.enter="handleLogin"
          />
        </div>
        <div v-if="loginError" class="text-xs text-rose-600 font-medium">{{ loginError }}</div>
        <v-btn
          color="primary"
          variant="flat"
          block
          rounded="lg"
          :loading="loginLoading"
          class="font-semibold"
          @click="handleLogin"
        >
          登入
        </v-btn>
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
