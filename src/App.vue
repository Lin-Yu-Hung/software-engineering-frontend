<script setup>
import { onMounted } from 'vue'
import AppFooter from "./components/AppFooter.vue";
import AppHeader from "./components/AppHeader.vue";
import GlobalApiLoader from "./components/GlobalApiLoader.vue";
import { initAuth } from './stores/authStore'

onMounted(() => {
  initAuth()
})
</script>

<template>
  <v-app class="bg-slate-100 min-h-screen flex flex-col">
    <!-- 全域 API 載入動畫（所有透過 apiRequest 發出的請求皆會自動觸發） -->
    <GlobalApiLoader />

    <!-- 共用 Header (前台與後台共用) -->
    <AppHeader />

    <!-- 路由展示主體區域 -->
    <main class="flex-1 flex flex-col">
      <router-view />
    </main>

    <!-- 共用 Footer -->
    <AppFooter />
  </v-app>
</template>

<style>
html,
body,
#app {
  height: 100%;
}
</style>
