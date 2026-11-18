# 羽球預約後端：作業 demo

同學在本機啟動 Docker 後，可於專案根目錄執行 `npm.cmd run demo:seed` 建立會員、訂單與維修封場等範例資料。情境、帳密與步驟見 [LOCAL_SAMPLE_DATA.md](LOCAL_SAMPLE_DATA.md)。

Azure 後端已上線：[Swagger](https://ccu-booking-api-1fd823.azurewebsites.net/swagger-ui.html)。使用 App Service F1 免費方案、Java 21 與 Neon PostgreSQL；更新部署、Postman 和驗證方式見 [AZURE_DEPLOYMENT.md](AZURE_DEPLOYMENT.md)。

Neon 雲端 PostgreSQL 已提供獨立啟動方式：在根目錄執行 `npm.cmd run backend:neon`，API 預設 localhost:8081。設定、驗證與 Azure 交接見 [NEON_SETUP.md](NEON_SETUP.md)。原本下列 Docker 本機資料庫流程仍可使用。

本階段使用 Java 21、Spring Boot 4.1.1、PostgreSQL 18、Flyway、JPA 與 Swagger。
已提供會員、場地、營業日曆、費率、預約訂單及共用後台 API。營業設定見 [API_PHASE3.md](API_PHASE3.md)，保留／訂單見 [API_BOOKING.md](API_BOOKING.md)，櫃台與封場見 [API_BACKOFFICE.md](API_BACKOFFICE.md)。日曆已支援實際占用，bookingEnabled=true／inventoryMode=LIVE。前端維持原樣；ECPay 與退款由另一位同學整合。

**要直接 demo，先看 [DEMO_GUIDE.md](DEMO_GUIDE.md)**。最後階段 collection 能依序展示預約 → 模擬付款 → 報到 → 取消 → 維修；示範付款明示 DEMO，不會實際請款。

Postman 有兩個版本：[純 API 與 Body 範例](postman/booking-api.postman_collection.json) 包含 43 個不重複的 API，適合手動 Send；[六階段流程版](postman/booking-all.postman_collection.json) 包含 152 個案例，適合本機 Collection Runner。操作與變數設定見 [Postman 說明](postman/README.md)。流程版會寫入測試資料，需使用本機 DEMO 設定。

## 同學 pull 後最快的啟動方式

只需安裝並啟動 **Docker Desktop**（Linux containers），不需要另外安裝 Java、Maven 或 PostgreSQL。
首次啟動需連線下載官方映像與 Maven 依賴。

在專案根目錄的 PowerShell 執行：

```powershell
cd backend
docker compose up --build -d postgres backend
docker compose logs -f backend
```

看到 `Started BookingBackendApplication` 就表示啟動完成。按 Ctrl+C 只退出 log 查看，不會停止容器。

- API：<http://localhost:8080/api/v1/health>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- PostgreSQL：`localhost:5433`，資料庫 `booking`、使用者 `booking`、開發密碼 `booking_dev_password`。

資料庫由 PostgreSQL 容器自動建立。後端首次啟動由 Flyway 執行：

1. `V1__create_courts.sql`：建立 `courts` 表。
2. `V2__seed_initial_courts.sql`：加入 6 個範例場地。
3. `V3__create_users_and_sessions.sql`：建立 users、user_roles、refresh_sessions。
4. `V4__unique_active_court_names.sql`：非退役場地名稱不分大小寫唯一。
5. `V5__create_schedules_and_pricing.sql`：每週營業時段、例外日、價格版本與初始費率。
6. `V6__create_booking_core.sql`：時段保留、訂單、明細、防撞占用與重試 key。
7. `V7__create_backoffice_operations.sql`：手動報到欄位、維修封場及共用占用。

每次重啟只執行尚未執行的 migration；不會重建資料表或重複加入場地。每位同學有自己的本機資料庫，但結構及初始資料相同，不需要傳資料庫備份給彼此。

## Postman 驗證

第六階段匯入 `postman/booking-phase6.postman_collection.json`，名稱 Booking Backend - Phase 6 Demo。沿用 Booking Local，Runner 依序跑 25 個請求、39 個斷言；本機 Compose 預設啟用 DEMO_PAYMENTS_ENABLED。這份 collection 適合最後作業展示；詳細步驟與同學交接見 [DEMO_GUIDE.md](DEMO_GUIDE.md)。

第五階段匯入 `postman/booking-phase5.postman_collection.json`，沿用 Booking Local，Runner 依序跑 30 個請求，測試櫃台代訂、每日棋盤、修改聯絡資料、報到撤銷與維修封場。日期／時段自動選取，最後清理占用；詳見 [API_BACKOFFICE.md](API_BACKOFFICE.md)。

第四階段匯入 `postman/booking-phase4.postman_collection.json`，沿用 Booking Local，按順序執行。會建立專用 demo 場地與會員，最後取消／釋放預約並退役場地。保留預設 10 分鐘，不需要額外 Redis。

**第三階段匯入 `postman/booking-phase3.postman_collection.json`**，沿用 `Booking Local`，以 Runner 按順序執行全部 31 個請求。日期依後端台北時間自動選取。流程會暫時調整日曆與價格，再刪除測試例外日、還原原本設定；請在本機開發環境完整跑完，期間避免其他人同時編輯設定。歷次價格版本會保留。

**第二階段請另外匯入 `postman/booking-phase2.postman_collection.json`**，名稱是 `Booking Backend - Phase 2`。沿用 `Booking Local` 環境與 baseUrl，不必刪除第一階段 collection。用 Collection Runner 按順序執行全部 30 個請求。登入請求會自動保存 accessToken；Postman cookie jar 會接收 refresh cookie。請勿停用 cookie jar。

第二階段會建立一位隨機 Email 的測試會員和一個測試場地，最後將場地退役；因此健康檢查 courtCount 可能大於 6，這是保留歷史資料的預期結果。400、401、403、409 測試回應是預期結果。

第一階段的公開查詢仍可用。若只要驗證第一階段，在 Postman 選 **Import**，匯入：

- `postman/booking-phase1.postman_collection.json`
- `postman/local.postman_environment.json`

右上角選擇 `Booking Local` 環境。依序送出 collection 裡的請求，或使用 Collection Runner 一次執行。
第一階段 Authorization 選 No Auth。每個請求已附測試斷言，錯誤測試的 400/404/405 是預期結果。

| 方法 | URL | 預期 |
| --- | --- | --- |
| GET | `{{baseUrl}}/api/v1/health` | 200；資料庫 UP、schemaVersion 7、初始 courtCount 6 |
| GET | `{{baseUrl}}/api/v1/courts` | 200；6 個啟用場地，按順序排列 |
| GET | `{{baseUrl}}/api/v1/courts/{{courtId}}` | 200；單一場地 |
| GET | `{{baseUrl}}/api/v1/courts/ffffffff-ffff-ffff-ffff-ffffffffffff` | 404、COURT_NOT_FOUND |
| GET | `{{baseUrl}}/api/v1/courts/not-a-uuid` | 400、INVALID_PARAMETER |
| POST | `{{baseUrl}}/api/v1/courts` | 405；新增場地使用管理員 `/backoffice/courts` |
| GET | `{{baseUrl}}/v3/api-docs` | 200；OpenAPI 文件 |

`baseUrl` 預設 `http://localhost:8080`。`courtId` 在查詢列表後自動存入 collection variable；未查列表也可使用初始第一場地 UUID。

健康檢查會真的執行 SQL，成功範例：

```json
{
  "status": "UP",
  "database": "UP",
  "databaseName": "booking",
  "schemaVersion": "7",
  "courtCount": 6,
  "checkedAt": "2026-10-04T13:00:00Z"
}
```

場地列表是直接回傳陣列，場地 ID 是 UUID，不是前端假資料的 c1/c2：

```json
[
  {
    "id": "00000000-0000-0000-0000-000000000001",
    "name": "第 1 場地",
    "type": "VIP 木地板",
    "enabled": true
  }
]
```

上例只展示一筆；初始資料會回 6 筆。停用與退役場地不會出現在公開查詢。
API 成功不會改變 Vue 畫面，前端串接由前端同學完成。

## 第二階段 API 與操作方式

以下 URL 均加上 `/api/v1`。登入以外的受保護 API 需要 `Authorization: Bearer <accessToken>`；登入、refresh、logout 用 No Auth。Swagger 也可按 Authorize 貼上 accessToken。

| 方法 | 路徑 | 權限／用途 |
| --- | --- | --- |
| POST | `/auth/register` | 公開；新增 MEMBER，不接受角色或 userId |
| POST | `/auth/login` | 公開；取得 accessToken 與 HttpOnly refresh cookie |
| POST | `/auth/refresh` | Cookie 與 `X-Requested-With: BookingClient`；輪替 refresh token |
| POST | `/auth/logout` | 同上；撤銷當次登入並清除 cookie，204 |
| GET | `/me` | 登入者的姓名、Email、電話與角色 |
| PATCH | `/me` | 登入者；只可修改 name、phone |
| GET | `/backoffice/courts` | ADMIN；包括停用、退役場地 |
| GET | `/backoffice/courts/{courtId}` | ADMIN；場地主檔與 version |
| POST | `/backoffice/courts` | ADMIN；建立場地，201 |
| PATCH | `/backoffice/courts/{courtId}` | ADMIN；修改名稱、類型、排序、啟用狀態 |
| DELETE | `/backoffice/courts/{courtId}?expectedVersion=0` | ADMIN；退役，保留資料，204 |

Compose 在本機開發環境自動建立以下帳號；只在 Email 不存在時新增，不會重設既有帳號或密碼。自行註冊的一律是 MEMBER。

| Email | 密碼 | 角色 |
| --- | --- | --- |
| `admin@booking.local` | `AdminDemo123!` | ADMIN，並包含櫃台功能權限 |
| `frontdesk@booking.local` | `StaffDemo123!` | FRONT_DESK，無場地設定權限 |

註冊 JSON：

```json
{"email":"member@example.test","password":"MemberDemo123!","name":"測試會員","phone":"0912345678"}
```

密碼至少 8 字元，最多 72 UTF-8 bytes；採 BCrypt 雜湊保存。Email 不分大小寫。姓名必填、最多 100 字元；電話 6～20 字元，限數字、空白、+、-、括號。登入 JSON 只需要 email、password。

登入回應：

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {"id":"<UUID>","email":"member@example.test","name":"測試會員","phone":"0912345678","roles":["MEMBER"]}
}
```

access token 有效 15 分鐘；refresh session 有效 7 天且不因 refresh 延長。refresh token 只透過 HttpOnly、SameSite=Lax cookie 發送，資料庫只存雜湊。每次 refresh 舊 cookie 失效；登出後當次登入所發的所有 access token 立即失效，其他裝置的登入仍有效。每次受保護請求會查詢資料庫的 session 與目前角色；停用帳號與角色變更立即生效。

瀏覽器登入／refresh／logout 需使用 `credentials: 'include'`，refresh／logout 再帶 `X-Requested-With: BookingClient`；Origin 必須符合 CORS_ALLOWED_ORIGINS。本機使用同一 hostname（都用 localhost）。前端同學未來串接時需自行加入這些設定，本次未修改前端。Postman 會自動保存 cookie，collection 已帶必要標頭。

新增場地 JSON：

```json
{"name":"第 7 場地","type":"標準 PU","status":"ACTIVE","displayOrder":7}
```

修改場地 JSON（expectedVersion 使用上一個 GET／POST／PATCH 回傳的 version）：

```json
{"status":"DISABLED","expectedVersion":0}
```

status 可用 ACTIVE、DISABLED；RETIRED 只能透過 DELETE 設定，退役後不可再修改。修改或退役前須提供最新 expectedVersion，衝突回 409 STALE_VERSION。非退役場地的名稱不可重複，違反回 409 RESOURCE_CONFLICT；不可停用或退役最後一個啟用場地，回 409 LAST_ACTIVE_COURT。所有寫入由資料庫交易處理，同時更新也會檢查版本。未來加入訂單後，停用／退役還需檢查未完成預約；目前尚無訂單模組。

錯誤固定回傳 `{code,message,path,timestamp}`。未登入或憑證失效回 401；角色不足回 403；輸入／未知欄位回 400；重複 Email 或場地、版本衝突回 409。

## 直接確認資料表

在 `backend/` 執行（以下採預設帳號與資料庫）：

```powershell
docker compose exec postgres psql -U booking -d booking -c "\dt"
docker compose exec postgres psql -U booking -d booking -c "SELECT id, name, status FROM courts ORDER BY display_order;"
docker compose exec postgres psql -U booking -d booking -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

