# 後端作業 demo 驗收紀錄

驗收日期：2026-10-04（台北時間）。範圍為你負責的第一至第六階段；前端未修改，未串接真實 ECPay 或執行實際退款。

## 後端測試

`docker compose --profile test run --rm tests`：**67 tests，0 failures，0 errors，0 skipped**。

使用獨立 booking_test 資料庫。覆蓋會員／JWT／RBAC、場地、營業／費率邊界、會員同時搶位、會員與櫃台同時搶位、批次交易回復、價格快照、到期釋放、重試、取消、報到／撤銷、封場及示範付款開關。

付款測試包含金額不符／零金額／額外注入欄位、會員與櫃台禁止自行付款確認、同時重送只更新一次、到期與未付款取消後拒絕遲到通知、已付取消仍保留 PAID／退款 PENDING_REVIEW。

## Postman 全流程

建立獨立 Compose 專案 booking-verify-final，使用全新的 PostgreSQL volume（API 8081、DB 5435），驗證首次啟動後依序執行六份 collection。與日常開發資料庫完全分開。

| Collection | Requests | Assertions | 失敗 |
| --- | ---: | ---: | ---: |
| Phase 1 | 7 | 14 | 0 |
| Phase 2 | 30 | 60 | 0 |
| Phase 3 | 31 | 52 | 0 |
| Phase 4 | 29 | 43 | 0 |
| Phase 5 | 30 | 45 | 0 |
| Phase 6 Demo | 25 | 39 | 0 |
| 合計 | 152 | 253 | 0 |

全部 Newman exit code=0。另外在日常 localhost:8080 的開發環境完成 Phase 6 Demo：25 requests／39 assertions，0 失敗。

## 資料庫與交付檢查

- 全新 PostgreSQL 自動建庫；Flyway V1～V7 全部 success=true。
- 首次健康檢查 database=UP、schemaVersion=7、courtCount=6。
- 完整 Postman 流程後 slot_claims=0、ACTIVE 場地數=6。
- 開發資料庫由 V6 升級至 V7，既有資料保留，健康檢查成功。
- Git 比對前端 src、package.json、package-lock.json、index.html、vite.config.js，無變更。
- 各階段 commit 在本機 limeng 分支，沒有 push 遠端。

## 交付界線

DEMO 付款只模擬訂單狀態，不代表真實請款；已付取消只標記退款待處理，不代表退款完成。真實 ECPay、現場收款記帳、退款政策／交易及財務報表由金流同學整合；前端同學需自行把假資料換成 API 呼叫。

本紀錄針對本機作業 demo。沒有實作 Redis、QR Code、季租、改期審核、通知或進階報表。
