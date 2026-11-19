<script setup>
import { isApiLoading } from '../stores/loadingStore'
</script>

<template>
  <teleport to="body">
    <!-- 頂部全域進度條 -->
    <transition
      enter-active-class="transition-opacity duration-150"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-200"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="isApiLoading"
        class="fixed top-0 left-0 right-0 z-[100000] pointer-events-none"
      >
        <v-progress-linear
          indeterminate
          color="primary"
          bg-color="emerald-lighten-4"
          height="3"
        />
      </div>
    </transition>

    <!-- 右下角浮動 API 載入膠囊 -->
    <transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="transform translate-y-3 opacity-0 scale-95"
      enter-to-class="transform translate-y-0 opacity-100 scale-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="transform translate-y-0 opacity-100 scale-100"
      leave-to-class="transform translate-y-3 opacity-0 scale-95"
    >
      <div
        v-if="isApiLoading"
        role="status"
        aria-live="polite"
        class="fixed bottom-5 right-5 z-[100000] pointer-events-none flex items-center gap-2.5 px-4 py-2.5 rounded-full bg-slate-900/90 text-white shadow-xl border border-slate-700/80 backdrop-blur-xs"
      >
        <v-progress-circular
          indeterminate
          color="primary"
          size="16"
          width="2.5"
        />
        <span class="text-xs font-semibold tracking-wide whitespace-nowrap">
          資料讀取中...
        </span>
      </div>
    </transition>
  </teleport>
</template>
