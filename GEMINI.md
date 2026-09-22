# 羽球場館線上預約系統 - Gemini / Antigravity 指南

本專案之核心架構與業務規範定義於 [AGENTS.md](AGENTS.md) 與 [REQUIREMENTS.md](REQUIREMENTS.md)。

## 核心守則摘要
1. **統一後台架構**：櫃台端與管理端共用同一套後台 (`/admin`)，以 RBAC 角色切換功能，嚴禁重複開發兩套後台 UI。
2. **排程棋盤圖**：為系統核心看板，即時反映各場地小時時段狀態（可預約、暫鎖中、已預約、維修中）。
3. **預約防撞機制**：進入結帳付款時需有 5~10 分鐘 TTL 暫存鎖 (Hold lock)，避免超賣。
4. **金流與退款政策**：串接綠界 ECPay，支援階梯式取消退款規則。
5. **程式碼風格**：Vue 3 Composition API `<script setup>`，元件職責分離。