也可用 pgAdmin/DBeaver 連線至 localhost:5433。沒有安裝資料庫 GUI 也能執行上述命令。

## 可選：本機 Java 開發

後端開發者安裝 **JDK 21**，並確保 `java -version` 可用。專案附 Maven Wrapper，不需要另外安裝 Maven。
先停止已執行的後端容器，避免 8080 衝突，再以 Maven 啟動：

```powershell
docker compose stop backend
docker compose up -d postgres
$env:DEMO_USERS_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

macOS/Linux 使用 `./mvnw spring-boot:run`；若沒有執行權限可先 `chmod +x mvnw`。
IDE 可將 `backend/pom.xml` 匯入 Maven 專案，再啟動 `BookingBackendApplication`。

## 自訂連接埠與環境設定

預設不用建立 `.env` 也可啟動。要修改設定時先：

```powershell
Copy-Item .env.example .env
```

例如電腦的 8080 已被使用，可將 `.env` 的 `API_PORT` 改為 8081，重新執行 `docker compose up --build -d postgres backend`，並把 Postman 環境的 baseUrl 改為 `http://localhost:8081`。

- DB_PORT：主機 PostgreSQL 連接埠，預設 5433。
- API_PORT：主機 HTTP 連接埠，預設 8080。
- CORS_ALLOWED_ORIGINS：前端允許來源；預設 localhost:5173、localhost:5174。
- DEMO_PAYMENTS_ENABLED：本機 Compose 預設 true，應用程式預設 false；只供管理員示範付款，沒有實際請款。
- HOLD_SECONDS：保留期限 60～900 秒，預設 600 秒。
- JWT_SECRET：至少 32 UTF-8 bytes；所有後端 instance 使用相同秘密，部署時改成隨機秘密。
- SECURE_COOKIE：本機 HTTP 為 false；部署 HTTPS 時設 true。
- DEMO_USERS_ENABLED：Compose 開發預設 true，本機 Maven/IDE 預設 false；部署設 false，不建立示範帳號。若使用既有開發資料庫，false 不會刪掉已建立的示範帳號。
- `.env` 不進 Git；`.env.example` 是共用開發範本。
- Compose 會讀 `.env`，Maven/IDE **不會自動讀**；本機 Java 自訂設定需在執行環境設定 DB_HOST、DB_PORT、DB_NAME、DB_USER、DB_PASSWORD、SERVER_PORT。
- 開發預設密碼只用於本機容器；部署時使用實際環境設定。

