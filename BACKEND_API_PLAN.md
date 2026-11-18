# 羽球場館預約系統：後端 API 與架構規劃

分析日期：2026-10-04。技術要求：Spring Boot、Java、PostgreSQL。

交付範圍已調整為課程作業的簡單 demo：保留已完成 API，後續優先完成預約、訂單查詢、基本取消／代訂及與金流同學整合。下列完整系統規劃作為參考，改期審核、進階報表等延伸功能不列為必做。目前只在本機 commit，未經使用者另外要求不 push 遠端。

第四階段已完成會員 hold／訂單／整單取消、防撞、TTL、自有資料權限與營業占用保護；日曆已改為 LIVE。金流同學可呼叫 PaymentConfirmationService 確認已驗證交易。實際合約見 [backend/API_BOOKING.md](backend/API_BOOKING.md)。作業 demo 採 PostgreSQL 交易與到期時間，不另建 Redis。

第五階段已完成共用每日棋盤、現場／電話代訂、訂單聯絡資料更新、手動報到／撤銷及維修封場。櫃台代訂為 CONFIRMED／UNPAID，實際收款由金流同學串接。合約與操作見 [backend/API_BACKOFFICE.md](backend/API_BACKOFFICE.md)。

第六階段提供 ADMIN 專用且可關閉的 DEMO 付款確認、完整 Postman 展示及同學交接。你負責的簡單 demo 範圍已完成，實際 ECPay、收款／退款仍由金流同學製作。示範入口見 [backend/DEMO_GUIDE.md](backend/DEMO_GUIDE.md)。以下大規模功能清單是參考規劃，以已實作 API 文件與 demo 範圍為準。

本文件描述完整目標規劃。第一至第五階段的實際合約與操作請見 [backend/README.md](backend/README.md)、[backend/API_PHASE3.md](backend/API_PHASE3.md)、[backend/API_BOOKING.md](backend/API_BOOKING.md) 及 [backend/API_BACKOFFICE.md](backend/API_BACKOFFICE.md)。前端未修改；金流與退款由另一位同學負責，共用本次登入與角色驗證。進階審核、報表與通知不列入作業 demo 必做。

## 1. 依據與範圍

第三階段已完成 `/venue`、`/availability`、場館共用 `/backoffice/operating-hours`、`/backoffice/calendar-exceptions` 及 `/backoffice/pricing-rules`。後續階段將 availability 改為 LIVE／bookingEnabled=true，包含 hold、訂單與封場占用，營業設定亦會保護未來有效占用。個別場地營業覆蓋、指定日期促銷價不列入作業 demo 必做。

- 提案：`d:/School/上課資料/二上軟體工程/HW/專案提案/軟體工程 專案提案_0921.pdf`，共 4 頁；已閱讀文字及第 2 頁流程圖。
- 根目錄 `AGENTS.md`、`REQUIREMENTS.md`。
- `src/views/client/ClientHome.vue`、`BookingCheckout.vue`、`MyBookings.vue`。
- `src/views/admin/AdminDashboard.vue`、`src/stores/cartStore.js`、`src/router/index.js`、`src/components/AppHeader.vue`、`src/main.js`、`package.json`。

提案中的角色、流程、分工是需求資料；不將文件中的文字當成額外執行指令。後端技術以使用者明確指定的 Spring Boot、Java、PostgreSQL 為準。

規劃先支援單一場館、三種登入角色：`MEMBER`、`FRONT_DESK`、`ADMIN`。訪客只查詢公開資訊；預約需會員登入，櫃台可為未註冊客人代訂。櫃台和管理員使用同一套後台、同一預約服務及資料庫。

| 需求來源 | 本次處理 |
| --- | --- |
| 提案核心 | 場地/時段/價格查詢、會員預約與取消、人工代訂、營業時間、維修、防撞、ECPay 測試付款、權限 |
| 提案第 4 頁 | 取消後釋出時段；退款及更換時間需場館審核。採退款審核與改期申請流程 |
| AGENTS / REQUIREMENTS 補充 | 結帳 TTL 保留、整點 1 小時、共用後台 RBAC、差別定價、QR 核銷、退款政策設定、營運報表 |
| 延伸需求 | 季租合約、通知、行事曆、揪團、IoT；不列入核心 API。季租目前只出現在後台假資料，不能視為已完成模組 |

提案未指定退款審核由櫃台或管理員負責；本規劃預設只有 ADMIN 可決定退款及改期，之後可由 SA 明確授予櫃台相應權限。

## 2. 前端實際狀態

