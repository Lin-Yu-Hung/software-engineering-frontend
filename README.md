# 羽球場館線上預約系統

Vue 3／Vite 前端與 Java 21／Spring Boot／PostgreSQL 後端。提供場地日曆、會員預約、訂單及共用場館後台；真正的 ECPay 付款與退款尚待整合。

## 文件入口

- [後端串接手冊](backend/README.md)：本機啟動、範例資料、帳號、API 流程及串接限制。
- [雲端部署手冊](backend/AZURE_DEPLOYMENT.md)：Neon 連線、Azure 設定、打包與部署。
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
