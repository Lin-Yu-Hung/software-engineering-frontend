# 雲端環境與部署

## 環境

| 項目 | 設定 |
| --- | --- |
| Azure 資源群組 | CCU_booking-demo |
| Web App | ccu-booking-api-1fd823 |
| App Service Plan | booking-demo-free-plan／F1 |
| 執行環境 | Southeast Asia／Java 21 |
| API origin | https://ccu-booking-api-1fd823.azurewebsites.net |
| Swagger | https://ccu-booking-api-1fd823.azurewebsites.net/swagger-ui.html |
| 健康檢查 | https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/health |
| Neon 專案／分支 | booking-demo／production |
| 資料庫／結構版本 | neondb／Flyway V7 |

部署設定見 [azure/backend.config.json](../azure/backend.config.json)。雲端示範帳號與模擬付款停用；使用自行註冊的會員或已授權帳號。完整本機 Runner 不適用於雲端。

## Neon 連線

經專案管理者授權，在 Neon Console 選擇專案與 branch，從 Connect 取得 pooled 與 direct 連線，填入專案根目錄 `.env.local`；若已有 `.env`，工具優先讀取它。

```dotenv
DATABASE_URL=postgresql://USER:PASSWORD@POOLED_HOST/neondb?sslmode=require
DATABASE_URL_UNPOOLED=postgresql://USER:PASSWORD@DIRECT_HOST/neondb?sslmode=require
```

上述是格式範例，需填真正連線。業務流量用 pooled，Flyway 用 direct，兩者須對應相同資料庫、角色與 endpoint。環境檔不提交 Git，不得放入前端或 VITE_ 變數。

在專案根目錄執行 `npm.cmd run backend:neon` 可啟動本機後端連 Neon（需 Docker），預設 localhost:8081。停止用 `npm.cmd run backend:neon:stop`。此模式會實際讀寫雲端資料；一般開發請用[本機 PostgreSQL](README.md#1-本機開發)。Neon profile 已降低閒置連線並停用定時到期掃描，預約操作仍處理到期占用。

## Azure CLI 與憑證

需要 Node.js 22.16+、Docker Desktop、Azure CLI 與指定訂閱的部署權限。工具優先使用 `tmp/azure-cli/python.exe`；亦可設定 AZURE_CLI_PYTHON 指向 CLI 隨附的 python.exe。非 Windows 支援 PATH 中的 az。CLI、登入狀態與環境檔不隨 Git 分享，其他電腦需另行準備。

使用 portable CLI 時，在專案根目錄登入：

```powershell
$env:AZURE_CONFIG_DIR = Join-Path (Get-Location) 'tmp/azure-config'
& .\tmp\azure-cli\bin\az.cmd login --use-device-code
& .\tmp\azure-cli\bin\az.cmd account set --subscription 1fd8238c-2282-4a06-96b0-f002bc282f7f
```

部署需 DATABASE_URL、DATABASE_URL_UNPOOLED、AZURE_JWT_SECRET。首次缺少 AZURE_JWT_SECRET 時會產生並存入忽略追蹤的環境檔；換電腦部署應保留原值，否則會使既有登入失效。

## 打包與部署

在專案根目錄執行：

```powershell
npm.cmd run azure:plan
Push-Location backend
docker compose --profile test run --rm --no-deps tests mvn -B -ntp -DskipTests package
Pop-Location
```

確認 BUILD SUCCESS 後，執行 `npm.cmd run azure:deploy`。部署只上傳現有 JAR，不會自動編譯；會檢查訂閱與 F1 方案、套用環境設定並重啟 Web App，結果存於 `tmp/azure-backend-deployment.json`。

腳本也處理資源與環境設定，包含註冊資源提供者。只有單一 Web App 權限的協作者可能無法完整執行；請由環境管理者部署，或另行規劃只更新 JAR 的流程。每次部署更新整個後端，需先合併共同版本。

## App Settings 與驗證

Azure Portal → Web App → Environment variables／App settings：

| 設定 | 用途 |
| --- | --- |
| SPRING_PROFILES_ACTIVE=neon | Neon profile |
| DATABASE_URL／DATABASE_URL_UNPOOLED | 業務與遷移連線 |
| JWT_SECRET | 由 AZURE_JWT_SECRET 提供的雲端簽章金鑰 |
| SECURE_COOKIE=true | cookie 僅經 HTTPS |
| SERVER_FORWARD_HEADERS_STRATEGY=framework | 正確辨識反向代理 HTTPS |
| DEMO_USERS_ENABLED=false | 停用固定示範帳號 |
| DEMO_PAYMENTS_ENABLED=false | 停用模擬付款 |
| CORS_ALLOWED_ORIGINS | 允許的前端 origin；目前為 localhost:5173、5174 |

部署會重新套用開關，Portal 手動修改可能被覆寫。真正綠界金鑰由金流模組定義，開啟模擬付款不等於接通綠界。

部署後查 `/api/v1/health`，status 與 database 應均為 UP。完整雲端驗收指令 `npm.cmd run check:azure` 會新增測試會員與已取消訂單，結果存於 `tmp/azure-e2e.json`。`npm.cmd run check:neon` 預設驗收 localhost:8081，也會寫入資料。

F1 未啟用 Always On，閒置後首次回應可能較慢。異常時先查 Azure Log stream；跨站續期限制見[後端串接手冊](README.md#7-前端串接限制)。