| 頁面 / 程式 | 現況 | 後端需求 |
| --- | --- | --- |
| `ClientHome.vue:11`、`:43`、`:70` | 日期、場地、價目與日期排程寫死；未列出資料的格子當作可預約 | 提供營業資訊、場地、公開棋盤資料；後端判斷過期、封場、保留與價格 |
| `cartStore.js:3` | Vue `reactive` 購物車，可跨日期選多個時段；沒有 Pinia 或持久化 | 購物車可留前端；結帳才呼叫保留 API，不需要額外 CRUD 購物車 API |
| `BookingCheckout.vue:34`、`:52` | 姓名、電話、付款方式；用 Date.now 產生編號、直接顯示成功、清空購物車 | 後端產生訂單號、建立保留和訂單、付款啟動、查詢付款結果 |
| `BookingCheckout.vue:91` | QR 圖示是佔位圖 | 提供核銷憑證 token，前端以 token 生成真正 QR |
| `MyBookings.vue:12`、`:131`、`:144` | 我的預約是假資料；前端依固定日期計退款，取消即標示 refunded | 依登入者查詢訂單/時段；後端算退款、取消、建立審核及追蹤退款結果 |
| `AdminDashboard.vue:7` | 角色由按鈕手動切換 | 登入身分決定角色；所有寫入在後端驗權 |
| `AdminDashboard.vue:565`、`:677` | 兩個代訂入口，只寫入畫面記憶體 | 共用一個代訂 API 與預約防撞交易 |
| `AdminDashboard.vue:585`、`:599`、`:612` | 報到切換布林值、取消直接刪除資料、維修直接覆寫格子 | 報到/撤銷紀錄、保留取消歷史、維修不得覆蓋已預約時段 |
| `AdminDashboard.vue:533`、`:636` | 表單有 notes，建立紀錄時沒有保存 | 訂單 DTO 與資料庫需保存 notes |
| `AdminDashboard.vue:69`、`:154`、`:752` | 設定只在後台頁記憶體；weekendBase 欄位未用於 getSlotPrice | 統一後端定價；新增設定不能回頭更改已成立訂單價格 |
| `AdminDashboard.vue:502` | 使用率用所有場地 × 固定時段算分母 | 後端依當天實際營業/封場回傳統計與計算口徑 |
| `router/index.js:57` | 守衛只改 document.title，沒有登入/角色檢查 | 前端補守衛，後端仍獨立檢查 JWT、角色與訂單所有權 |

整個 `src` 沒有 fetch、axios 或 XMLHttpRequest 呼叫；`package.json` 未安裝 Pinia。前台、我的預約、後台各自維護假資料，並未共用真正資料來源。營業時間、退款審核、改期申請、財務報表尚未有完整操作頁面。

### 需與前端/SA 對齊的規則

1. 顧客結帳目前可選現場現金/刷卡，提案希望線上預收以降低 No-Show。本規劃會員預約先只允許線上信用卡；現場付款留給櫃台。若決定開放會員到場付款，需另訂保留期限、收款期限及未到處理規則。
2. `payment` 混合方式與結果，例如 online_paid、onsite_cash、pending、refunded、contract。API 必須拆成付款方式、付款狀態、退款狀態；contract 是預約來源/合約類型。
3. 前台可多時段結帳，我的預約卻是一列一小時。採「一張 order 多個 booking_items」，列表可攤平 item，但每列帶 orderId、bookingItemId。
4. 退款政策的「3 天前」尚未定義日曆日或實際 72 小時。暫採 Asia/Taipei 日曆日，對應現有 UI；72/24 小時是另一種規則，不混用。政策應明示計算基準及整數取整規則。
5. QR 核銷、固定日期、到場時間窗、可提前預約天數、最少保留一場地等，需要後端驗證，不依賴畫面限制。

## 3. API 共通合約

- Base URL：`/api/v1`。下列路徑省略此前綴；`/admin` 是 Vue 頁面，`/backoffice` 是後端業務 API 命名空間。
- 一般請求/回應採 JSON camelCase；時間 ISO 8601 含 offset，例如 `2026-10-10T18:00:00+08:00`；日期 `YYYY-MM-DD`。
- 所有業務日曆以 `Asia/Taipei` 計算；資料庫事件時間使用 `timestamptz`，Java 使用 Instant / OffsetDateTime。營業日/星期/時段以 LocalDate / LocalTime 表示。
- 金額以 TWD 整數元表示，Java `long`、PostgreSQL `bigint`；百分比計算用 BigDecimal 及明示 RoundingMode。不得使用 float/double。
- API 不接受前端作為價格、實收、退款金額或付款成功的權威。只收場地與時段等輸入，由後端計算。
- 會員身份從 SecurityContext 取得，不相信 body 中的 memberId / role。
- 登入後 `Authorization: Bearer <accessToken>`；refresh token 建議 HttpOnly cookie、輪替且可撤銷。使用 cookie 的 refresh/logout 須有 CSRF 與 Origin 檢查；限制 CORS 為實際前端來源。
- 建立 hold、order、payment、代訂、取消、退款決策等可重試 POST 接受 `Idempotency-Key`。同使用者/操作/key/相同 body 回傳原結果，不同 body 回 409；資料庫保存 request hash 與結果。
- 訂單列表分頁：page、size、sort；限制 size 上限。公開棋盤只含狀態/價格，不含姓名、電話、token、內部原因或其他會員訂單識別。
- 查詢通常 200；新增 201；長時間退款處理 202；無回應內容的刪除 204。
- 錯誤：400 格式/欄位錯誤、401 未登入、403 角色不足、404 找不到或無權查看的資源、409 時段衝突/版本過期/價格異動、410 hold 逾期、422 不可執行的業務規則、429 頻率限制。

錯誤 JSON 範例：

```json
{
  "code": "SLOT_CONFLICT",
  "message": "部分時段已被預約，請重新選擇",
  "conflicts": [{"courtId": "c1", "startAt": "2026-10-10T18:00:00+08:00"}],
  "traceId": "request-trace-id"
}
```

## 4. API 清單

標記：**核心**為既有 UI 串接或提案必要流程；**補充**為需求文件要求、UI 待補；**後續**為可延後管理能力。ADMIN 包含 FRONT_DESK 的日常作業權限。

