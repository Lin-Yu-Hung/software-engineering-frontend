<script setup>
import { computed, ref } from "vue";

// ==============================
// 1. RBAC 角色切換狀態 (櫃台人員 vs 場館管理員)
// ==============================
const currentRole = ref("frontdesk"); // 'frontdesk' | 'admin'

// 當前選擇日期（預設為今日 2026-09-22）
const selectedDate = ref("2026-09-22");

// 提示訊息 Snackbar
const snackbar = ref({
  show: false,
  text: "",
  color: "success",
});

let toastTimer = null;
const showToast = (text, color = "success") => {
  snackbar.value = { show: true, text, color };
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    snackbar.value.show = false;
  }, 3200);
};

// ==============================
// 2. 日期輔助工具 (防換行、星期計算與快捷切換)
// ==============================
const getWeekdayInfo = (dateStr) => {
  if (!dateStr) return { label: "", isWeekend: false };
  const [year, month, day] = dateStr.split("-").map(Number);
  const d = new Date(year, month - 1, day);
  const days = [
    "星期日",
    "星期一",
    "星期二",
    "星期三",
    "星期四",
    "星期五",
    "星期六",
  ];
  const weekday = days[d.getDay()];
  const isWeekend = d.getDay() === 0 || d.getDay() === 6;
  return {
    label: `${weekday} (${isWeekend ? "週末假日" : "平日"})`,
    isWeekend,
  };
};

const shiftDate = (deltaDays) => {
  const [year, month, day] = selectedDate.value.split("-").map(Number);
  const d = new Date(year, month - 1, day);
  d.setDate(d.getDate() + deltaDays);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const dateNum = String(d.getDate()).padStart(2, "0");
  selectedDate.value = `${y}-${m}-${dateNum}`;
};

const setQuickDate = (targetDateStr) => {
  selectedDate.value = targetDateStr;
};

// ==============================
// 3. 場地主檔與費率規則 (支援管理員自訂設定)
// ==============================
const pricingRules = ref({
  peakHourStart: 18, // 18:00 後算尖峰
  offPeakBase: 350,
  peakBase: 500,
  weekendBase: 500,
});

const courts = ref([
  {
    id: "c1",
    name: "第 1 場地",
    type: "VIP 木地板",
    peakPrice: 500,
    offPeakPrice: 350,
    enabled: true,
  },
  {
    id: "c2",
    name: "第 2 場地",
    type: "VIP 木地板",
    peakPrice: 500,
    offPeakPrice: 350,
    enabled: true,
  },
  {
    id: "c3",
    name: "第 3 場地",
    type: "標準 PU",
    peakPrice: 450,
    offPeakPrice: 300,
    enabled: true,
  },
  {
    id: "c4",
    name: "第 4 場地",
    type: "標準 PU",
    peakPrice: 450,
    offPeakPrice: 300,
    enabled: true,
  },
  {
    id: "c5",
    name: "第 5 場地",
    type: "標準 PU",
    peakPrice: 450,
    offPeakPrice: 300,
    enabled: true,
  },
  {
    id: "c6",
    name: "第 6 場地 (教學)",
    type: "訓練用 PU",
    peakPrice: 400,
    offPeakPrice: 280,
    enabled: true,
  },
]);

const activeCourts = computed(() => courts.value.filter((c) => c.enabled));

const timeSlots = [
  "08:00",
  "09:00",
  "10:00",
  "11:00",
  "12:00",
  "13:00",
  "14:00",
  "15:00",
  "16:00",
  "17:00",
  "18:00",
  "19:00",
  "20:00",
  "21:00",
];

// 判斷時段是否為尖峰 (平日指定小時後，或週末全天)
const isPeakSlot = (slot) => {
  const hour = parseInt(slot.split(":")[0], 10);
  const isWeekend = getWeekdayInfo(selectedDate.value).isWeekend;
  return isWeekend || hour >= pricingRules.value.peakHourStart;
};

// 取得場地在特定時段的價格
const getSlotPrice = (court, slot) => {
  return isPeakSlot(slot) ? court.peakPrice : court.offPeakPrice;
};

