# 後端串接手冊

Java 21、Spring Boot 4.1.1、PostgreSQL 18、Flyway。REST API 採 JSON camelCase，前綴為 `/api/v1`。詳細欄位與範例以 Swagger 和 Postman 為準。

## 1. 本機開發

需要 Docker Desktop（Linux containers）；範例資料工具另需 Node.js 22.16+。不需自行安裝 Java、Maven 或 PostgreSQL。

在專案根目錄執行：

```powershell
Push-Location backend
docker compose up --build -d postgres backend
docker compose logs -f backend
```

看到 `Started BookingBackendApplication` 後按 Ctrl+C 離開 log，服務仍繼續執行，再用 `Pop-Location` 回到專案根目錄。

| 用途 | 預設位置 |
| --- | --- |
| API origin | `http://localhost:8080` |
| Swagger（中文） | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| 健康檢查 | `http://localhost:8080/api/v1/health` |
| 本機 PostgreSQL | localhost:5433；database=booking；user=booking；password=booking_dev_password |

Flyway 自動建立資料表、六個場地、營業時間和費率。預設每日營業 08:00–22:00，可提前預約 30 天。設定範例見 [.env.example](.env.example)。

修改 Java 後，在 backend 執行 `docker compose up --build -d backend`。停止用 `docker compose stop`；資料存在 Docker volume，停止後保留。`down --volumes` 會刪除資料，不作為一般停止指令。

## 2. 範例資料與帳號

後端啟動後，在專案根目錄執行：

```powershell
npm.cmd run demo:seed
```

透過既有 API 建立範例場地及下列情境：

| 情境 | 狀態 |
| --- | --- |
| 會員預約兩小時並模擬付款 | CONFIRMED／PAID |
| 電話代訂、現場尚未收款 | CONFIRMED／UNPAID |
| 已付款後取消 | CANCELLED／PENDING_REVIEW，時段已釋放 |
| 會員尚未付款 | PENDING_PAYMENT／UNPAID |
| 暫時保留／燈具維修 | HOLDING／MAINTENANCE |

日期依場館時間選取未來空檔，IDs 與日期寫入 `tmp/local-demo-data.json`。保留與待付款約十分鐘後到期。每次執行會新增一組資料、沿用範例會員並釋放工具上次建立且仍有效的保留；不清空既有資料。會員另有手動保留時，請先完成或釋放。中途失敗的已建資料會保留。

工具只接受本機 HTTP 後端及 booking 資料庫，不讀取 Neon 憑證；其他本機 port 可設定 `DEMO_API_URL`。

| 本機角色 | 帳號 | 密碼 |
| --- | --- | --- |
| 管理員 | admin@booking.local | AdminDemo123! |
| 櫃台 | frontdesk@booking.local | StaffDemo123! |
| 範例會員 | member@booking.local | MemberDemo123! |

前兩個帳號由 Compose 示範設定建立，會員由 `demo:seed` 建立；不適用於 Azure。使用 Postman 或 Swagger 查詢，資料尚不會自動顯示在假資料版 Vue 畫面。

## 3. Postman

| 檔案 | 用途 |
| --- | --- |
| [純 API 版](postman/booking-api.postman_collection.json) | 44 個 API，手動 Send，含 20 個 JSON body 範例 |
| [流程版](postman/booking-all.postman_collection.json) | 六階段、152 個案例，供本機 Collection Runner |
| [本機環境](postman/local.postman_environment.json) | baseUrl 預設 localhost:8080 |

登入後把回應 `accessToken` 填入 collection 同名變數。courtId、holdId、orderId 等由回應複製，範例日期須更新為有效未來日期。Environment 同名變數會優先於 collection。

流程版須依完整階段順序操作，會建立資料、暫時修改並還原營業時間與費率，請勿跳過還原或整套跑在 Azure。舊 phase1–6 JSON 保留作為產生來源，不需逐份匯入。

重新產生檔案：在專案根目錄執行 `node scripts/build-postman-api.mjs`、`node scripts/merge-postman.mjs`。