### 4.1 會員與驗證

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| POST | `/auth/register` | 公開 | email、password、name、phone；建立會員，不接受角色 | 核心 |
| POST | `/auth/login` | 公開 | email/password；accessToken、expiresIn、角色，設定 refresh cookie | 核心 |
| POST | `/auth/refresh` | refresh cookie | 輪替 refresh token 與 access token | 核心 |
| POST | `/auth/logout` | refresh cookie + 必要標頭 | 撤銷當次 session、清 cookie；該 session 的 access token 立即失效 | 已實作 |
| GET | `/me` | 登入者 | 會員/員工資料、角色、權限 | 核心 |
| PATCH | `/me` | 登入者 | 更新姓名/電話，email 與角色不可任意修改 | 補充 |

MVP 可透過 migration/seed 建立員工帳號；若要在系統管理員工，再補 ADMIN 專用 `/backoffice/staff` 帳號管理 API。忘記密碼與 email 驗證另依專案範圍安排。

### 4.2 公開場地、營業資訊與棋盤

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| GET | `/venue` | 公開 | 場館名稱、timezone、slotMinutes、可提前預約日數、公開付款方式 | 核心 |
| GET | `/courts` | 公開 | 啟用場地 id/name/type；選填 courtId 篩選 | 核心 |
| GET | `/availability?date=YYYY-MM-DD` | 公開 | 當天營業時段、各場地每小時狀態/價格、serverTime、updatedAt | 核心 |
| GET | `/cancellation-policy` | 公開 | 人類可讀政策、計算基準、級距、費用、生效版本 | 核心 |

棋盤狀態：`AVAILABLE`、`HOLDING`、`BOOKED`、`MAINTENANCE`、`CLOSED`、`PAST`。日期選擇器和下一天/上一天不需要獨立 API；每次 date 改變重查 availability 即可。完整棋盤要包含不能預約的格子，不能用「缺資料＝有空場」。

### 4.3 保留時段、訂單與付款

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| POST | `/holds` | MEMBER | items[{courtId,startAt}]；原子保留全部時段、回 holdId/expiresAt/項目價格/totalAmount/policyVersion | 核心 |
| GET | `/holds/{holdId}` | 擁有者 | 保留明細、狀態與剩餘期限，供重整結帳頁恢復 | 核心 |
| DELETE | `/holds/{holdId}` | 擁有者 | 釋放尚未啟動付款的 hold；付款啟動後使用訂單取消流程 | 核心 |
| POST | `/orders` | MEMBER | holdId、customer{name,phone}、paymentMethod；回 orderId/orderNumber/items/amount/status/expiresAt | 核心 |
| GET | `/orders?scope=upcoming\|past\|all` | MEMBER | 只查本人訂單，分頁；每筆含多個 booking_items | 核心 |
| GET | `/orders/{orderId}` | 擁有者 | 訂單/明細、付款、退款進度、可執行操作 | 核心 |
| POST | `/orders/{orderId}/credentials` | 擁有者 | 為有效已確認預約簽發核銷 token、expiresAt；前端產生真正 QR | 補充 |
| POST | `/orders/{orderId}/payments` | 擁有者 | 建立 ECPay 交易；回 paymentId、formAction、formMethod、簽署過的 formFields | 核心 |
| GET | `/payments/{paymentId}` | 擁有者 | PENDING/SUCCEEDED/FAILED/UNKNOWN 等結果，不得只相信導回頁 | 核心 |
| POST | `/payments/ecpay/notify` | ECPay 簽章驗證 | ReturnURL 接受 form-urlencoded；持久化事件/處理結果後回純文字 `1|OK` | 核心 |

建立 order 會消耗 hold 一次，但不延長原 expiresAt；原先的時段占用移交給 PENDING_PAYMENT 訂單，仍保留同一期限。每張訂單只允許一筆未確定結果的付款嘗試；網路重試用相同 paymentId/交易編號，不能任意產生第二次扣款。

ECPay 商品先採「全方位金流 AIO、信用卡一次付清」，不要混用站內付等產品的參數/URL。額外支付工具另行評估產品支援與商戶合約。

`notify` 不驗證會員 JWT；它必須檢查 CheckMacValue、MerchantID、MerchantTradeNo、TradeAmt、成功碼與環境，保存交易編號。正式環境不能把 SimulatePaid 當真實付款。瀏覽器付款導回只導向結果頁，結果頁重新查 order/payment。

### 4.4 取消、退款與改期申請

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| POST | `/orders/{orderId}/cancellation-quotes` | 擁有者 | bookingItemIds（可部分取消）；回逐項退款額、合計、quoteId、版本、expiresAt | 核心 |
| POST | `/orders/{orderId}/cancellations` | 擁有者 | quoteId、reason；取消指定時段、釋出占用、建立退款待審紀錄；回 cancellationId/refundId/status | 核心 |
| GET | `/refunds/{refundId}` | 擁有者 | 審核/執行/失敗/完成進度及核准額 | 核心 |
| POST | `/orders/{orderId}/reschedule-requests` | 擁有者 | 原 bookingItemId、目標 courtId/startAt、reason；提出改期申請，保留原時段 | 核心（前端待補） |
| GET | `/orders/{orderId}/reschedule-requests` | 擁有者 | 本訂單改期申請結果 | 核心（前端待補） |