// ==============================
// 4. 多日期預約資料庫 (切換日期資料真正連動)
// ==============================
const reservationsByDate = ref({
  // 2026-09-22 (今日 / 週二)
  "2026-09-22": {
    "c1_08:00": {
      status: "booked",
      customer: "林先生",
      phone: "0912-345-678",
      payment: "online_paid",
      checkedIn: true,
      price: 350,
    },
    "c1_09:00": {
      status: "booked",
      customer: "林先生",
      phone: "0912-345-678",
      payment: "online_paid",
      checkedIn: true,
      price: 350,
    },
    "c1_19:00": {
      status: "booked",
      customer: "羽球社團 (季租)",
      phone: "0922-111-222",
      payment: "contract",
      checkedIn: false,
      price: 500,
    },
    "c1_20:00": {
      status: "booked",
      customer: "羽球社團 (季租)",
      phone: "0922-111-222",
      payment: "contract",
      checkedIn: false,
      price: 500,
    },
    "c2_10:00": {
      status: "holding",
      customer: "線上結帳中",
      phone: "系統保留鎖定",
      payment: "unpaid",
      checkedIn: false,
      price: 350,
    },
    "c2_19:00": {
      status: "booked",
      customer: "王小明 (代訂)",
      phone: "0933-888-999",
      payment: "onsite_cash",
      checkedIn: false,
      price: 500,
    },
    "c3_14:00": {
      status: "maintenance",
      customer: "例行燈具檢修",
      phone: "場館維護組",
      payment: "na",
      checkedIn: false,
      price: 0,
    },
    "c3_15:00": {
      status: "maintenance",
      customer: "例行燈具檢修",
      phone: "場館維護組",
      payment: "na",
      checkedIn: false,
      price: 0,
    },
    "c4_18:00": {
      status: "booked",
      customer: "張教練 (團課)",
      phone: "0955-666-777",
      payment: "online_paid",
      checkedIn: true,
      price: 450,
    },
    "c5_20:00": {
      status: "booked",
      customer: "陳小姐",
      phone: "0977-222-333",
      payment: "onsite_card",
      checkedIn: false,
      price: 450,
    },
  },

  // 2026-09-23 (明日 / 週三)
  "2026-09-23": {
    "c1_18:00": {
      status: "booked",
      customer: "台積電羽球社",
      phone: "0911-223-344",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c1_19:00": {
      status: "booked",
      customer: "台積電羽球社",
      phone: "0911-223-344",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c2_09:00": {
      status: "booked",
      customer: "早安羽球隊",
      phone: "0932-112-233",
      payment: "onsite_cash",
      checkedIn: false,
      price: 350,
    },
    "c2_10:00": {
      status: "booked",
      customer: "早安羽球隊",
      phone: "0932-112-233",
      payment: "onsite_cash",
      checkedIn: false,
      price: 350,
    },
    "c3_19:00": {
      status: "holding",
      customer: "線上結帳中",
      phone: "系統保留鎖定",
      payment: "unpaid",
      checkedIn: false,
      price: 450,
    },
    "c3_20:00": {
      status: "booked",
      customer: "李大同",
      phone: "0988-777-666",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c6_10:00": {
      status: "booked",
      customer: "個人特訓課",
      phone: "0966-554-433",
      payment: "online_paid",
      checkedIn: false,
      price: 280,
    },
    "c6_11:00": {
      status: "booked",
      customer: "個人特訓課",
      phone: "0966-554-433",
      payment: "online_paid",
      checkedIn: false,
      price: 280,
    },
  },

  // 2026-09-24 (後天 / 週四)
  "2026-09-24": {
    "c1_19:00": {
      status: "booked",
      customer: "季租周四固定隊",
      phone: "0910-998-877",
      payment: "contract",
      checkedIn: false,
      price: 500,
    },
    "c1_20:00": {
      status: "booked",
      customer: "季租周四固定隊",
      phone: "0910-998-877",
      payment: "contract",
      checkedIn: false,
      price: 500,
    },
    "c2_14:00": {
      status: "maintenance",
      customer: "地板打蠟維護",
      phone: "工程部",
      payment: "na",
      checkedIn: false,
      price: 0,
    },
    "c2_15:00": {
      status: "maintenance",
      customer: "地板打蠟維護",
      phone: "工程部",
      payment: "na",
      checkedIn: false,
      price: 0,
    },
    "c4_20:00": {
      status: "booked",
      customer: "科技業球聚",
      phone: "0972-334-455",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c5_18:00": {
      status: "booked",
      customer: "同樂雙打隊",
      phone: "0928-333-111",
      payment: "onsite_card",
      checkedIn: false,
      price: 450,
    },
  },

  // 2026-09-26 (週末 / 週六 尖峰熱門)
  "2026-09-26": {
    "c1_09:00": {
      status: "booked",
      customer: "週末聯賽小組賽",
      phone: "0918-112-233",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c1_10:00": {
      status: "booked",
      customer: "週末聯賽小組賽",
      phone: "0918-112-233",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c1_14:00": {
      status: "booked",
      customer: "週六同好會",
      phone: "0933-445-566",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c1_15:00": {
      status: "booked",
      customer: "週六同好會",
      phone: "0933-445-566",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c2_09:00": {
      status: "booked",
      customer: "假日晨羽隊",
      phone: "0921-334-455",
      payment: "onsite_cash",
      checkedIn: false,
      price: 500,
    },
    "c2_10:00": {
      status: "booked",
      customer: "假日晨羽隊",
      phone: "0921-334-455",
      payment: "onsite_cash",
      checkedIn: false,
      price: 500,
    },
    "c2_14:00": {
      status: "booked",
      customer: "大學校友隊",
      phone: "0987-654-321",
      payment: "online_paid",
      checkedIn: false,
      price: 500,
    },
    "c3_10:00": {
      status: "booked",
      customer: "雙打特訓班",
      phone: "0955-443-322",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c3_14:00": {
      status: "booked",
      customer: "家庭羽球組",
      phone: "0966-112-233",
      payment: "onsite_card",
      checkedIn: false,
      price: 450,
    },
    "c4_14:00": {
      status: "booked",
      customer: "球友自主揪團",
      phone: "0931-223-344",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c4_15:00": {
      status: "booked",
      customer: "球友自主揪團",
      phone: "0931-223-344",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c5_15:00": {
      status: "holding",
      customer: "線上結帳中",
      phone: "系統保留鎖定",
      payment: "unpaid",
      checkedIn: false,
      price: 450,
    },
    "c5_19:00": {
      status: "booked",
      customer: "週末夜戰隊",
      phone: "0912-888-777",
      payment: "online_paid",
      checkedIn: false,
      price: 450,
    },
    "c6_14:00": {
      status: "booked",
      customer: "青少年基礎營",
      phone: "0988-999-000",
      payment: "contract",
      checkedIn: false,
      price: 400,
    },
    "c6_15:00": {
      status: "booked",
      customer: "青少年基礎營",
      phone: "0988-999-000",
      payment: "contract",
      checkedIn: false,
      price: 400,
    },
  },
});

// 當前日期的排程映射表（響應式連動）
const currentDateSchedule = computed(() => {
  if (!reservationsByDate.value[selectedDate.value]) {
    reservationsByDate.value[selectedDate.value] = {};
  }
  return reservationsByDate.value[selectedDate.value];
});

// ==============================
// 5. 數據統計連動 (根據當前切換之日期動態計算)
// ==============================
const totalSlots = computed(() => activeCourts.value.length * timeSlots.length);

const bookedCount = computed(() => {
  return Object.values(currentDateSchedule.value).filter(
    (s) => s.status === "booked",
  ).length;
});

const checkedInCount = computed(() => {
  return Object.values(currentDateSchedule.value).filter(
    (s) => s.status === "booked" && s.checkedIn,
  ).length;
});

const maintenanceCount = computed(() => {
  return Object.values(currentDateSchedule.value).filter(
    (s) => s.status === "maintenance",
  ).length;
});

const occupancyRate = computed(() => {
  if (totalSlots.value === 0) return 0;
  return Math.round((bookedCount.value / totalSlots.value) * 100);
});

// ==============================
// 6. 點擊棋盤格彈窗 (時段詳情 / 劃位)
// ==============================
const slotDialogActive = ref(false);
const selectedSlotInfo = ref(null);

const slotBookingForm = ref({
  customer: "",
  phone: "",
  paymentMethod: "onsite_cash",
  notes: "",
});

const onSlotClick = (court, slot) => {
  const key = `${court.id}_${slot}`;
  const existing = currentDateSchedule.value[key];

  selectedSlotInfo.value = {
    key,
    courtId: court.id,
    courtName: court.name,
    slot,
    price: getSlotPrice(court, slot),
    record: existing || null,
  };

  if (!existing) {
    slotBookingForm.value = {
      customer: "",
      phone: "",
      paymentMethod: "onsite_cash",
      notes: "",
    };
  }
  slotDialogActive.value = true;
};

// 彈窗內建立劃位
const confirmSlotBooking = () => {
  if (!slotBookingForm.value.customer || !slotBookingForm.value.phone) {
    alert("請填寫預約人姓名與聯絡電話");
    return;
  }
  const key = selectedSlotInfo.value.key;
  currentDateSchedule.value[key] = {
    status: "booked",
    customer: slotBookingForm.value.customer,
    phone: slotBookingForm.value.phone,
    payment: slotBookingForm.value.paymentMethod,
    checkedIn: false,
    price: selectedSlotInfo.value.price,
  };
  slotDialogActive.value = false;
  showToast(
    `已成功為 ${slotBookingForm.value.customer} 劃位 ${selectedSlotInfo.value.courtName} (${selectedSlotInfo.value.slot})`,
  );
};

