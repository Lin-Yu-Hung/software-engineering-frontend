# 本機範例資料

同學取得專案後可以自己建立，不必安裝 PostgreSQL，也不需要 Neon 密碼。需要 Docker Desktop，以及能執行 `fetch` 的 Node.js 22+。

## 建立方式

在專案根目錄執行：

```powershell
Push-Location backend
docker compose up --build -d postgres backend
docker compose logs -f backend
```

看到 `Started BookingBackendApplication` 後按 Ctrl+C 結束 log 顯示，再執行：

```powershell
Pop-Location
npm.cmd run demo:seed
```

這個 npm 指令只使用 Node.js 內建模組，不需額外安裝套件。它透過既有 API 建立範例，沿用正式的計價、防撞和狀態規則。

## 能看到什麼

原本 Flyway 已提供六個場地、營業時間和費率。範例指令另外新增一個有「範例場地」前綴的場地，以及：

| 情境 | 訂單／時段狀態 |
| --- | --- |
| 會員一次預約兩小時並完成示範付款 | CONFIRMED／PAID |
| 電話代訂，現場預計以現金付款 | CONFIRMED／UNPAID |
| 付款後取消 | CANCELLED／PAID／PENDING_REVIEW，時段已釋放 |
| 會員尚未付款 | PENDING_PAYMENT／UNPAID |
| 暫時保留 | HOLDING |
| 燈具維修 | MAINTENANCE |

所有姓名、電話和帳號都是人工範例，不會刷卡或向綠界請款。待付款與保留仍遵守約十分鐘到期規則，過期後可重新執行指令產生新情境。

每次執行都會新增一個範例場地及一組訂單，不會清空既有資料，也不會在每次後端重啟時自動重建。已存在的示範會員會嘗試以文件密碼登入再使用，不會覆寫其密碼。重跑時會依本機摘要檔釋放上次此工具建立且仍有效的保留；若會員另有手動建立的保留，請先自行完成或釋放。

## 登入與查詢

| 角色 | 帳號 | 密碼 |
| --- | --- | --- |
| 管理員 | admin@booking.local | AdminDemo123! |
| 櫃台 | frontdesk@booking.local | StaffDemo123! |
| 範例會員 | member@booking.local | MemberDemo123! |

前兩個帳號由本機 Compose 啟動時建立，會員由範例指令建立。請使用 Swagger 或純 API Postman 登入並查資料：

- Swagger：`http://localhost:8080/swagger-ui.html`
- 會員：`GET /api/v1/orders?scope=all`
- 櫃台／管理員：`GET /api/v1/backoffice/timetable?date=指令顯示的日期`
- 公開空場：`GET /api/v1/availability?date=指令顯示的日期`

回應 Token 請填到 Postman 的 `accessToken` 變數。範例日期、場地與訂單 IDs 會顯示在終端機，也會寫入忽略 Git 追蹤的 `tmp/local-demo-data.json`。這些本機資料尚不會自動顯示在目前使用假資料的 Vue 畫面。

若要用 DBeaver 直接查看，連線為 localhost:5433、資料庫 booking、使用者 booking、密碼 booking_dev_password，資料表在 public schema。上述都是 Compose 預設值；若自行調整 backend/.env，請以自己的設定為準。

## 匯入範圍

指令預設呼叫 `http://localhost:8080`，僅接受本機 HTTP 位址，且檢查資料庫必須為 `booking`／Flyway V7；拒絕 Neon 的 `neondb`。需要本機示範帳號與付款開關（Compose 預設均啟用）。不會讀取根目錄的 Neon 或 Azure 私密環境檔案。

本機使用不同 API port 時，可設定 `DEMO_API_URL`；資料庫仍需是 `booking`。如果執行中途失敗，已建立的本機範例會保留，終端機會顯示失敗操作。這份工具不會執行任何清空或重設資料庫的指令。
