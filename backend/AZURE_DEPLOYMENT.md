# Azure 後端部署與驗證

後端已部署到 Azure App Service，資料庫沿用 Neon production。這次使用 F1 Free 方案、Java 21，直接部署 Spring Boot JAR；沒有 Git push。

| 項目 | 目前設定 |
| --- | --- |
| 訂閱 | Azure for Students |
| 資源群組 | `CCU_booking-demo` |
| 區域 | Southeast Asia（新加坡） |
| App Service Plan | `booking-demo-free-plan`／F1 Free |
| Web App | `ccu-booking-api-1fd823` |
| API origin | `https://ccu-booking-api-1fd823.azurewebsites.net` |
| 健康檢查 | [GET /api/v1/health](https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/health) |
| Swagger | [線上 API 操作介面](https://ccu-booking-api-1fd823.azurewebsites.net/swagger-ui.html) |
| OpenAPI | [JSON 規格](https://ccu-booking-api-1fd823.azurewebsites.net/v3/api-docs) |
| 資料庫 | Neon／`neondb`／Flyway V7 |

## Postman 如何使用

建立新的 Environment「Booking Azure」，加入 `baseUrl`，值為：

```text
https://ccu-booking-api-1fd823.azurewebsites.net
```

選取此環境，先送出 `GET {{baseUrl}}/api/v1/health`，預期 HTTP 200、`status=UP`、`database=UP`、`schemaVersion=7`。接著可測試公開場地、日曆，或註冊自己的會員後登入預約。URL 後面不要再補 `/api/v1`，collection 的請求路徑已包含它。

雲端停用固定示範帳號與模擬付款，因此本機 phase 2～6 collection 中依賴這些功能的請求不適合直接全套跑在雲端。完整會員雲端驗收使用下方的 `check:azure`；管理員需另行建立，真實 ECPay 仍由金流同學負責。

## 修改後如何再次部署

在專案根目錄執行。需要 Node.js 22.16+、Docker Desktop，以及有此 Azure 訂閱權限的 CLI 登入。

```powershell
# 查看部署目標，不會建立資源
npm.cmd run azure:plan

# 使用 Docker 編譯 Java 21 JAR；不需要本機安裝 Java/Maven
Push-Location backend
docker compose --profile test run --rm --no-deps tests mvn -B -ntp -DskipTests package
Pop-Location

# 套用應用程式設定並上傳 JAR
npm.cmd run azure:deploy

# 從 Azure HTTPS 網址跑完整驗收
npm.cmd run check:azure
```

`-DskipTests package` 是部署打包；若修改後端業務邏輯，應先執行原本測試：

```powershell
Push-Location backend
docker compose --profile test run --rm tests
Pop-Location
```

部署腳本讀取 `azure/backend.config.json`。它會檢查目前訂閱、方案必須是 F1，並確認既有 Web App 屬於指定方案；不會自動升級成付費方案。成功後將公開部署資訊寫入 `tmp/azure-backend-deployment.json`，驗收結果寫入 `tmp/azure-e2e.json`。

### Azure CLI 登入

本機目前使用官方 portable Azure CLI，位於忽略追蹤的 `tmp/azure-cli`，登入狀態保存在 `tmp/azure-config`。需重新登入時：

```powershell
$env:AZURE_CONFIG_DIR = Join-Path (Get-Location) 'tmp/azure-config'
& .\tmp\azure-cli\bin\az.cmd login --use-device-code
& .\tmp\azure-cli\bin\az.cmd account set --subscription 1fd8238c-2282-4a06-96b0-f002bc282f7f
```

其他同學 pull 專案後不會取得 CLI、登入狀態或密碼。可依 [官方 Windows 安裝方式](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli-windows) 取得 CLI，再登入有權限的帳號；腳本支援 `AZURE_CLI_PYTHON` 指向 CLI 隨附的 `python.exe`，使用 `-IBm azure.cli` 執行。一般 macOS/Linux 的 `az` 也可直接使用。

### 憑證與設定

根目錄 `.env.local`（若已有 `.env` 則優先讀它）需有 Neon 的 `DATABASE_URL`、`DATABASE_URL_UNPOOLED`；取得方式見 [NEON_SETUP.md](NEON_SETUP.md)。部署腳本會首次產生獨立的 `AZURE_JWT_SECRET` 並保存在該忽略追蹤的檔案。不要刪除這個值，否則重新部署會使既有登入失效。

資料庫連線及 JWT 透過 Azure App Settings 提供給後端，不會寫進前端程式碼或部署設定 JSON。上傳設定用的本機暫存 JSON 在上傳後刪除。Neon profile 使用 pooled 連線處理業務、direct 連線跑 Flyway，保留 TLS。Azure 設定另外包含：

- `SECURE_COOKIE=true`：refresh cookie 只走 HTTPS，並保留 HttpOnly。
- `SERVER_FORWARD_HEADERS_STRATEGY=framework`：Swagger 以 HTTPS 產生 API 網址。
- `DEMO_USERS_ENABLED=false`、`DEMO_PAYMENTS_ENABLED=false`。
- `CORS_ALLOWED_ORIGINS`：目前允許本機 5173／5174；前端正式上線時，再於部署 JSON 加入完整 HTTPS origin 並重新部署。

前端元件維持原樣，仍使用既有假資料。跨站 refresh cookie 需要前後端整合時處理 SameSite 或同源代理；目前部署不代表 Vue 已接上 API。

## 2026-10-22 雲端驗收

JAR 打包 `BUILD SUCCESS`，部署命令成功且 Web App 狀態為 Running。實際 HTTP 驗證共 37 項：

- 9 項 HTTPS／工作階段檢查：OpenAPI、Swagger HTTPS、註冊、登入、Secure+HttpOnly cookie、續期、cookie 輪替、登出與 token 撤銷。
- 28 項預約流程檢查：Neon 健康與 V7、場地與時段、兩位會員、保留與重送、第二位會員搶同時段 409、訂單持久化、擁有者隔離、取消釋放、再次保留與歷史紀錄。

本次命令輸出摘要：

```text
npm.cmd run build
✓ 188 modules transformed.
✓ built in 965ms

npm.cmd run check:azure
Azure health UP; database=neondb; schema=7
NEON E2E PASS: 28 checks; database=neondb; schema=7
Azure verification passed: 9 HTTPS/session checks + 28 booking API checks = 37 checks.
```

驗收會新增隨機測試會員並保留一張已取消的訂單，會釋放自身測試占用；不會刪除其他使用者資料。

初次 Container Apps 雲端 builder 回報 `BuilderOperationError`（服務端 Kube Config 為空），因此改用 App Service F1 直接上傳 JAR。失敗的 builder、測試 Container App 與空環境已全部刪除；資源群組最後只保留 Web App 和 F1 plan，不需 ACR 或 Log Analytics。

F1 適合作業展示，具有用量限制且未啟用 Always On；閒置後第一次請求可能較慢。可在展示前開啟健康檢查喚醒。若回應異常，先到 Azure Portal → Web App → Log stream 檢查啟動訊息，避免為排查問題直接升級付費方案。