PostgreSQL 的初始化帳號/資料庫環境變數只對空資料目錄生效；修改既有 volume 的密碼需在資料庫中變更，不會因修改 `.env` 自動重設。

## 自動化整合測試

測試使用真 PostgreSQL，獨立 `booking_test` 資料庫，不使用 H2、不修改開發資料庫。
Docker 方式（不需要 JDK）：

```powershell
docker compose --profile test run --rm tests
```

有本機 JDK 的方式：

```powershell
docker compose --profile test up -d test-db
.\mvnw.cmd test
```

測試涵蓋 migration/初始資料、公開查詢、CORS/OpenAPI、註冊防止提權、密碼雜湊、JWT 過期／登出撤銷、refresh 輪替與併發、角色權限、場地 CRUD／版本衝突／併發及保留最後啟用場地。
第三階段另含台北日期與整點邊界、例外日優先、休館、尖離峰／假日價格、金額與規則驗證、價格版本保存、營業設定與費率同時修改的衝突保護。第四至六階段加入預約、訂單、櫃台、封場及示範付款測試，完整套件共 67 個測試。
test-db 使用獨立 tmpfs；測試拒絕操作名稱不以 `_test` 結尾的資料庫。測試報告在 `target/surefire-reports/`。

## 停止與保留資料

