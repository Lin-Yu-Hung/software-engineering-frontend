<script setup>
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();

const navigateTo = (path) => {
  router.push(path);
};
</script>

<template>
  <header
    class="bg-white border-b border-slate-200 px-6 py-3.5 shadow-xs sticky top-0 z-30"
  >
    <div
      class="max-w-7xl mx-auto flex items-center justify-between flex-wrap gap-4"
    >
      <!-- 品牌 Logo 與主標題 (已移除測試副標題) -->
      <div
        class="flex items-center gap-3 cursor-pointer group select-none"
        @click="navigateTo('/')"
      >
        <div
          class="w-10 h-10 rounded-xl bg-emerald-600 flex items-center justify-center text-white shadow-sm transition-transform group-hover:scale-105"
        >
          <v-icon icon="mdi-badminton" size="24"></v-icon>
        </div>
        <div>
          <h1
            class="text-lg font-bold text-slate-800 tracking-tight leading-tight group-hover:text-emerald-700 transition-colors"
          >
            羽球場館線上預約系統
          </h1>
          <p class="text-xs text-slate-400 font-medium">
            Court Reservation & Management
          </p>
        </div>
      </div>

      <!-- 導覽切換 (前台顧客預約 / 後台管理看板) -->
      <nav class="flex items-center gap-2">
        <v-btn
          :variant="route.path === '/' ? 'flat' : 'text'"
          :color="route.path === '/' ? 'primary' : 'grey-darken-2'"
          rounded="lg"
          prepend-icon="mdi-calendar-check-outline"
          class="font-medium !px-4"
          @click="navigateTo('/')"
        >
          前台預約
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

        <!-- 系統狀態徽章 (足夠 padding-x) -->
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
        <v-chip
          v-else
          color="success"
          variant="tonal"
          size="small"
          prepend-icon="mdi-account-outline"
          class="font-medium !px-3"
        >
          顧客預約端
        </v-chip>
      </nav>
    </div>
  </header>
</template>