退款試算不能直接作為執行金額。取消交易內重算，並檢查 quote 是否過期、項目狀態/實收/政策快照是否一致；跨級距或狀態改變回 409，要求重新確認。保存取消時間與試算快照，日後審核不以審核當日重新降低退款比例。

已付款會員取消：時段先釋出、booking item 為 CANCELLED，refund 為 PENDING_REVIEW。管理員拒絕退款只改退款結果，不把取消的時段復活。未付款取消、退款額 0，狀態為 NOT_REQUIRED，不呼叫金流，也不能顯示 REFUNDED。

改期提案只簡述「更換時間需審核」，本規劃以申請/審核補全。申請不永久占用目標時段；核准時重新檢查並在同一交易換位，目標已占用則拒絕/請改選，原預約不動。MVP 先限同額改期；不同金額需補款/退款流程，未完成前回 PRICE_DIFFERENCE_REQUIRES_PAYMENT，不能只改資料留下帳差。

### 4.5 共用後台：櫃台與管理員

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| GET | `/backoffice/timetable?date=YYYY-MM-DD` | FRONT_DESK / ADMIN | 完整棋盤；含顧客聯絡資訊、bookingItemId/orderId、付款/報到狀態、統計 | 核心 |
| GET | `/backoffice/orders` | FRONT_DESK / ADMIN | date/from/to、orderNumber、customer、phone、status、paymentStatus，分頁查詢 | 核心 |
| GET | `/backoffice/orders/{orderId}` | FRONT_DESK / ADMIN | 訂單詳情、備註、付款、取消及核銷紀錄 | 核心 |
| POST | `/backoffice/orders` | FRONT_DESK / ADMIN | 多個 items、顧客、來源 WALK_IN/PHONE、notes、現場付款安排；同一防撞服務 | 核心 |
| PATCH | `/backoffice/orders/{orderId}` | FRONT_DESK / ADMIN | customer/phone/notes、expectedVersion；不得直接改金額、時段或付款成功狀態 | 核心 |
| POST | `/backoffice/orders/{orderId}/payments` | FRONT_DESK / ADMIN | method CASH/ONSITE_CARD/BANK_TRANSFER、amount、收款證據/備註；建立收款紀錄 | 核心 |
| POST | `/backoffice/check-ins` | FRONT_DESK / ADMIN | credentialToken 或 orderNumber + bookingItemIds；報到驗證與結果 | 補充 / UI 目前手動報到 |
| DELETE | `/backoffice/check-ins/{checkInId}` | FRONT_DESK / ADMIN | reason、撤銷核銷並記錄操作者；冪等，不物理刪除歷史 | 核心 |
| POST | `/backoffice/orders/{orderId}/cancellation-quotes` | FRONT_DESK / ADMIN | 代客試算，與會員使用相同政策引擎 | 核心 |
| POST | `/backoffice/orders/{orderId}/cancellations` | FRONT_DESK / ADMIN | quoteId/reason；取消後保留歷史、進入適當退款流程 | 核心 |
| GET | `/backoffice/refunds?status=PENDING_REVIEW` | ADMIN | 退款審核/執行列表，分頁 | 核心（前端待補） |
| POST | `/backoffice/refunds/{refundId}/decisions` | ADMIN | APPROVE/REJECT、reason、expectedVersion；核准後排入退款工作 | 核心（前端待補） |
| POST | `/backoffice/refunds/{refundId}/retry` | ADMIN | 先查金流狀態再重試已確認未成功的退款；UNKNOWN 不盲目重刷 | 補充 |
| GET | `/backoffice/reschedule-requests` | ADMIN | 依待審/結果查詢 | 核心（前端待補） |
| POST | `/backoffice/reschedule-requests/{requestId}/decisions` | ADMIN | APPROVE/REJECT、reason、expectedVersion；核准交易重新檢查換位 | 核心（前端待補） |

現場未付款可「CONFIRMED 預約 + UNPAID 付款」，因為有櫃台承諾保留；與會員的限時 PENDING_PAYMENT 不同。建立代訂時只有明確傳入收款紀錄才表示已付；單選 onsite_cash 不能自動當作已收現金。MVP 收款金額須等於尚欠全額，不先開部分付款能力。櫃台不能任意標記 ECPay 線上已付。

QR 憑證使用高熵隨機 token 或有簽章/到期的 token，不能僅用可猜測訂單編號。可在簽發時只保存 token hash，token 明文只回傳此次；重新取得時重新簽發並撤銷舊憑證。核銷在登入員工的 context 下檢查 booking status、報到時間窗、付款/現場收款要求與是否已核銷；訂單編號手動查詢也只供員工。相同核銷重試回原結果；撤銷記 reason/actor/time。

### 4.6 管理員營運設定