```powershell
docker compose --profile test down
```

停止並移除本專案容器；開發資料保留在 `booking-dev_postgres-data` named volume，下次啟動仍在。
不要加 `-v`，除非確定要刪除此專案的本機資料。

## 常見問題

| 狀況 | 處理 |
| --- | --- |
| Docker daemon 無法連線 | 啟動 Docker Desktop，確認使用 Linux containers，再重試 |
| 連接埠已被占用 | 調整 `.env` 的 API_PORT/DB_PORT/TEST_DB_PORT；Postman 同步調整 |
| 第一次建置較久 | 正在下載映像與依賴；查看終端輸出與 `docker compose logs backend` |
| 資料庫連不上，後端啟動失敗 | 查看 `docker compose ps`、`docker compose logs postgres`；本機 Java 檢查使用 5433 |
| API 健康檢查回 503 | 執行中的後端無法查詢資料庫；檢查 postgres 容器 |
| Flyway checksum 錯誤 | 不要修改已共用的 migration；新增新的 migration，與同學確認版本編號 |
| refresh/logout 回 403 | 確認 X-Requested-With 為 BookingClient；瀏覽器來源需在允許清單 |
| refresh 回 401 | 先登入取得 cookie，確認 cookie jar 啟用且 hostname 相同；過期後重新登入 |
| 場地修改回 409 | 重新 GET 取得最新 version；同名或最後啟用場地也會回 409 |
| 後端程式變更沒有生效 | Docker 方式需再次 `docker compose up --build -d backend` |

