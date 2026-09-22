<script setup>
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <div class="max-w-4xl mx-auto py-16 px-6 text-center">
    <div
      class="bg-white rounded-2xl border border-dashed border-slate-300 p-12 shadow-xs"
    >
      <div
        class="w-16 h-16 rounded-full bg-emerald-100 text-emerald-700 flex items-center justify-center mx-auto mb-5 shadow-inner"
      >
        <v-icon icon="mdi-account-group-outline" size="36"></v-icon>
      </div>

      <h2 class="text-2xl font-bold text-slate-800 tracking-tight mb-3">
        前台預約端畫面（保留開發中）
      </h2>

      <p class="text-slate-500 text-sm max-w-lg mx-auto leading-relaxed mb-6">
        此區塊已預留給前端協同開發者。未來將在此建構顧客端功能：
        <strong>場地即時查詢</strong>、<strong>時段預約</strong>、<strong
          >綠界 ECPay 線上付款</strong
        >
        與 <strong>個人訂單取消退款</strong>。
      </p>

      <div class="flex items-center justify-center gap-3 flex-wrap">
        <v-chip color="info" variant="tonal" size="small" class="!px-3.5">
          <v-icon icon="mdi-code-tags" class="mr-1"></v-icon>
          Views: src/views/client/ClientHome.vue
        </v-chip>
        <v-chip color="success" variant="tonal" size="small" class="!px-3.5">
          <v-icon icon="mdi-account" class="mr-1"></v-icon>
          角色：前台一般會員 / 訪客
        </v-chip>
      </div>

      <div
        class="mt-8 pt-8 border-t border-slate-100 flex items-center justify-center"
      >
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-view-dashboard"
          class="!px-5 py-2"
          @click="router.push('/admin')"
        >
          前往後台管理系統 (/admin)
        </v-btn>
      </div>
    </div>
  </div>
</template>