| 方法 | 路徑 | 權限 | 輸入 / 回傳 | 範圍 |
| --- | --- | --- | --- | --- |
| GET | `/backoffice/courts` | ADMIN | 全部場地，含停用與 version、排序、材質 | 核心 |
| GET | `/backoffice/courts/{courtId}` | ADMIN | 單一場地，包含退役資料與 version | 已實作 |
| POST | `/backoffice/courts` | ADMIN | name/type/status 等；id 由後端產生 | 核心 |
| PATCH | `/backoffice/courts/{courtId}` | ADMIN | 修改資料/啟停用、expectedVersion | 核心 |
| DELETE | `/backoffice/courts/{courtId}?expectedVersion=0` | ADMIN | 已實作版本檢查與退役/軟刪；未來訂單模組需補上有效占用則 409 的檢查 | 已部分實作 |
| GET | `/backoffice/operating-hours` | ADMIN | 每週各日/場地營業區間與版本 | 核心（前端待補） |
| PUT | `/backoffice/operating-hours` | ADMIN | 完整每週營業設定、expectedVersion；整點驗證、不得排除既有預約 | 核心（前端待補） |
| GET | `/backoffice/calendar-exceptions?from=...&to=...` | ADMIN | 休館/假日/特殊營業日期；可依場地覆寫 | 核心（前端待補） |
| POST | `/backoffice/calendar-exceptions` | ADMIN | 日期、營業/休館、HOLIDAY 分類、適用場地 | 核心（前端待補） |
| PATCH | `/backoffice/calendar-exceptions/{exceptionId}` | ADMIN | 修改例外日設定與版本 | 核心（前端待補） |
| DELETE | `/backoffice/calendar-exceptions/{exceptionId}` | ADMIN | 刪除例外設定並驗證既有占用 | 核心（前端待補） |
| GET | `/backoffice/pricing-rules` | ADMIN | 場地/日期類型/尖離峰費率、優先序、版本 | 核心 |
| PUT | `/backoffice/pricing-rules` | ADMIN | 原子儲存完整規則集合、expectedVersion，拒絕同優先序重疊 | 核心 |
| GET | `/backoffice/blocks?from=...&to=...` | ADMIN | 維修、活動、保留封場清單 | 核心 |
| POST | `/backoffice/blocks` | ADMIN | courtIds、startAt/endAt、reason、type；全部成功或全部失敗 | 核心 |
| DELETE | `/backoffice/blocks/{blockId}` | ADMIN | 解除封場，留存操作歷史 | 核心 |
| GET | `/backoffice/cancellation-policy` | ADMIN | 全部退款級距、審核要求、計算基準、版本 | 核心（前端待補） |
| PUT | `/backoffice/cancellation-policy` | ADMIN | 儲存不重疊且覆蓋全部合法期限的級距與費用 | 核心（前端待補） |
| GET | `/backoffice/reports/occupancy?from=...&to=...` | ADMIN | 每日/各場地/尖離峰利用率，回傳分子分母與計算基準 | 補充 |
| GET | `/backoffice/reports/revenue?from=...&to=...` | ADMIN | 實收/退款/費用/淨額，按收付發生日期及方式匯總 | 補充 |

現有「場地與費率」單一儲存按鈕可由前端呼叫上述 API。若要跨場地主檔和價目一起成功/一起失敗，可加 `POST /backoffice/operating-settings/batches`，傳明確 courtOperations + pricingRules + expectedVersions，由一個 transaction 驗證和儲存，不能用刪掉全部再重建的方式更新。

定價暫採「指定日期例外 > 假日規則 > 場地平日尖離峰 > 場館預設」；國定假日透過維護的日曆例外標示，不能只用週六/日判斷。週末共用價與各場地尖峰價的先後順序需在規則中明訂。

統計先將 `totalSlots` 定義為該日營業區間中的場地小時格（含維修）；`sellableSlots = totalSlots - maintenance/blockedSlots`；利用率採有效已預約格 / sellableSlots，保留格不算已成交。`checkedInSlotCount` 和 `checkInCount` 分別表示核銷時段格及核銷次數，避免一筆兩小時預約被誤認為兩位客人。

### 4.7 可延後的 API

- ADMIN 員工帳號、角色指派、停用帳號與稽核查詢。
- 固定隊/季租合約和批次排程；必須使用相同 slot inventory 防撞。
- Email/SMS/LINE 成功通知、提醒、退款結果；先選定服務與實際通知需求，再訂 API。
- 財報 CSV 匯出、ICS 行事曆、WebSocket/SSE 即時棋盤。MVP 先定期重查棋盤及操作後刷新。
- 揪團、器材租借、燈光控制，不因 mock notes 或 REQUIREMENTS 的延伸建議就增加核心工作量。

## 5. 關鍵 DTO 範例

### 5.1 公開棋盤

```json
{
  "date": "2026-10-10",
  "timezone": "Asia/Taipei",
  "serverTime": "2026-10-04T15:00:00+08:00",
  "slotMinutes": 60,
  "courts": [{"id": "c1", "name": "第 1 場地", "type": "VIP 木地板"}],
  "slots": [
    {"courtId": "c1", "startAt": "2026-10-10T18:00:00+08:00", "endAt": "2026-10-10T19:00:00+08:00", "status": "AVAILABLE", "price": 500, "priceType": "HOLIDAY"},
    {"courtId": "c1", "startAt": "2026-10-10T19:00:00+08:00", "endAt": "2026-10-10T20:00:00+08:00", "status": "BOOKED", "price": 500, "priceType": "HOLIDAY"}
  ]
}
```

### 5.2 保留多個時段

```json
{
  "items": [
    {"courtId": "c1", "startAt": "2026-10-10T18:00:00+08:00"},
    {"courtId": "c1", "startAt": "2026-10-10T19:00:00+08:00"}
  ]
}
```

回傳：