// 報到核銷 (Check-in)
const toggleCheckIn = () => {
  if (selectedSlotInfo.value?.record) {
    const isNowChecked = !selectedSlotInfo.value.record.checkedIn;
    selectedSlotInfo.value.record.checkedIn = isNowChecked;
    slotDialogActive.value = false;
    showToast(
      isNowChecked ? "報到核銷完成！" : "已取消報到狀態",
      isNowChecked ? "success" : "info",
    );
  }
};

// 取消預約 / 釋放時段
const cancelSlot = () => {
  if (
    confirm(
      `確定要取消 ${selectedSlotInfo.value.courtName} ${selectedSlotInfo.value.slot} 的預約嗎？`,
    )
  ) {
    delete currentDateSchedule.value[selectedSlotInfo.value.key];
    slotDialogActive.value = false;
    showToast("預約已取消並釋出該時段", "warning");
  }
};

// 維護封場切換 (管理員限定)
const toggleMaintenance = () => {
  const key = selectedSlotInfo.value.key;
  if (selectedSlotInfo.value.record?.status === "maintenance") {
    delete currentDateSchedule.value[key];
    showToast("已解除維修封場", "info");
  } else {
    currentDateSchedule.value[key] = {
      status: "maintenance",
      customer: "場地維修封場",
      phone: "管理員排程",
      payment: "na",
      checkedIn: false,
      price: 0,
    };
    showToast("已設定為維護封場", "warning");
  }
  slotDialogActive.value = false;
};

// ==============================
// 7. 「快速臨櫃代訂」專用彈窗
// ==============================
const quickBookingDialog = ref(false);

const quickForm = ref({
  courtId: "c1",
  slot: "18:00",
  customer: "",
  phone: "",
  paymentMethod: "onsite_cash",
  notes: "",
});

// 開啟快速臨櫃代訂
const openQuickBookingDialog = () => {
  let defaultCourt = activeCourts.value[0]?.id || "c1";
  let defaultSlot = "18:00";
  for (const c of activeCourts.value) {
    const freeSlot = timeSlots.find(
      (s) => !currentDateSchedule.value[`${c.id}_${s}`],
    );
    if (freeSlot) {
      defaultCourt = c.id;
      defaultSlot = freeSlot;
      break;
    }
  }

  quickForm.value = {
    courtId: defaultCourt,
    slot: defaultSlot,
    customer: "",
    phone: "",
    paymentMethod: "onsite_cash",
    notes: "",
  };
  quickBookingDialog.value = true;
};

const quickSelectedPrice = computed(() => {
  const court = courts.value.find((c) => c.id === quickForm.value.courtId);
  if (!court) return 400;
  return getSlotPrice(court, quickForm.value.slot);
});

const submitQuickBooking = () => {
  if (!quickForm.value.customer.trim()) {
    alert("請輸入預約人姓名或稱謂");
    return;
  }
  if (!quickForm.value.phone.trim()) {
    alert("請輸入聯絡電話");
    return;
  }

  const key = `${quickForm.value.courtId}_${quickForm.value.slot}`;
  const existing = currentDateSchedule.value[key];

  if (existing) {
    alert(`該時段已有預約（${existing.customer}），請改選其他時段或場地！`);
    return;
  }

  const court = courts.value.find((c) => c.id === quickForm.value.courtId);
  currentDateSchedule.value[key] = {
    status: "booked",
    customer: quickForm.value.customer.trim(),
    phone: quickForm.value.phone.trim(),
    payment: quickForm.value.paymentMethod,
    checkedIn: false,
    price: quickSelectedPrice.value,
  };

  quickBookingDialog.value = false;
  showToast(
    `臨櫃劃位成功！[${court?.name}] ${quickForm.value.slot} 預約人：${quickForm.value.customer}`,
  );
};

// ==============================
// 8. 「場地與費率設定」管理面板 (完整功能實作)
// ==============================
const courtPricingDialog = ref(false);
const settingsTab = ref("courts"); // 'courts' | 'pricing'

// 編輯中的場地暫存
const editingCourts = ref([]);
const editingPricing = ref({ ...pricingRules.value });

const openCourtPricingSettings = () => {
  // 深拷貝當前場地與費率資料供編輯
  editingCourts.value = JSON.parse(JSON.stringify(courts.value));
  editingPricing.value = { ...pricingRules.value };
  settingsTab.value = "courts";
  courtPricingDialog.value = true;
};

// 新增一個場地
const addNewCourt = () => {
  const nextNum = editingCourts.value.length + 1;
  editingCourts.value.push({
    id: `c${Date.now()}`,
    name: `第 ${nextNum} 場地`,
    type: "標準 PU",
    peakPrice: editingPricing.value.peakBase,
    offPeakPrice: editingPricing.value.offPeakBase,
    enabled: true,
  });
};

// 移除一個場地
const removeCourt = (index) => {
  if (editingCourts.value.length <= 1) {
    alert("場館至少需保留 1 個場地");
    return;
  }
  editingCourts.value.splice(index, 1);
};

// 儲存場地與費率設定
const saveCourtPricingSettings = () => {
  courts.value = JSON.parse(JSON.stringify(editingCourts.value));
  pricingRules.value = { ...editingPricing.value };
  courtPricingDialog.value = false;
  showToast("場地與費率設定已成功儲存更新！");
};
</script>