## 4. API 操作與權限

下表路徑均加 `/api/v1`；詳細方法與 body 請開 Swagger 或純 API Postman。

| 功能 | API | 權限 |
| --- | --- | --- |
| 場地、場館、空場 | `/courts`、`/courts/{courtId}`、`/venue`、`/availability?date=YYYY-MM-DD&courtId=UUID` | 公開 |
| 註冊、登入、續期、登出 | `/auth/register`、`/auth/login`、`/auth/refresh`、`/auth/logout` | 公開／refresh cookie |
| 個人資料 | `GET/PATCH /me` | 登入者 |
| 保留 | `POST /holds`、`GET/DELETE /holds/{holdId}` | MEMBER |
| 會員訂單 | `POST/GET /orders`、`GET /orders/{orderId}`、`POST /orders/{orderId}/cancellations` | MEMBER，限本人資料 |
| 排程、代訂、修改、取消、報到／撤銷 | `/backoffice/timetable`、`/backoffice/orders` 及其子路徑 | FRONT_DESK／ADMIN |
| 場地設定 | `/backoffice/courts` | ADMIN |
| 營業與費率 | `/backoffice/operating-hours`、`/backoffice/calendar-exceptions`、`/backoffice/pricing-rules` | ADMIN |
| 維修封場 | `POST/GET /backoffice/blocks`、`DELETE /backoffice/blocks/{blockId}` | ADMIN |
| 示範付款 | `POST /demo/orders/{orderId}/confirm-payment` | ADMIN 且開關啟用 |

ADMIN 包含櫃台權限，不自動包含 MEMBER。註冊只建立會員，不能自行指定管理員角色。

### 新增管理員

使用現有 ADMIN 登入，帶其 Bearer token 呼叫 `POST /api/v1/backoffice/admins`：

```json
{"email":"admin-new@example.test","password":"AdminDemo123!","name":"新管理員","phone":"0912345678"}
```

成功回傳 201 與帳號資料（不回密碼、不切換登入身分）。未登入回 401，會員／櫃台回 403，Email 已存在回 409；此 API 不提升既有會員角色。第一位管理員仍需由本機示範初始化或資料庫授權建立，不提供公開建立入口。目前先提供 Swagger／Postman 操作，前端尚未有新增管理員表單。