```json
{
  "holdId": "hold-uuid",
  "expiresAt": "2026-10-04T15:10:00+08:00",
  "serverTime": "2026-10-04T15:00:00+08:00",
  "currency": "TWD",
  "totalAmount": 1000,
  "items": [
    {"courtId": "c1", "startAt": "2026-10-10T18:00:00+08:00", "endAt": "2026-10-10T19:00:00+08:00", "unitPrice": 500},
    {"courtId": "c1", "startAt": "2026-10-10T19:00:00+08:00", "endAt": "2026-10-10T20:00:00+08:00", "unitPrice": 500}
  ],
  "pricingVersion": 1,
  "cancellationPolicyVersion": 1
}
```

每個 item 固定一小時，跨日/不連續時段仍為獨立 item。不得重複 item；限制一次保留數量和每會員有效 hold 數量，避免不付款占位。TTL 暫採 10 分鐘，供場館配置。

### 5.3 建立訂單

```json
{
  "holdId": "hold-uuid",
  "customer": {"name": "王小明", "phone": "0912345678"},
  "paymentMethod": "ONLINE_CARD"
}
```

只回 PENDING_PAYMENT，不等同完成付款；totalAmount、orderNumber、memberId 由後端決定。前端保存 orderId，在付款結果頁查詢訂單；若付款流程已開始，返回修改時段需先取消該付款中的訂單，再建立新保留，不能直接改已簽署金額。

前端需先展示 holds 回傳的權威價格，再讓會員確認訂單；不能以舊購物車價格直接進入扣款。

## 6. Spring Boot 後端架構

採模組化單體：一個 Spring Boot application、一個 PostgreSQL。初期沒有拆微服務的必要。

```text
Vue 3
  -> REST Controllers / Request DTO / Bean Validation
  -> Spring Security (JWT、RBAC、所有權)
  -> Application Services (@Transactional 的業務交易)
  -> Domain (定價、取消、狀態轉換、防撞)
  -> Repositories (JPA + 必要的 SQL 資料列鎖)
  -> PostgreSQL

PaymentGateway interface -> EcpayGateway / FakePaymentGateway
Outbox + Scheduled Workers -> 退款執行、付款對帳、到期清理
```

| 技術 | 用途 |
| --- | --- |
| Java 21 | 專案共用 JDK；實際 Boot 版本以官方支援相容組合選定 |
| Spring Boot + Maven | 應用配置、依賴管理與部署；採建立當時受維護的穩定版本 |
| Spring Web MVC | REST Controller / JSON / ECPay form callback |
| Spring Security | JWT 驗證、登入、RBAC、method security、密碼雜湊 |
| Spring Data JPA / Hibernate | 一般 CRUD、實體關聯、@Version；關鍵鎖定查詢用明確 SQL |
| Jakarta Bean Validation | 姓名/電話、整點、日期、數量、金額、級距驗證 |
| PostgreSQL driver | 正式資料庫連線 |
| Flyway | 版本化 schema、唯一鍵、索引、seed migration |
| OpenAPI / Swagger UI | 前後端合約與測試；文件工具版本需與 Boot 相容 |
| JUnit + Testcontainers PostgreSQL | 防撞、交易、退款與狀態邊界測試；不使用 H2 代替 PostgreSQL 行為 |
| Actuator / structured logs | 健康檢查、request trace、工作失敗觀測；管理端不直接公開 |

建議以領域分包：

```text
com.example.booking
  auth/         controller dto service entity repository
  member/       ...
  court/        ...
  calendar/     ...
  pricing/      ...
  reservation/  HoldService BookingService SlotInventoryRepository
  order/        ...
  payment/      PaymentService PaymentGateway EcpayGateway
  cancellation/ CancellationService RefundPolicyEngine
  refund/       RefundReviewService RefundWorker
  reschedule/   RescheduleService
  checkin/      ...
  reporting/    ...
  security/     ...
  common/       exception config clock idempotency audit outbox
```

Controller 只處理 HTTP 與 DTO；Service 處理業務與交易；Entity 不直接序列化給前端。公開/後台 Controller 可分開，但共用服務。Redis 可後續作快取/通知/額外限流；資料庫占用仍為防撞權威，即使 Redis 遺失也不能超賣。

## 7. PostgreSQL 資料模型

| 資料表 | 核心欄位與用途 |
| --- | --- |
| `users` | id、email（標準化且唯一）、password_hash、name、phone、enabled、created_at |
| `user_roles` | user_id + role 複合唯一；角色 MEMBER/FRONT_DESK/ADMIN |
| `refresh_sessions` | user_id、token_hash、expires_at、revoked_at；不保存明文 refresh token |
| `venue_settings` | timezone、slot_minutes、advance_days、hold_ttl_minutes、version |
| `courts` | id、name、type、status、sort_order、version、retired_at |
| `operating_hours` | venue/court scope、weekday、open_time、close_time、version |
| `calendar_exceptions` | date、court scope、closed、date_type、特殊 open/close、reason |
| `pricing_rules` | court scope、date/day type、time range、price、priority、effective period、version |
| `cancellation_policy_versions` | immutable version、basis、effective_at、rounding、fee rule |
| `cancellation_policy_tiers` | policy_version、min/max lead days or seconds、refund_rate、fee |
| `holds` / `hold_items` | user_id、status、expires_at；所選 slot、報價與政策快照 |
| `orders` | id、唯一 order_number、member_id（代訂可空）、customer_name/phone、source、status、amount、currency、payment_deadline、notes、created_by、version |
| `booking_items` | id、order_id、court_id、start_at/end_at、court_name/type/price/policy 快照、status；保留取消歷史 |
| `slot_inventory` | court_id + start_at 唯一；end_at、occupation_type、hold_id/booking_item_id/block_id、expires_at、version |
| `blocks` | id、reason、type、created_by、released_at；多場地/多格由 inventory 關聯 |
| `payments` | order_id、method、amount、status、provider、唯一 merchant_trade_no、provider_trade_no、paid_at、received_by |
| `payment_events` | payment_id、event_key、驗簽結果、處理結果、received_at；事件唯一鍵防重複 |
| `cancellation_quotes` / `cancellations` / `cancellation_items` | quote期限、預覽快照、取消項目、reason、取消時間、金額 |
| `refunds` / `refund_items` | payment/cancellation、amount、status、審核理由/人/時間、provider reference、已保留退款額 |
| `reschedule_requests` | booking_item_id、target_court/start、狀態、decision/reason/actor、version |
| `check_ins` | order/item scope、credential reference、checked_in_at/by、reversed_at/by/reason |
| `booking_credentials` | order scope、token_hash、expires_at、revoked_at；取消/失效不得再核銷 |
| `idempotency_records` | actor + operation + key 唯一、request_hash、結果/狀態、expires_at |
| `outbox_events` | aggregate、type、payload、status、retry_count、next_attempt_at |
| `audit_logs` | actor、action、resource、before/after 摘要、reason、created_at |