## 分階段與分工

本專案以課程作業的簡單 demo 為目標。你負責的六個階段已完成；真正 ECPay、收款與退款由另一位同學串接。改期審核、QR Code、通知、季租、進階報表等延伸流程延後。

Git 工作流程目前只在本機 commit；除非使用者另外明確要求，不 push 到遠端。

1. **本階段已實作**：可重現環境、DB 建立、migration、公開場地查詢、健康檢查、Postman/Swagger/測試。
2. **第二階段已實作**：會員註冊／登入、JWT/RBAC、refresh/logout、個人資料及管理員場地 CRUD。
3. **第三階段已實作**：場館共用營業日曆、例外日、版本化定價及 availability 日曆／報價。
4. **第四階段已實作**：hold TTL、訂單與 PostgreSQL 防撞、基本取消、金流確認 Java 介面，及營業調整／場地退役的占用保護。
5. **第五階段已實作**：共用每日棋盤、櫃台代訂／聯絡資料更新、手動報到／撤銷、維修封場與釋放。
6. **第六階段已實作**：可關閉的 ADMIN 示範付款、完整 Postman demo、金流／前端交接及可重現啟動驗收。
5. **另一位同學**：付款/收款、ECPay 回調、退款試算/政策/審核/執行與財務報表；共用同一後端，不另建訂單或登入。

新模組需在 `security/SecurityConfiguration.java` 明確設定路徑權限（目前未開發路徑一律拒絕），並由 SecurityContext 取得使用者 ID。ECPay callback 的第三方驗證由金流同學實作，不能依賴會員 JWT。

開發者各自新增 migration，合併前協調下一個 V 編號；不要改已執行且共用的 migration。
前端同學只需執行快速啟動步驟與匯入 Postman，即可取得一致的 API 測試環境。

官方參考：[Spring Boot migration](https://docs.spring.io/spring-boot/how-to/data-initialization.html)、[PostgreSQL Docker image](https://hub.docker.com/_/postgres)、[springdoc](https://springdoc.org/)。
