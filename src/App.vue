<script setup>
import AppFooter from "./components/AppFooter.vue";
import AppHeader from "./components/AppHeader.vue";
</script>

<template>
  <v-app class="bg-slate-100 min-h-screen flex flex-col">
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
