# 羽球場館線上預約系統 - AI 協作規範與專案背景 (AGENTS.md)

本文件專門提供給 AI Coding Assistant (如 Antigravity, Claude Code, Cursor, Copilot 等) 讀取，以確保在開發、維護與擴充本專案時遵循統一的架構設計與業務邏輯規範。

---

## 1. 專案核心業務背景 (Domain Knowledge)

本專案為「**羽球場館線上預約系統 (Badminton Court Online Reservation System)**」，核心目標是消除電話預約溝通成本、即時呈現空場、預收費用防止 No-Show，並自動化執行取消退款。

### 關鍵業務規則 (Business Rules)
1. **統一後台架構 (Unified Backoffice with RBAC)**：
   * **櫃台端與管理端不是兩個獨立系統，而是同一套場館後台**。
   * 請勿重複開發兩套後台 UI。共用核心「每日排程棋盤看板 (Timetable Grid)」，依角色權限 (RBAC) 啟用功能：
     * **櫃台人員 (Front Desk)**：即時棋盤圖查看、現場/電話代訂劃位、現場收費記帳（現金/現場刷卡）、掃描 QR Code 報到核銷 (Check-in)。
     * **場館管理員 (Admin)**：包含櫃台所有功能，另有場地增刪、營業時段、尖離峰差別定價、維修封場排程、退款政策階梯設定與營運報表。
2. **預約防撞與暫時鎖定 (Hold Lock / Concurrency Control)**：
   * 會員選取時段進入金流付款階段時，系統必須暫時鎖定該時段（例如 5~10 分鐘 TTL）。
   * 付款成功正式轉為 Confirmed；逾期未付自動釋出，防止多人搶同一時段造成超賣。
3. **金流與階梯退款 (Payment & Refund Gateway)**：
   * 主要串接台灣在地金流：**綠界科技 (ECPay)** 信用卡/行動支付。
   * 取消預約時依場館自訂階梯規則（例如 3 天前 100% 退款、1~2 天前 50%、當天不退款）自動或半自動退款。
4. **防呆與零碎時段規範**：
   * 預約與劃位基本單位預設為 1 小時（整點~整點），避免產生無法出租的畸零時段。

> 詳細需求規格請參閱專案根目錄的 [REQUIREMENTS.md](REQUIREMENTS.md)。

---

## 2. 系統架構與技術棧 (Architecture & Tech Stack)

* **前端 (Current Workspace)**：
  * **Framework**: Vue 3 (`<script setup>`, Composition API)
  * **Build Tool**: Vite
  * **State Management**: Pinia (預約暫存、購物車、使用者驗證)
  * **Router**: Vue Router (分前台顧客路由、後台管理員/櫃台路由)
  * **Styling**: Tailwind CSS 或主流 Vue UI 元件庫 (如 Element Plus / Naive UI)
* **後端與資料庫 (規劃中)**：
  * **API 風格**: RESTful API
  * **資料庫**: 關聯式資料庫 (PostgreSQL / MySQL)，強調交易原子性 (ACID)
  * **快取與併發鎖**: Redis (時段暫時鎖定 TTL)
  * **第三方串接**: 綠界科技 ECPay SDK

---

## 3. 程式碼規範與開發準則 (Coding Guidelines for AI)

1. **Vue 3 規範**：
   * 一律採用 `<script setup>` 語法糖與 TypeScript/JavaScript Composition API。
   * 保持元件單一職責（Single Responsibility），排程棋盤格、時段卡片、訂單彈窗應適當抽離成獨立元件。
2. **前後台路由權限隔離**：
   * 前台：`/` (首頁/場地日曆), `/booking` (結帳預約), `/my-bookings` (個人訂單)
   * 後台：`/admin` 或 `/backoffice`
     * 需配置導航守衛 (Navigation Guards) 檢驗 JWT Token 與角色權限。
3. **避免假設與破壞性改動**：
   * 擴充功能前應優先參閱 `REQUIREMENTS.md`。
   * 修改現有程式碼時，保留既有註解與有效邏輯。
   * 涉及金流、取消金額計算時，請務必加入精確金額檢查與邊界測試（Boundary Condition Testing）。