<template>
  <div class="max-w-7xl mx-auto px-6 py-6 w-full space-y-6">
    <!-- 頂部控制列：模式切換、日期選擇、快捷操作 -->
    <div
      class="bg-white rounded-xl border border-slate-200 p-5 shadow-xs flex flex-col lg:flex-row lg:items-center justify-between gap-5"
    >
      <div class="flex items-center gap-5 flex-wrap">
        <!-- RBAC 角色切換 -->
        <div class="shrink-0">
          <span
            class="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1 whitespace-nowrap"
          >
            當前操作模式 (RBAC 角色)
          </span>
          <div
            class="inline-flex rounded-lg border border-slate-200 p-1 bg-slate-50 shrink-0"
          >
            <button
              type="button"
              :class="[
                'flex items-center gap-2 px-3.5 py-1.5 rounded-md text-xs font-semibold transition-all whitespace-nowrap',
                currentRole === 'frontdesk'
                  ? 'bg-emerald-600 text-white shadow-xs'
                  : 'text-slate-600 hover:text-slate-900',
              ]"
              @click="currentRole = 'frontdesk'"
            >
              <v-icon icon="mdi-desk" size="16"></v-icon>
              櫃台人員模式
            </button>
            <button
              type="button"
              :class="[
                'flex items-center gap-2 px-3.5 py-1.5 rounded-md text-xs font-semibold transition-all whitespace-nowrap',
                currentRole === 'admin'
                  ? 'bg-emerald-600 text-white shadow-xs'
                  : 'text-slate-600 hover:text-slate-900',
              ]"
              @click="currentRole = 'admin'"
            >
              <v-icon icon="mdi-shield-crown" size="16"></v-icon>
              場館管理員模式
            </button>
          </div>
        </div>

        <div class="h-10 w-px bg-slate-200 hidden lg:block"></div>

        <!-- 日期選擇區（絕不換行） -->
        <div class="shrink-0">
          <span
            class="text-xs font-semibold text-slate-400 uppercase tracking-wider block mb-1 whitespace-nowrap"
          >
            預約排程日期
          </span>
          <div class="flex items-center gap-2 flex-nowrap whitespace-nowrap">
            <!-- 前一天 -->
            <button
              type="button"
              class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 hover:text-slate-900 transition-colors shadow-2xs shrink-0"
              title="切換至前一天"
              @click="shiftDate(-1)"
            >
              <v-icon icon="mdi-chevron-left" size="18"></v-icon>
            </button>

            <!-- 日期選擇器本體 -->
            <input
              type="date"
              v-model="selectedDate"
              class="border border-slate-200 rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white shadow-2xs focus:outline-emerald-500 whitespace-nowrap shrink-0 w-36"
            />

            <!-- 後一天 -->
            <button
              type="button"
              class="w-8 h-8 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-center text-slate-600 hover:text-slate-900 transition-colors shadow-2xs shrink-0"
              title="切換至後一天"
              @click="shiftDate(1)"
            >
              <v-icon icon="mdi-chevron-right" size="18"></v-icon>
            </button>

            <!-- 星期徽章 -->
            <v-chip
              size="small"
              variant="flat"
              :color="
                getWeekdayInfo(selectedDate).isWeekend
                  ? 'amber-100'
                  : 'slate-100'
              "
              :class="[
                '!px-3 font-semibold whitespace-nowrap shrink-0',
                getWeekdayInfo(selectedDate).isWeekend
                  ? 'text-amber-800'
                  : 'text-slate-600',
              ]"
            >
              <v-icon
                :icon="
                  getWeekdayInfo(selectedDate).isWeekend
                    ? 'mdi-star'
                    : 'mdi-calendar-today'
                "
                size="14"
                class="mr-1"
              ></v-icon>
              {{ getWeekdayInfo(selectedDate).label }}
            </v-chip>

            <!-- 快捷日期按鈕 (防換行) -->
            <div class="hidden sm:flex items-center gap-1.5 shrink-0 pl-1">
              <button
                type="button"
                :class="[
                  'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                  selectedDate === '2026-09-22'
                    ? 'bg-emerald-50 border-emerald-300 text-emerald-700 font-bold'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                ]"
                @click="setQuickDate('2026-09-22')"
              >
                今天 (9/22)
              </button>
              <button
                type="button"
                :class="[
                  'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                  selectedDate === '2026-09-23'
                    ? 'bg-emerald-50 border-emerald-300 text-emerald-700 font-bold'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                ]"
                @click="setQuickDate('2026-09-23')"
              >
                明天 (9/23)
              </button>
              <button
                type="button"
                :class="[
                  'text-xs px-2.5 py-1 rounded-md border font-medium transition-colors whitespace-nowrap',
                  selectedDate === '2026-09-26'
                    ? 'bg-amber-50 border-amber-300 text-amber-800 font-bold'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50',
                ]"
                @click="setQuickDate('2026-09-26')"
              >
                週六 (熱門)
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 快捷動作按鈕 -->
      <div class="flex items-center gap-2 flex-wrap shrink-0">
        <v-btn
          color="primary"
          variant="flat"
          prepend-icon="mdi-account-plus"
          rounded="lg"
          size="small"
          class="!px-4 font-semibold shadow-xs"
          @click="openQuickBookingDialog"
        >
          快速臨櫃代訂
        </v-btn>

        <!-- 管理員專用：場地與費率設定 (完整有作用) -->
        <v-btn
          v-if="currentRole === 'admin'"
          color="secondary"
          variant="tonal"
          prepend-icon="mdi-tune-variant"
          rounded="lg"
          size="small"
          class="!px-4 font-semibold"
          @click="openCourtPricingSettings"
        >
          場地與費率設定
        </v-btn>
      </div>
    </div>

    <!-- 數據概覽卡片 (與當前切換之日期即時連動) -->
    <div class="grid grid-cols-2 sm:grid-cols-4 gap-4">
      <div
        class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
      >
        <div
          class="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center shrink-0"
        >
          <v-icon icon="mdi-percent" size="20"></v-icon>
        </div>
        <div>
          <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
            今日場地使用率
          </p>
          <p class="text-lg font-bold text-slate-800">{{ occupancyRate }}%</p>
        </div>
      </div>

      <div
        class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
      >
        <div
          class="w-10 h-10 rounded-lg bg-blue-50 text-blue-600 flex items-center justify-center shrink-0"
        >
          <v-icon icon="mdi-calendar-check" size="20"></v-icon>
        </div>
        <div>
          <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
            已預約時段數
          </p>
          <p class="text-lg font-bold text-slate-800">
            {{ bookedCount }}
            <span class="text-xs text-slate-400 font-normal"
              >/ {{ totalSlots }}</span
            >
          </p>
        </div>
      </div>

      <div
        class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
      >
        <div
          class="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center shrink-0"
        >
          <v-icon icon="mdi-qrcode-scan" size="20"></v-icon>
        </div>
        <div>
          <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
            現場已報到核銷
          </p>
          <p class="text-lg font-bold text-slate-800">
            {{ checkedInCount }}
            <span class="text-xs text-slate-400 font-normal">場次</span>
          </p>
        </div>
      </div>

      <div
        class="bg-white rounded-xl border border-slate-200 p-4 shadow-2xs flex items-center gap-3"
      >
        <div
          class="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center shrink-0"
        >
          <v-icon icon="mdi-wrench-clock" size="20"></v-icon>
        </div>
        <div>
          <p class="text-xs text-slate-400 font-medium whitespace-nowrap">
            場地維護時段
          </p>
          <p class="text-lg font-bold text-slate-800">
            {{ maintenanceCount }}
            <span class="text-xs text-slate-400 font-normal">小時</span>
          </p>
        </div>
      </div>
    </div>

    <!-- 核心：每日排程棋盤看板 (Timetable Grid) -->
    <div
      class="bg-white rounded-xl border border-slate-200 shadow-xs overflow-hidden"
    >
      <!-- 看板標頭與圖例說明 -->
      <div
        class="p-5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
      >
        <div>
          <h2
            class="text-base font-bold text-slate-800 flex items-center gap-2"
          >
            <v-icon icon="mdi-table-clock" color="primary" size="20"></v-icon>
            即時排程棋盤圖 — {{ selectedDate }}
          </h2>
          <p class="text-xs text-slate-400 mt-0.5">
            點選空白時段可快速代訂；點選已預約時段可進行「核銷報到」、「收款備註」或「退款取消」
          </p>
        </div>

        <!-- 狀態圖例 -->
        <div class="flex items-center gap-2 flex-wrap shrink-0">
          <v-chip
            size="small"
            variant="flat"
            color="emerald-50"
            class="!px-3 text-emerald-700 font-medium border border-emerald-200 whitespace-nowrap"
          >
            <span class="w-2 h-2 rounded-full bg-emerald-500 mr-1.5"></span>
            可預約
          </v-chip>
          <v-chip
            size="small"
            variant="flat"
            color="blue-50"
            class="!px-3 text-blue-700 font-medium border border-blue-200 whitespace-nowrap"
          >
            <span class="w-2 h-2 rounded-full bg-blue-500 mr-1.5"></span> 已預約
          </v-chip>
          <v-chip
            size="small"
            variant="flat"
            color="amber-50"
            class="!px-3 text-amber-700 font-medium border border-amber-200 whitespace-nowrap"
          >
            <span class="w-2 h-2 rounded-full bg-amber-500 mr-1.5"></span>
            鎖定中 (TTL)
          </v-chip>
          <v-chip
            size="small"
            variant="flat"
            color="rose-50"
            class="!px-3 text-rose-700 font-medium border border-rose-200 whitespace-nowrap"
          >
            <span class="w-2 h-2 rounded-full bg-rose-500 mr-1.5"></span>
            維護封場
          </v-chip>
        </div>
      </div>

      <!-- 橫向滾動排程網格 (寬度擴展至 min-w-[136px]，徹底解決格子內換行問題) -->
      <div class="overflow-x-auto">
        <table class="w-full border-collapse text-left text-xs">
          <thead>
            <tr
              class="bg-slate-50 border-b border-slate-200 text-slate-600 font-semibold"
            >
              <th
                class="p-3 w-36 min-w-[140px] sticky left-0 bg-slate-50 z-10 border-r border-slate-200 whitespace-nowrap"
              >
                場地資訊
              </th>
              <!-- 時段欄寬度加大為 min-w-[136px]，確保內部兩段文字絕不折行 -->
              <th
                v-for="slot in timeSlots"
                :key="slot"
                class="p-2.5 text-center min-w-[136px] border-r border-slate-100 font-medium whitespace-nowrap"
              >
                <div class="font-bold text-slate-700">{{ slot }}</div>
                <div class="text-3xs font-normal text-slate-400 mt-0.5">
                  {{ isPeakSlot(slot) ? "尖峰時段" : "離峰時段" }}
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr
              v-for="court in activeCourts"
              :key="court.id"
              class="hover:bg-slate-50/50 transition-colors"
            >
              <!-- 左側場地名稱 (Sticky) -->
              <td
                class="p-3 sticky left-0 bg-white z-10 border-r border-slate-200 font-medium whitespace-nowrap"
              >
                <div class="font-bold text-slate-800">{{ court.name }}</div>
                <div class="text-2xs text-slate-400 mt-0.5">
                  {{ court.type }}
                </div>
              </td>

              <!-- 各時段棋盤格 (文字加上 whitespace-nowrap 徹底防止換行) -->
              <td
                v-for="slot in timeSlots"
                :key="slot"
                class="p-1 border-r border-slate-100 text-center align-middle"
              >
                <!-- 1. 已預約狀態卡片 (高度與寬度舒適，單行絕不折行) -->
                <button
                  v-if="
                    currentDateSchedule[`${court.id}_${slot}`]?.status ===
                    'booked'
                  "
                  type="button"
                  class="w-full h-15 rounded-lg p-2 text-left transition-all border flex flex-col justify-between bg-blue-50/90 border-blue-200 hover:border-blue-400 hover:shadow-2xs select-none"
                  @click="onSlotClick(court, slot)"
                >
                  <div class="flex items-center justify-between gap-1 w-full">
                    <span
                      class="font-bold text-blue-950 truncate whitespace-nowrap text-xs"
                    >
                      {{ currentDateSchedule[`${court.id}_${slot}`].customer }}
                    </span>
                    <v-icon
                      v-if="
                        currentDateSchedule[`${court.id}_${slot}`].checkedIn
                      "
                      icon="mdi-check-circle"
                      color="success"
                      size="15"
                      class="shrink-0"
                      title="已到場報到"
                    ></v-icon>
                  </div>
                  <div
                    class="flex items-center justify-between text-2xs font-medium w-full whitespace-nowrap"
                  >
                    <span class="text-blue-700 whitespace-nowrap shrink-0">
                      {{
                        currentDateSchedule[`${court.id}_${slot}`].payment ===
                        "online_paid"
                          ? "線上已付"
                          : currentDateSchedule[`${court.id}_${slot}`]
                                .payment === "contract"
                            ? "季租合約"
                            : "現場收費"
                      }}
                    </span>
                    <span
                      v-if="
                        currentDateSchedule[`${court.id}_${slot}`].checkedIn
                      "
                      class="text-emerald-700 font-bold whitespace-nowrap shrink-0"
                    >
                      已報到
                    </span>
                    <span
                      v-else
                      class="text-slate-400 whitespace-nowrap shrink-0"
                    >
                      未報到
                    </span>
                  </div>
                </button>

                <!-- 2. 線上結帳暫存鎖 (Holding TTL) -->
                <button
                  v-else-if="
                    currentDateSchedule[`${court.id}_${slot}`]?.status ===
                    'holding'
                  "
                  type="button"
                  class="w-full h-15 rounded-lg p-2 text-left border bg-amber-50/90 border-amber-200 hover:border-amber-400 flex flex-col justify-between select-none"
                  @click="onSlotClick(court, slot)"
                >
                  <span
                    class="text-xs font-bold text-amber-900 truncate whitespace-nowrap"
                    >線上結帳中</span
                  >
                  <div
                    class="flex items-center gap-1 text-2xs text-amber-700 whitespace-nowrap"
                  >
                    <v-icon icon="mdi-timer-sand" size="13"></v-icon>
                    <span class="whitespace-nowrap">暫存鎖定中</span>
                  </div>
                </button>

                <!-- 3. 維護封場 (Maintenance) -->
                <button
                  v-else-if="
                    currentDateSchedule[`${court.id}_${slot}`]?.status ===
                    'maintenance'
                  "
                  type="button"
                  class="w-full h-15 rounded-lg p-2 text-left border bg-rose-50/90 border-rose-200 hover:border-rose-400 flex flex-col justify-between select-none"
                  @click="onSlotClick(court, slot)"
                >
                  <span
                    class="text-xs font-bold text-rose-900 whitespace-nowrap truncate"
                    >維修封場</span
                  >
                  <span class="text-2xs text-rose-600 whitespace-nowrap"
                    >暫停預約</span
                  >
                </button>

                <!-- 4. 可預約空檔 -->
                <button
                  v-else
                  type="button"
                  class="w-full h-15 rounded-lg border border-dashed border-slate-200 hover:border-emerald-400 hover:bg-emerald-50/50 transition-all flex flex-col items-center justify-center text-slate-300 hover:text-emerald-600 group select-none"
                  @click="onSlotClick(court, slot)"
                >
                  <v-icon
                    icon="mdi-plus"
                    size="18"
                    class="group-hover:scale-110 transition-transform"
                  ></v-icon>
                  <span
                    class="text-2xs text-slate-400 group-hover:text-emerald-700 font-medium whitespace-nowrap"
                    >空場代訂</span
                  >
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============================================== -->
    <!-- 彈窗 A：時段詳情與管理 (點選棋盤格時彈出) -->
    <!-- ============================================== -->
    <v-dialog v-model="slotDialogActive" max-width="520">
      <v-card rounded="xl" class="overflow-hidden">
        <div
          class="bg-slate-900 text-white p-5 flex items-center justify-between"
        >
          <div>
            <h3 class="text-base font-bold flex items-center gap-2">
              <v-icon icon="mdi-badminton" color="primary" size="20"></v-icon>
              {{ selectedSlotInfo?.courtName }} ({{ selectedSlotInfo?.slot }})
            </h3>
            <p class="text-xs text-slate-400 mt-0.5">
              預約排程日期：{{ selectedDate }}
            </p>
          </div>
          <v-btn
            icon="mdi-close"
            variant="text"
            size="small"
            color="grey-lighten-1"
            @click="slotDialogActive = false"
          ></v-btn>
        </div>

        <v-card-text class="p-6 space-y-5">
          <!-- A1. 既有預約詳情處理 -->
          <div v-if="selectedSlotInfo?.record" class="space-y-4">
            <div
              class="bg-slate-50 rounded-xl p-4 border border-slate-200 space-y-2 text-sm"
            >
              <div class="flex justify-between">
                <span class="text-slate-500">預約客戶：</span>
                <span class="font-bold text-slate-800">{{
                  selectedSlotInfo.record.customer
                }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-slate-500">聯絡電話：</span>
                <span class="font-medium text-slate-700">{{
                  selectedSlotInfo.record.phone
                }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-slate-500">時段費率：</span>
                <span class="font-bold text-emerald-600"
                  >${{ selectedSlotInfo.record.price }} / 小時</span
                >
              </div>
              <div class="flex justify-between">
                <span class="text-slate-500">收費狀態：</span>
                <span class="font-medium text-slate-700">
                  {{
                    selectedSlotInfo.record.payment === "online_paid"
                      ? "綠界線上刷卡 (已付款)"
                      : selectedSlotInfo.record.payment === "onsite_cash"
                        ? "現場現金付款"
                        : selectedSlotInfo.record.payment === "onsite_card"
                          ? "現場刷卡付款"
                          : selectedSlotInfo.record.payment === "contract"
                            ? "季租合約款"
                            : "尚未付款"
                  }}
                </span>
              </div>
              <div
                class="flex justify-between items-center pt-2 border-t border-slate-200"
              >
                <span class="text-slate-500">入場出席狀態：</span>
                <v-chip
                  :color="
                    selectedSlotInfo.record.checkedIn ? 'success' : 'grey'
                  "
                  size="small"
                  variant="flat"
                  class="!px-3 font-semibold"
                >
                  {{
                    selectedSlotInfo.record.checkedIn
                      ? "已核銷報到"
                      : "尚未報到"
                  }}
                </v-chip>
              </div>
            </div>

            <div class="flex gap-2 flex-wrap">
              <v-btn
                :color="selectedSlotInfo.record.checkedIn ? 'grey' : 'success'"
                variant="flat"
                rounded="lg"
                class="flex-1 !px-4 font-semibold"
                :prepend-icon="
                  selectedSlotInfo.record.checkedIn
                    ? 'mdi-undo'
                    : 'mdi-check-all'
                "
                @click="toggleCheckIn"
              >
                {{
                  selectedSlotInfo.record.checkedIn
                    ? "取消報到狀態"
                    : "櫃台確認報到 (Check-in)"
                }}
              </v-btn>

              <v-btn
                color="error"
                variant="outlined"
                rounded="lg"
                class="!px-4 font-semibold"
                prepend-icon="mdi-trash-can-outline"
                @click="cancelSlot"
              >
                取消預約/釋出
              </v-btn>
            </div>
          </div>

          <!-- A2. 空白時段：快速劃位表單 -->
          <div v-else class="space-y-4">
            <div
              class="p-3 bg-emerald-50 border border-emerald-200 rounded-lg text-xs text-emerald-800 flex justify-between items-center"
            >
              <span
                >此時段費用預估：<strong
                  >${{ selectedSlotInfo?.price }} / hr</strong
                ></span
              >
              <v-chip
                size="x-small"
                color="primary"
                variant="flat"
                class="!px-2"
              >
                {{
                  isPeakSlot(selectedSlotInfo?.slot) ? "尖峰時段" : "離峰時段"
                }}
              </v-chip>
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >預約人姓名 / 稱謂</label
              >
              <input
                v-model="slotBookingForm.customer"
                type="text"
                placeholder="例如: 王小明"
                class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
              />
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >聯絡電話</label
              >
              <input
                v-model="slotBookingForm.phone"
                type="tel"
                placeholder="例如: 0912-345-678"
                class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
              />
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >現場收費方式</label
              >
              <select
                v-model="slotBookingForm.paymentMethod"
                class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500 bg-white"
              >
                <option value="onsite_cash">現場現金付款</option>
                <option value="onsite_card">現場刷卡付款</option>
                <option value="unpaid">尚未付款 (入場再付)</option>
              </select>
            </div>

            <div class="pt-2 flex gap-2">
              <v-btn
                color="primary"
                variant="flat"
                rounded="lg"
                class="flex-1 !px-4 font-semibold"
                prepend-icon="mdi-check"
                @click="confirmSlotBooking"
              >
                建立現場劃位
              </v-btn>

              <v-btn
                v-if="currentRole === 'admin'"
                color="warning"
                variant="tonal"
                rounded="lg"
                class="!px-4 font-semibold"
                prepend-icon="mdi-wrench"
                @click="toggleMaintenance"
              >
                維護封場
              </v-btn>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

    <!-- ============================================== -->
    <!-- 彈窗 B：「快速臨櫃代訂」專用表單 (自選場地與時段) -->
    <!-- ============================================== -->
    <v-dialog v-model="quickBookingDialog" max-width="540">
      <v-card rounded="xl" class="overflow-hidden">
        <div
          class="bg-emerald-700 text-white p-5 flex items-center justify-between"
        >
          <div class="flex items-center gap-3">
            <div
              class="w-9 h-9 rounded-lg bg-emerald-600 flex items-center justify-center"
            >
              <v-icon icon="mdi-account-plus" size="22"></v-icon>
            </div>
            <div>
              <h3 class="text-base font-bold">快速臨櫃代訂劃位</h3>
              <p class="text-xs text-emerald-100 mt-0.5">
                登記預約日期：{{ selectedDate }}
              </p>
            </div>
          </div>
          <v-btn
            icon="mdi-close"
            variant="text"
            size="small"
            color="white"
            @click="quickBookingDialog = false"
          ></v-btn>
        </div>

        <v-card-text class="p-6 space-y-4">
          <!-- 場地與時段選擇網格 -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >預約場地</label
              >
              <select
                v-model="quickForm.courtId"
                class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-emerald-500 bg-white"
              >
                <option v-for="c in activeCourts" :key="c.id" :value="c.id">
                  {{ c.name }} ({{ c.type }})
                </option>
              </select>
            </div>

            <div>
              <label class="text-xs font-semibold text-slate-700 block mb-1"
                >預約時段 (1小時)</label
              >
              <select
                v-model="quickForm.slot"
                class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-emerald-500 bg-white"
              >
                <option v-for="s in timeSlots" :key="s" :value="s">
                  {{ s }}
                  {{
                    currentDateSchedule[`${quickForm.courtId}_${s}`]
                      ? "(已被訂)"
                      : "(空場可訂)"
                  }}
                </option>
              </select>
            </div>
          </div>

          <!-- 費用即時估算 Banner -->
          <div
            class="bg-emerald-50 border border-emerald-200 rounded-lg p-3 flex items-center justify-between text-xs"
          >
            <span class="text-emerald-800">
              預計收費：<strong class="text-base text-emerald-700"
                >${{ quickSelectedPrice }}</strong
              >
              元
            </span>
            <v-chip
              size="x-small"
              color="primary"
              variant="flat"
              class="!px-2 font-semibold"
            >
              {{ isPeakSlot(quickForm.slot) ? "尖峰費率" : "離峰費率" }}
            </v-chip>
          </div>

          <!-- 顧客姓名 -->
          <div>
            <label class="text-xs font-semibold text-slate-700 block mb-1"
              >預約人姓名 / 稱謂 <span class="text-rose-500">*</span></label
            >
            <input
              v-model="quickForm.customer"
              type="text"
              placeholder="例如: 郭先生 / 蔡教練"
              class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
            />
          </div>

          <!-- 聯絡電話 -->
          <div>
            <label class="text-xs font-semibold text-slate-700 block mb-1"
              >聯絡電話 <span class="text-rose-500">*</span></label
            >
            <input
              v-model="quickForm.phone"
              type="tel"
              placeholder="例如: 0988-123-456"
              class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
            />
          </div>

          <!-- 收費方式 -->
          <div>
            <label class="text-xs font-semibold text-slate-700 block mb-1"
              >收費方式</label
            >
            <select
              v-model="quickForm.paymentMethod"
              class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500 bg-white"
            >
              <option value="onsite_cash">現場現金付款</option>
              <option value="onsite_card">現場信用卡付款</option>
              <option value="unpaid">尚未付款 (打球前再結帳)</option>
            </select>
          </div>

          <!-- 備註說明 -->
          <div>
            <label class="text-xs font-semibold text-slate-700 block mb-1"
              >備註說明 (選填)</label
            >
            <input
              v-model="quickForm.notes"
              type="text"
              placeholder="例如: 需要租借球拍 2 支"
              class="w-full border border-slate-200 rounded-lg px-3.5 py-2 text-sm focus:outline-emerald-500"
            />
          </div>

          <div class="pt-3 border-t border-slate-100 flex gap-2">
            <v-btn
              variant="outlined"
              color="grey"
              rounded="lg"
              class="!px-4 font-medium"
              @click="quickBookingDialog = false"
            >
              取消
            </v-btn>
            <v-btn
              color="primary"
              variant="flat"
              rounded="lg"
              class="flex-1 !px-4 font-semibold"
              prepend-icon="mdi-check-circle"
              @click="submitQuickBooking"
            >
              確認建立劃位
            </v-btn>
          </div>
        </v-card-text>
      </v-card>
    </v-dialog>

    <!-- ============================================== -->
    <!-- 彈窗 C：「場地與費率設定」管理面板 (全面實作) -->
    <!-- ============================================== -->
    <v-dialog v-model="courtPricingDialog" max-width="720">
      <v-card rounded="xl" class="overflow-hidden">
        <!-- 標頭 -->
        <div
          class="bg-slate-900 text-white p-5 flex items-center justify-between"
        >
          <div class="flex items-center gap-3">
            <div
              class="w-9 h-9 rounded-lg bg-slate-800 flex items-center justify-center"
            >
              <v-icon
                icon="mdi-tune-variant"
                color="primary"
                size="22"
              ></v-icon>
            </div>
            <div>
              <h3 class="text-base font-bold">場地與費率營運設定</h3>
              <p class="text-xs text-slate-400 mt-0.5">
                場館管理員專屬設定 (RBAC Admin)
              </p>
            </div>
          </div>
          <v-btn
            icon="mdi-close"
            variant="text"
            size="small"
            color="grey-lighten-1"
            @click="courtPricingDialog = false"
          ></v-btn>
        </div>

        <!-- 頁籤切換 -->
        <div class="flex border-b border-slate-200 bg-slate-50 px-6">
          <button
            type="button"
            :class="[
              'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-2 whitespace-nowrap',
              settingsTab === 'courts'
                ? 'border-emerald-500 text-emerald-600 bg-white'
                : 'border-transparent text-slate-500 hover:text-slate-800',
            ]"
            @click="settingsTab = 'courts'"
          >
            <v-icon icon="mdi-badminton" size="16"></v-icon>
            場地主檔管理 ({{ editingCourts.length }} 個場地)
          </button>
          <button
            type="button"
            :class="[
              'py-3 px-4 text-xs font-bold border-b-2 transition-colors flex items-center gap-2 whitespace-nowrap',
              settingsTab === 'pricing'
                ? 'border-emerald-500 text-emerald-600 bg-white'
                : 'border-transparent text-slate-500 hover:text-slate-800',
            ]"
            @click="settingsTab = 'pricing'"
          >
            <v-icon icon="mdi-currency-usd" size="16"></v-icon>
            尖離峰費率引擎規則
          </button>
        </div>

        <v-card-text class="p-6 max-h-[65vh] overflow-y-auto space-y-5">
          <!-- 頁籤 1: 場地主檔管理 -->
          <div v-if="settingsTab === 'courts'" class="space-y-4">
            <div class="flex items-center justify-between">
              <p class="text-xs text-slate-500">
                可自由增刪場地、修改名稱、規格材質與個別時段定價：
              </p>
              <v-btn
                color="primary"
                size="small"
                variant="tonal"
                prepend-icon="mdi-plus"
                rounded="lg"
                class="!px-3 font-semibold"
                @click="addNewCourt"
              >
                新增場地
              </v-btn>
            </div>

            <div class="space-y-3">
              <div
                v-for="(court, idx) in editingCourts"
                :key="court.id"
                class="bg-slate-50 rounded-xl border border-slate-200 p-4 space-y-3"
              >
                <div class="flex items-center justify-between gap-3">
                  <div class="flex items-center gap-2">
                    <span
                      class="w-6 h-6 rounded-full bg-slate-200 text-slate-700 text-xs font-bold flex items-center justify-center"
                    >
                      {{ idx + 1 }}
                    </span>
                    <input
                      v-model="court.name"
                      type="text"
                      class="border border-slate-300 rounded-lg px-2.5 py-1 text-xs font-bold text-slate-800 bg-white focus:outline-emerald-500"
                    />
                  </div>

                  <div class="flex items-center gap-2">
                    <label
                      class="text-xs text-slate-500 flex items-center gap-1 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        v-model="court.enabled"
                        class="rounded text-emerald-600"
                      />
                      <span>開放預約</span>
                    </label>
                    <v-btn
                      icon="mdi-delete-outline"
                      size="x-small"
                      variant="text"
                      color="error"
                      @click="removeCourt(idx)"
                      title="刪除此場地"
                    ></v-btn>
                  </div>
                </div>

                <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1 text-xs">
                  <div>
                    <label class="text-slate-400 block mb-0.5"
                      >場地材質規格</label
                    >
                    <select
                      v-model="court.type"
                      class="w-full border border-slate-200 rounded-md px-2 py-1 text-xs bg-white"
                    >
                      <option value="VIP 木地板">VIP 木地板</option>
                      <option value="標準 PU">標準 PU</option>
                      <option value="訓練用 PU">訓練用 PU</option>
                      <option value="國際比賽地墊">國際比賽地墊</option>
                    </select>
                  </div>

                  <div>
                    <label class="text-slate-400 block mb-0.5"
                      >離峰費率 ($/hr)</label
                    >
                    <input
                      v-model.number="court.offPeakPrice"
                      type="number"
                      class="w-full border border-slate-200 rounded-md px-2 py-1 text-xs bg-white font-semibold text-emerald-600"
                    />
                  </div>

                  <div>
                    <label class="text-slate-400 block mb-0.5"
                      >尖峰費率 ($/hr)</label
                    >
                    <input
                      v-model.number="court.peakPrice"
                      type="number"
                      class="w-full border border-slate-200 rounded-md px-2 py-1 text-xs bg-white font-semibold text-rose-600"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 頁籤 2: 尖離峰費率規則引擎 -->
          <div v-else-if="settingsTab === 'pricing'" class="space-y-5">
            <div
              class="p-4 bg-emerald-50 border border-emerald-200 rounded-xl text-xs text-emerald-800 space-y-1"
            >
              <p class="font-bold flex items-center gap-1">
                <v-icon icon="mdi-information-outline" size="16"></v-icon>
                差別定價規則說明
              </p>
              <p class="text-emerald-700">
                系統根據所設定之平日尖峰起始時段自動判斷；週末（週六、日）全天自動以假日尖峰計價。
              </p>
            </div>

            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div
                class="bg-white p-4 rounded-xl border border-slate-200 space-y-2"
              >
                <label class="text-xs font-bold text-slate-800 block"
                  >平日尖峰時段起始時間</label
                >
                <select
                  v-model.number="editingPricing.peakHourStart"
                  class="w-full border border-slate-200 rounded-lg px-3 py-2 text-xs bg-white focus:outline-emerald-500"
                >
                  <option :value="17">17:00 後算尖峰</option>
                  <option :value="18">18:00 後算尖峰 (預設推薦)</option>
                  <option :value="19">19:00 後算尖峰</option>
                </select>
                <p class="text-2xs text-slate-400">
                  目前設定：{{ editingPricing.peakHourStart }}:00
                  之後的預約時段將套用尖峰價格。
                </p>
              </div>

              <div
                class="bg-white p-4 rounded-xl border border-slate-200 space-y-2"
              >
                <label class="text-xs font-bold text-slate-800 block"
                  >週末假日計價基準</label
                >
                <div class="flex items-center gap-2">
                  <input
                    v-model.number="editingPricing.weekendBase"
                    type="number"
                    class="w-full border border-slate-200 rounded-lg px-3 py-2 text-xs bg-white font-bold text-slate-800 focus:outline-emerald-500"
                  />
                  <span class="text-xs text-slate-500 shrink-0">元/hr</span>
                </div>
                <p class="text-2xs text-slate-400">
                  週末 (六、日) 全天營業時段均以此基準計算。
                </p>
              </div>
            </div>
          </div>
        </v-card-text>

        <!-- 彈窗底部按鈕列 -->
        <div
          class="p-5 border-t border-slate-200 bg-slate-50 flex items-center justify-between"
        >
          <span class="text-xs text-slate-400"
            >修改將即時反映於今日看板與預約計算中</span
          >
          <div class="flex gap-2">
            <v-btn
              variant="outlined"
              color="grey"
              rounded="lg"
              class="!px-4 font-medium"
              @click="courtPricingDialog = false"
            >
              取消
            </v-btn>
            <v-btn
              color="primary"
              variant="flat"
              rounded="lg"
              class="!px-5 font-bold"
              prepend-icon="mdi-content-save-check"
              @click="saveCourtPricingSettings"
            >
              儲存營運設定
            </v-btn>
          </div>
        </div>
      </v-card>
    </v-dialog>

    <!-- 操作提示 Toast (自製優雅浮動通知，解決 Vuetify Snackbar 跑版與邊界貼合問題) -->
    <teleport to="body">
      <transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="transform -translate-y-4 opacity-0 scale-95"
        enter-to-class="transform translate-y-0 opacity-100 scale-100"
        leave-active-class="transition duration-200 ease-in"
        leave-from-class="transform translate-y-0 opacity-100 scale-100"
        leave-to-class="transform -translate-y-4 opacity-0 scale-95"
      >
        <div
          v-if="snackbar.show"
          class="fixed top-6 right-6 z-[99999] flex items-center gap-3.5 px-5 py-3.5 rounded-xl shadow-2xl text-white font-medium text-sm pointer-events-auto border"
          :class="[
            snackbar.color === 'success'
              ? 'bg-emerald-600 border-emerald-400/50 shadow-emerald-950/25'
              : snackbar.color === 'warning'
                ? 'bg-amber-600 border-amber-400/50 shadow-amber-950/25'
                : snackbar.color === 'error'
                  ? 'bg-rose-600 border-rose-400/50 shadow-rose-950/25'
                  : 'bg-slate-800 border-slate-600 shadow-slate-950/25',
          ]"
        >
          <!-- 圓形背景 Icon：充足內距、完美居中，不再貼邊 -->
          <div
            class="w-6 h-6 rounded-full bg-white/20 flex items-center justify-center shrink-0"
          >
            <v-icon
              :icon="
                snackbar.color === 'success'
                  ? 'mdi-check'
                  : snackbar.color === 'warning'
                    ? 'mdi-alert'
                    : snackbar.color === 'error'
                      ? 'mdi-close-circle'
                      : 'mdi-information'
              "
              size="16"
              color="white"
            ></v-icon>
          </div>

          <span
            class="whitespace-nowrap font-bold tracking-wide text-xs sm:text-sm"
          >
            {{ snackbar.text }}
          </span>

          <button
            type="button"
            class="ml-2 w-5 h-5 rounded-full flex items-center justify-center text-white/70 hover:text-white hover:bg-white/20 transition-colors shrink-0"
            @click="snackbar.show = false"
            title="關閉提示"
          >
            <v-icon icon="mdi-close" size="14"></v-icon>
          </button>
        </div>
      </transition>
    </teleport>
  </div>
</template>