關係：user 1:N orders；order 1:N booking_items、1:N payments；payment 1:N refunds；每個可販售時段只有一筆 inventory。歷史 booking_items 可以有多筆同一時段（取消後重新預約），不要直接給歷史訂單表無條件 `UNIQUE(court_id,start_at)`。

重要限制與索引：

- `slot_inventory UNIQUE(court_id,start_at)`，所有線上/櫃台/維修都占用這一表。
- inventory 的 HELD、BOOKED、BLOCKED 只能對應一種有效 owner；用 CHECK 約束 owner 欄位一致。
- slot 的 end_at = start_at + 1 hour，所有輸入須在台北整點；區間以 [start,end) 表示。
- 金額非負、有效退款累計不得超過實收；跨列退款累計不能只靠單列 CHECK，須鎖 payment 列及交易內加總/保留。
- merchant_trade_no、有效 provider_trade_no、provider event key 唯一；供應商編號格式與長度按所選 ECPay 產品驗證。
- 索引 orders(member_id,created_at)、booking_items(court_id,start_at)、inventory(expires_at)、refunds(status,created_at)、outbox(status,next_attempt_at)。
- 場地退役只改主檔狀態，訂單快照、收款及退款歷史不能 cascade 刪除。

## 8. 防撞、TTL 與狀態轉換

### 8.1 PostgreSQL 單獨即可完成核心防撞

先在允許預約期間建立 inventory 格子；首次建立使用 INSERT ... ON CONFLICT DO NOTHING 和唯一鍵。`SELECT FOR UPDATE` 不會鎖住不存在的資料列，不能只查「沒有 booking」就當取得鎖。

保留交易：

1. 驗證登入者、數量/整點/未過期/預約範圍/場地啟用/營業時間。
2. 建立或取得對應 inventory；按一致順序鎖所需格子，再檢查最新占用狀態。
3. 過期 hold 依同一套協調鎖定流程轉 EXPIRED、釋出全部相關格子；排程清理未跑也不能使逾期保留永久不可用。
4. 任一格 HELD 未逾期、BOOKED 或 BLOCKED 則整筆 rollback，回 409 conflicts。
5. 後端計價、建立 hold/items、將全部格子改 HELD、寫 expires_at，commit。

所有預約、清理、付款回調、取消、改期和封場經共用服務、遵守一致鎖定順序；多格任一失敗不能留下半張訂單。營業/啟停用/封場變更也要與預約操作協調鎖定並檢查未來占用，不能僅在前端先查再寫。

資料庫鎖只在短交易內持有。10 分鐘 TTL 是持久化占用的有效期，不是讓 PostgreSQL transaction 或 row lock 開著等 10 分鐘。呼叫 ECPay 時不持有 inventory 交易鎖。

### 8.2 狀態分開管理

| 類別 | 狀態 |
| --- | --- |
| hold | ACTIVE / CONSUMED / RELEASED / EXPIRED |
| order / booking item | PENDING_PAYMENT / CONFIRMED / CANCELLED / EXPIRED / COMPLETED；order 可聚合為 PARTIALLY_CANCELLED |
| inventory | FREE / HELD / BOOKED / BLOCKED；CLOSED/PAST 是查詢時依時間/日曆衍生 |
| payment | UNPAID / PENDING / SUCCEEDED / FAILED / UNKNOWN；退款是獨立流程 |
| refund | PENDING_REVIEW / REJECTED / QUEUED / PROCESSING / SUCCEEDED / FAILED / UNKNOWN / NOT_REQUIRED |
| reschedule | PENDING_REVIEW / APPROVED / REJECTED / WITHDRAWN |

前端需要的 paidAmount/refundedAmount/refundableBalance，由付款、退款資料聚合，不從單一混合 payment 字串推導。

付款成功的有效回調：驗簽及金額後，短交易內確認自己仍持有全部格子、hold/order 未到期且未取消，再把 HELD 轉 BOOKED、order/items 轉 CONFIRMED。重複回調回原處理結果。