會員註冊與新增管理員密碼都需 8–72 字元、UTF-8 不超過 72 bytes，沒有強制大小寫、數字或特殊符號組合。欄位驗證失敗回 400，message 可直接顯示，errors 可供前端標示欄位；不回傳輸入值。例如：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "password：密碼長度需為 8–72 字元，且 UTF-8 不可超過 72 bytes",
  "errors": [{"field":"password","message":"密碼長度需為 8–72 字元，且 UTF-8 不可超過 72 bytes"}]
}
```

回應另包含 path、timestamp。多位元組密碼超過 72 bytes 時回 INVALID_PASSWORD 與長度說明。

會員預約順序：

1. 查 `/venue` 取得後端日期與可預約範圍。
2. 查 `/availability` 選 AVAILABLE 時段，傳 `items: [{courtId, startAt}]` 建立 hold。
3. `/holds` 回傳 id、expiresAt、totalAmount；每會員最多一筆 ACTIVE 保留，一次 1–12 個時段。
4. `/orders` 傳 holdId、customer（name／phone）、paymentMethod=CREDIT_CARD，建立 PENDING_PAYMENT 訂單；沿用原保留期限。
5. 付款驗證後更新為 CONFIRMED／PAID；用 `/orders/{orderId}` 查結果，建立訂單本身不代表付款成功。

## 5. 共通規則

- 時區為 Asia/Taipei，預約固定整點一小時。日期 YYYY-MM-DD；時間 ISO 8601 含 offset，顯示前轉為臺灣時間。
- 金額為新臺幣整數元，由後端計算；既有保留與訂單保存成交單價與費率版本。
- 新增保留、訂單、櫃台代訂、封場需 Idempotency-Key：8–100 個英數或 `. _ : -`。同操作重試沿用 key，新操作換新 key；同 key 不同內容回 409。
- 修改設定與報到等操作需最新 expectedVersion，先 GET 取得 version；版本衝突須重新讀取。
- 訂單分頁 page 從 0 起，size 為 1–100；會員 scope 可用 all／upcoming／past。回應為 content 加分頁資訊，一張訂單有多個 items。
- 顯示編號用 orderNumber，操作 API 用 UUID id。
- 錯誤：400 格式／欄位、401 未登入、403 權限不足、404 不存在／非本人資料、409 占用或狀態／版本衝突、503 資料庫異常。JSON 含 code、message、path、timestamp；目前不提供逐欄驗證錯誤。

## 6. 後台與設定

- 代訂 source 限 WALK_IN／PHONE，付款方式限 CASH／ONSITE_CARD，需 customer、notes、items。建立為 CONFIRMED／UNPAID，不表示已收款。
- `/backoffice/timetable?date=YYYY-MM-DD` 回傳 grid、orders、blocks；前端依場地與時間對照顧客和訂單。
- 修改訂單僅更新聯絡資料與備註，不改預約時段或金額。
- 報到／撤銷傳 `{expectedVersion}`。僅 CONFIRMED 可報到，時間限第一時段前 24 小時至最後時段結束前；報到不代表收款。
- 取消以整張訂單為單位；已開始不可取消，已報到須先撤銷。已付取消為 PENDING_REVIEW，不代表退款成功。
- 封場每次 1–12 個未來營業時段，不可覆蓋預約；解除以整筆封場為單位。
- 每週營業 PUT 需完整七天，週一=1、週日=7，periods=[] 代表休館。例外日取代當日營業時間及 dayType；HOLIDAY 須自行設定。
- 費率 PUT 取代完整規則。WEEKDAY／WEEKEND／HOLIDAY 都需有涵蓋全天的全場預設費率；個別場地優先，再比較 priority。同範圍與優先序不可有重疊區間。
- 停用場地或縮減營業時間不可排除既有有效占用。

## 7. 前端串接限制

目前 Vue 尚未接 API，也沒有完整登入流程；角色切換按鈕不能取代權限驗證。串接前須對齊：

- 畫面會員現場付款選項與後端會員僅信用卡的限制。
- 畫面逐時段操作與後端整張訂單取消／報到的範圍。
- 訂單 item 只有 courtId、startAt、endAt、unitPrice；未附場地名稱、類型與價格類型，歷史停用場地需另處理。
- 大寫 API 狀態需轉換，並處理 EXPIRED；日曆 CLOSED、PAST、HOLDING、MAINTENANCE 不可視為空場。
- 場地與費率是分開資源，一次畫面儲存可能涉及多次 API，沒有跨請求的一次性交易。
- QR 掃碼核銷、分項取消／報到、退款試算及真正金流尚未提供完整 API。
- CORS 預設允許 http://localhost:5173、5174。續期／登出需 refresh cookie 與 X-Requested-With: BookingClient；瀏覽器需包含 credentials。SameSite=Lax 的跨站 Azure 呼叫還需對齊代理或 cookie 策略，Postman 通過不代表瀏覽器續期可用。

## 8. 金流整合與驗證

在同一個 Spring Boot 專案驗證 ECPay 簽章、交易識別及實收金額後，注入 PaymentConfirmationService 呼叫：

```java
confirmVerifiedPayment(orderId, paidAmount, providerTransactionReference);
```

金額須等於 totalAmount。成功更新為 CONFIRMED／PAID 並移除占用 TTL，同筆交易可重送。已到期訂單不能復活，遲到付款須補償。會員不可直接宣告付款成功。

示範付款只更新狀態，不連綠界；真正付款頁、回呼、現場收款與退款由金流模組提供。

在 backend 執行 `docker compose --profile test run --rm tests` 使用獨立 booking_test 資料庫；雲端連線與部署見[雲端部署手冊](AZURE_DEPLOYMENT.md)。
