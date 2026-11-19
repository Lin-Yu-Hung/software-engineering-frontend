# 羽球場館線上預約系統

Vue 3／Vite 前端與 Java 21／Spring Boot／PostgreSQL 後端。提供場地日曆、會員預約、訂單及共用場館後台；**共用後台 (`/admin`) 已完成所有現有後端 API 串接並部署至 Vercel**，ECPay 信用卡付款已完成後端測試環境串接，前台預約結帳與後台退款尚待整合。

## 線上網址

| 用途 | 網址 |
| --- | --- |
| 前端網站（Vercel） | [https://software-engineering-frontend-five.vercel.app](https://software-engineering-frontend-five.vercel.app) |
| 後台管理看板 | [https://software-engineering-frontend-five.vercel.app/admin](https://software-engineering-frontend-five.vercel.app/admin) |
| 後端 API（Azure） | `https://ccu-booking-api-1fd823.azurewebsites.net` |
| Swagger 中文說明 | [開啟 Swagger](https://ccu-booking-api-1fd823.azurewebsites.net/swagger-ui.html) |
| 後端健康檢查 | [檢查 API 與資料庫](https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/health) |

> Vercel 已透過 [vercel.json](vercel.json) 將 `/api/*` 反向代理至 Azure 後端並處理 SPA 路由。`azure/backend.config.json` 已將上述 Vercel 網址加入 `corsAllowedOrigins`，同步套用至 Azure App Settings 的 `CORS_ALLOWED_ORIGINS` 後即可完整支援線上重整自動續期與登出。

## 目前前端完成度

| 模組 | 狀態 | 說明 |
| --- | --- | --- |
| 共用場館後台 (`/admin`) | ✅ 已串接真實 API | 真實 JWT 登入與 RBAC（`FRONT_DESK`／`ADMIN`）、每日排程棋盤看板、臨櫃／電話代訂（支援多時段）、訂單列表分頁、修改聯絡資料與備註、整張訂單報到／撤銷與取消、維修封場、場地主檔／費率規則／每週營業時間／特殊日曆例外管理、本機示範付款確認 |
| 全站 API 載入狀態 | ✅ 已完成 | 統一 API 客戶端 (`src/api/client.js`)、自動 401 Token 續期與全站「資料讀取中...」動畫 |
| 前台預約與結帳 (`/`, `/booking`, `/my-bookings`) | ⏳ 待串接 | 目前仍使用假資料；綠界付款串接待整合 |
| 後台現場收款確認／綠界退款 | ⏳ 待後端提供 API | 後端尚未提供現場收款記帳、`NEEDS_REFUND` 清單與退款 API |

## 文件入口

- [後端串接手冊](backend/README.md)：本機啟動、範例資料、帳號、API 流程及串接限制。
- [雲端部署手冊](backend/AZURE_DEPLOYMENT.md)：Neon 連線、Azure 設定、打包與部署。
- [綠界金流串接手冊](backend/ECPAY.md)：信用卡付款流程、本機 ngrok 測試刷卡、付款狀態與常見問題。
- [需求規格](REQUIREMENTS.md)：完整目標需求，不代表目前功能全部完成。

## 啟動前端

需要 Node.js 22.16+，在專案根目錄執行：

```powershell
npm.cmd install
npm.cmd run dev
```

依終端機顯示網址開啟，預設為 `http://localhost:5173`。首頁 `/` 為場地預約、`/booking` 為結帳、`/my-bookings` 為個人訂單、`/admin` 為共用後台。

### 本機開發切換後端目標（Vite Proxy）

前端開發伺服器已於 [vite.config.js](vite.config.js) 設定 `/api` 反向代理，預設連線至本機 Docker 後端 (`http://localhost:8080`)。若要改連 Azure 雲端後端，可複製 [.env.example](.env.example) 為 `.env` 並設定後重啟 `npm.cmd run dev`：

```env
VITE_API_PROXY_TARGET=https://ccu-booking-api-1fd823.azurewebsites.net
```

> 目前 `/admin` 後台已串接真實 API；前台頁面 (`/`, `/booking`, `/my-bookings`) 仍為假資料。

## 啟動本機後端

開啟 Docker Desktop（Linux containers），不需另外安裝 Java、Maven 或 PostgreSQL：

```powershell
cd backend
docker compose up --build -d postgres backend
```

Swagger：`http://localhost:8080/swagger-ui.html`。範例資料與本機示範帳號（`admin@booking.local`、`frontdesk@booking.local`）請閱讀[後端串接手冊](backend/README.md)。

## 直接使用 Azure API（不需 Docker）

前端開發（透過 `.env` 設定 `VITE_API_PROXY_TARGET`）、Vercel 正式站或 Postman 皆可直接呼叫已部署的 Azure 後端，不需啟動 Docker，也不需安裝本機 Java 或 PostgreSQL。

Postman 將 `baseUrl` 改為 `https://ccu-booking-api-1fd823.azurewebsites.net`（後面不加 `/api/v1`，collection 已包含此前綴）。

雲端使用共用的 Neon 資料庫，新增、修改與取消都會實際影響共用資料。請自行註冊會員或使用已授權之後台帳號；本機示範帳號與模擬付款在 Azure 上停用，`demo:seed` 和完整流程版 Postman 請使用本機環境。

首次呼叫可能因閒置暖機而較慢，可先開啟健康檢查，確認 `status` 與 `database` 都是 `UP`。瀏覽器的 CORS、登入續期及 cookie 限制見[後端串接手冊](backend/README.md#7-前端串接限制)。需要修改或除錯後端程式時，請使用本機開發環境。