到期與晚到回調可能競爭，必須鎖定並重新判斷。已釋放/轉售/取消後收到付款成功，記錄實際付款，但不能覆蓋後來的預約；建立補償退款待處理/待審或人工異常帳務。付款成功但沒有預約的情況必須可追蹤。

### 8.3 退款與金流交易不是同一資料庫 transaction

取消交易先寫 cancellation/refund PENDING_REVIEW、釋出格子、保存退款額快照；核准交易再將退款額保留、寫 outbox。worker 呼叫 ECPay 後在新交易記結果。失敗可重試，網路逾時 UNKNOWN 先查詢/對帳，不能把 timeout 當失敗直接第二次退款。

退款上限：某 payment 的已完成退款 + 已核准保留退款 + 本次申請執行額 <= 成功實收。部分取消分配到各 booking item，防止兩個取消申請並行超退。

金流已受理退刷與信用卡帳戶實際入帳是不同階段；回應需帶 providerStatus 和執行時間，前端依可確認的結果顯示「退款待審／退刷已受理／退款失敗」，不能把 API 已受理等同顧客已收到款項。

暫定級距：台北日曆日差 >=3 為 100%；1-2 日 50%；當日 0%。各時段逐一以預約開始日計算，退款按原已付分攤價計算、向下取整、扣明示費用且最小 0。此為待 SA 確認的政策範例，不硬寫在 Controller；規則版本在訂單保存快照。

ECPay AIO 信用卡退款須依實際授權/關帳狀態選擇放棄、取消關帳或退刷。所查官方 AIO `CreditDetail/DoAction` 文件寫明測試環境不提供該 API，所以「ECPay 測試付款可展示」不能等同「沙盒真的可以退款」。後端保留 PaymentGateway 抽象，以 FakePaymentGateway 驗證審核/級距/重試/對帳，正式退款依商戶產品支援或以廠商後台執行並回填證據。

## 9. 必須測試的業務邊界

1. 兩位會員同格、會員和櫃台同格、會員和維修同格：最多一方成功。
2. 一次兩格，第二格衝突：第一格不得留下占用。
3. inventory 初次生成並行：唯一鍵與重試不超賣。
4. TTL 前、等於、超過期限；清理工作延遲；晚到成功回調與取消競爭。
5. 同 Idempotency-Key 重送不新增；相同 key 不同 payload 拒絕。
6. 偽造/錯誤簽章、金額不同、訂單不同、重複及亂序付款事件、正式環境模擬付款。
7. 退款級距 3/2/1/0 日及台北午夜；若採小時模式另測 72/24 小時前後。
8. 奇數價 50% 取整、手續費大於退款、未付款取消、零退款、部分取消、重複退款/並行超退。
9. 退款逾時已成功但本地未知：查詢對帳後不得二次退款。
10. 前端偽造 role/memberId/price、查看他人訂單、櫃台修改管理設定，應拒絕。
11. 非整點、過去時段、關門後、停用場地、假日、跨日截止區間。
12. 已有未來訂單時停用/退役/縮短營業/新增維修，不能悄悄刪除或覆蓋。
13. 取消後核銷、重複核銷、撤銷再核銷、未付款報到處理。
14. 改期審核時目標被占用、並行取消原預約、價差不允許、換位中途失敗，原預約保持一致。

## 10. 開發與前後端整合順序

1. **訂合約與資料庫**：定稿會員付款選项、退款日曆/小時計算、審核角色、改期價差；Swagger/OpenAPI、Flyway、種子員工與場地。
2. **讀取流程**：auth/me、courts、venue、availability；前端移除寫死日期及場地價目，補登入頁和路由守衛。
3. **核心交易**：holds/orders、PostgreSQL 防撞、到期释放、本人列表與詳情；此階段即用真 PostgreSQL 做並行測試。
4. **共用後台**：每日棋盤、代訂、備註、現場收款、維修、場地主檔和營業/定價設定；前後台狀態同步。
5. **ECPay 與取消**：測試付款、ReturnURL、對帳、取消試算/釋出、退款審核/模擬執行與失敗處理；補前端付款結果及退款進度。
6. **提案完整流程**：改期申請/審核、QR 核銷、退款政策設定；報表按需求文件優先序安排。
7. **部署驗收**：Docker/容器化 Spring Boot + PostgreSQL、環境變數管理 DB/JWT/ECPay secret、HTTPS 可對外 callback、備份、migration、health check；以 Postman collection 跑完整角色流程。

最小完整展示：會員登入 -> 查空檔 -> 保留 -> ECPay 測試付款 -> 本人訂單 -> 櫃台核銷 -> 取消與退款審核；另展示會員/櫃台搶同一格只有一方成功、管理員封場不可被預約、改期需審核。

## 11. 技術查核來源

- Spring Boot Java 相容要求：https://docs.spring.io/spring-boot/system-requirements.html
- PostgreSQL row locks / transaction 行為：https://www.postgresql.org/docs/17/explicit-locking.html
- PostgreSQL unique/check/foreign-key 限制：https://www.postgresql.org/docs/current/ddl-constraints.html
- ECPay CheckMacValue 與 callback：https://developers.ecpay.com.tw/2858/
- ECPay ReturnURL 通知與 `1|OK`：https://developers.ecpay.com.tw/16538/
- ECPay AIO 信用卡請退款與測試環境限制：https://developers.ecpay.com.tw/2885/

以上來源支援技術機制；API 命名、資料模型、模組邊界與 MVP 選擇是針對本專案提出的設計建議。
