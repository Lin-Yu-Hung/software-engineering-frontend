# 羽球場館線上預約系統

Vue 3／Vite 前端與 Java 21／Spring Boot／PostgreSQL 後端。提供場地日曆、會員預約、訂單及共用場館後台；ECPay 信用卡付款已完成後端測試環境串接，前端結帳串接與退款尚待整合。

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

前端目前使用假資料，啟動後端或建立資料不會自動反映到畫面，仍需串接 API。

## 啟動本機後端

開啟 Docker Desktop（Linux containers），不需另外安裝 Java、Maven 或 PostgreSQL：

```powershell
cd backend
docker compose up --build -d postgres backend
```

Swagger：`http://localhost:8080/swagger-ui.html`。範例資料與 API 操作請閱讀[後端串接手冊](backend/README.md)。

## 直接使用 Azure API（不需 Docker）

前端串接或使用 Postman 時，也可直接呼叫已部署的 Azure 後端，不需啟動 Docker，也不需安裝本機 Java 或 PostgreSQL。

| 用途 | 網址 |
| --- | --- |
| API origin／Postman baseUrl | `https://ccu-booking-api-1fd823.azurewebsites.net` |
| Swagger 中文說明 | [開啟 Swagger](https://ccu-booking-api-1fd823.azurewebsites.net/swagger-ui.html) |
| 健康檢查 | [檢查 API 與資料庫](https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/health) |

Postman 將 `baseUrl` 改為上面的 origin，後面不加 `/api/v1`，collection 已包含此前綴。前端請將 API 請求指向此 origin；仍須完成實際串接，單純啟動 Vue 不會自動切換資料來源。

雲端使用共用的 Neon 資料庫，新增、修改與取消都會實際影響共用資料。請自行註冊會員；本機示範帳號與模擬付款在 Azure 上停用，`demo:seed` 和完整流程版 Postman 請使用本機環境。

首次呼叫可能因閒置暖機而較慢，可先開啟健康檢查，確認 `status` 與 `database` 都是 `UP`。瀏覽器的 CORS、登入續期及 cookie 限制見[後端串接手冊](backend/README.md#7-前端串接限制)。需要修改或除錯後端程式時，請使用本機開發環境。
