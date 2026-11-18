# Neon PostgreSQL 設定與驗收

已連結的 Neon 專案為 booking-demo，project ID `long-morning-75525692`，branch `production`。本次只使用 PostgreSQL，保留現有 Spring Boot／Java／JDBC、Flyway 與 JWT。Neon 不會透過 `neon deploy` 發佈 Vue 網站或 Java API；這兩個應用目前仍在本機，後續可部署 Azure。

## 啟動

開啟 Docker Desktop，在專案根目錄執行：

```powershell
cd D:\School\code\booking
npm.cmd run backend:neon
npm.cmd run dev
```

`backend:neon` 會讀取根目錄既有 `.env`，沒有時讀 `.env.local`，驗證兩個連線變數，首次自動產生 JWT_SECRET 並保存在同一個已忽略的檔案，再啟動獨立的 booking-neon Compose 專案。原本 booking-dev 的本機 PostgreSQL 不受影響。

- Vue：http://localhost:5173（依 Vite 顯示網址為準）。
- Neon 後端：http://localhost:8081/api/v1/health。
- Swagger：http://localhost:8081/swagger-ui.html。
- Postman 的 baseUrl 改成 `http://localhost:8081`。

後端第一次啟動需等待 migration。停止 Neon 後端執行 `npm.cmd run backend:neon:stop`；前端按 Ctrl+C。

前端 Vue 元件沒有修改，原有畫面仍使用假資料。Neon 的實際資料流程由 Spring Boot API 提供，前端同學需另行串接；不要從 Vue 直接連 PostgreSQL，也不要使用 VITE_ 前綴暴露資料庫密碼。

## CLI 與環境變數

初始化時使用：

```powershell
neon.cmd init -y --org-id org-calm-night-65411364 --project-id long-morning-75525692 --branch production --services postgres
neon.cmd deploy
```

本機第一次 init 的 plugin 安裝失敗，改用 CLI 支援的 skills 模式成功完成：

```powershell
neon.cmd init -y --org-id org-calm-night-65411364 --project-id long-morning-75525692 --branch production --services postgres --skill neon --skill neon-postgres --agent codex
```

CLI 已登入。換電腦時，先安裝 `npm.cmd i -g neon@latest`，執行 `neon.cmd login` 完成瀏覽器登入，再初始化與 deploy。Windows 若 git 不在 PATH，先安裝 Git 或將現有 Git 的 cmd 目錄加到當次終端 PATH，才能讓 agent skills 的下載流程找到 Git。

`neon deploy` 的實際結果：

```text
No changes — branch production already matches the policy.
Utilized services: Postgres
Pulled 3 Neon variables into .env.local
Postgres: DATABASE_URL, DATABASE_URL_UNPOOLED
Branch: NEON_BRANCH
```

| 變數 | 使用方式 |
| --- | --- |
| DATABASE_URL | 由 Neon URL 轉成 JDBC URL，供 Hikari／JPA／JdbcTemplate 使用 pooled endpoint |
| DATABASE_URL_UNPOOLED | 轉成獨立 JDBC URL，供 Flyway 使用 direct endpoint |
| NEON_BRANCH | CLI 的分支資訊，本身不是資料庫連線參數 |
| JWT_SECRET | 本機啟動工具產生；雲端部署請透過秘密設定提供 |

啟用 `SPRING_PROFILES_ACTIVE=neon` 時，NeonDatabaseInitializer 才會將 Postgres URI 轉成 Spring DataSource／Flyway 設定。保留 TLS、百分比編碼密碼與必要 query 參數，將 libpq 的 channel_binding 轉成 JDBC 的 channelBinding。缺少變數、關閉 TLS、pooled migration URL 或混用不同 endpoint／database／role 均會明確失敗，不會悄悄連到本機資料庫，錯誤訊息不包含 URL／密碼。

一般本機與 test profile 保持原本設定。Neon profile 的連線池最多 5 條，minimum-idle=0、keepalive=0，並停用每 30 秒清理的排程；現有預約讀寫會即時處理到期，公開日曆也會忽略逾期占用，避免閒置時持續查詢雲端資料庫。

`.neon`、`.env`、`.env.local` 均已由 Git 忽略。不要把它們或密碼加入 commit；換電腦重新登入／deploy 取得對應環境變數。`neon.ts`、套件鎖定檔與程式設定可正常版本控制。

## 重跑端到端驗證

後端啟動後，在專案根目錄執行：

```powershell
npm.cmd run check:neon
```

此指令透過 HTTP API 建立兩個隨機測試會員，依序測試註冊／登入、讀場地、保留、重試、防撞、訂單、所有權、取消、重新保留及歷史。結束時釋放本次占用，留下已取消訂單作為持久化證據；不會刪除其他人的資料。需要原始範例營業時間內有未來可用時段。

驗證結果只把非機密摘要寫入忽略的 `tmp/neon-e2e.json`，不保存密碼或 token。可用 NEON_API_URL 指定其他驗收 API 位址。

完整 Java 測試仍使用獨立的本機 booking_test 資料庫：

```powershell
cd backend
docker compose --profile test run --rm tests
```

## 2026-10-22 驗收

- `npm.cmd run build`：Vite 8.3.0，188 modules transformed，built in 1.25s。
- 後端：74 tests，0 failures／errors／skipped，BUILD SUCCESS。
- 短期 Neon production 副本：28 項 HTTP 端到端檢查成功；直接 SQL 查到 V1～V7 全部成功、6 個場地、CANCELLED／UNPAID 訂單及 slot_claims=0。
- 指定 production：相同 28 項檢查通過，直接 SQL 再次確認 V1～V7 全成功、6 個場地、已取消訂單持久化及 slot_claims=0。留下兩個本次驗證用會員及一張已取消訂單，不留下有效預約。
- Vue 啟動：Vite ready，頁面與 `/src/main.js` 都回 HTTP 200。
- 掃描 dist 的 9 個檔案：未發現資料庫 URL、密碼或 JWT_SECRET，credentialLeaks=0。

臨時驗收 branch `booking-neon-verify-20261022205535` 在驗收後刪除；root 的 `.neon`／`.env.local` 一直維持連到 production。只清理本次驗收資源，本機 booking-dev 的資料庫 volume 保留。

初次 plugin 安裝失敗已以官方 skills 模式解決。npm 安裝提示 2 個 high severity advisories，稽核對應既有前端的 vite 與 source-map-js；此次沒有為修復相依套件而升級既有前端。這項警告不影響已完成的 build 與 API 驗收，後續應另外安排相依更新。

## Azure 後端部署

後端已部署至 Azure App Service F1 免費方案，直接上傳 Java 21 JAR。API 為 https://ccu-booking-api-1fd823.azurewebsites.net；重部署與完整驗證見 [AZURE_DEPLOYMENT.md](AZURE_DEPLOYMENT.md)。Neon 仍是 PostgreSQL 資料庫，不另外建立 Azure PostgreSQL。

Azure 的應用程式設定包含 SPRING_PROFILES_ACTIVE=neon、DATABASE_URL、DATABASE_URL_UNPOOLED、獨立 JWT_SECRET 與 SECURE_COOKIE=true；示範帳號與模擬付款停用。跨站 refresh cookie 仍需在前後端整合時處理 SameSite／同源代理設定；目前 Vue 的假資料不會因資料庫接通而自動改為 API。
