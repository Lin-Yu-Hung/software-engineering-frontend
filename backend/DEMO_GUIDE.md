# 羽球預約系統：後端 demo 與同學交接

你負責的簡單 demo 範圍已完成：Spring Boot／Java／PostgreSQL、會員驗證、場地、營業日曆、定價、預約防撞、訂單、取消、櫃台代訂／報到、維修封場。前端未修改。真實綠界付款、收款紀錄、階梯退款與財務報表仍由金流同學負責。

## 1. 啟動（同學 pull 後也相同）

安裝並啟動 Docker Desktop，使用 Linux containers。在專案根目錄開 PowerShell：

```powershell
cd backend
docker compose up --build -d postgres backend
docker compose logs -f backend
```

看到 Started BookingBackendApplication，按 Ctrl+C 離開 log。第一次需下載映像／依賴；不用手動安裝 Java、Maven、PostgreSQL，也不用自己建資料表。Flyway 自動升級到 V7，建立六個範例場地、營業時間、費率與本機 demo 管理員／櫃台帳號。既有資料庫重啟時保留資料，每位同學各自建立本機 DB。

- 健康檢查：<http://localhost:8080/api/v1/health>，database=UP、schemaVersion=7。
- Swagger：<http://localhost:8080/swagger-ui.html>。
- PostgreSQL：localhost:5433，DB／user 都是 booking，開發密碼 booking_dev_password。
- 管理員：admin@booking.local／AdminDemo123!。
- 櫃台：frontdesk@booking.local／StaffDemo123!。

服務停止：`docker compose stop backend postgres`；下次 `docker compose up -d postgres backend`。一般不要加 `down -v`，因為會刪除本機資料庫 volume。

8080／5433 已占用時，可複製 `.env.example` 成 `.env`，改 API_PORT／DB_PORT。Postman 的 baseUrl 也改為對應 API_PORT；DB_PORT 是電腦連接資料庫用的 port，容器間仍走 5432。

## 2. 五分鐘 Postman demo

Import 以下兩個檔案：

- `postman/booking-phase6.postman_collection.json`（Collection 名稱 **Booking Backend - Phase 6 Demo**）。
- `postman/local.postman_environment.json`（已有 Booking Local 就沿用）。

右上角選 Booking Local，baseUrl=http://localhost:8080。以 Runner 按順序跑全部 **25 個請求**。若 collection 未出現，確認匯入的是 collection 檔，而非只有 environment，清掉左側搜尋條件後看 Collections。

流程包含：

1. 檢查資料庫、管理員／櫃台登入，建立 demo 專用場地。
2. 註冊會員，查空場，保留十分鐘，轉成待付款訂單。
3. 展示會員不能偽造付款、金額不符遭拒。
4. 管理員 **DEMO 模擬付款**，訂單變 CONFIRMED／PAID，重複通知不重複更新。
5. 櫃台棋盤查看、手動報到、撤銷誤報到。
6. 會員取消，場地立即釋放，已付訂單標示退款 PENDING_REVIEW。
7. 維修封場，公開日曆顯示 MAINTENANCE，解除封場，退役 demo 場地。

25 個請求附 39 個斷言；403／409 是故意測試的預期回應。日期與可用整點依後端台北時間自動挑選，需在原本範例營業時間 08～22 點與未休館設定下執行；demo 時段若被改成休館或即將開始，重新選其他開放日期／時段。最後不留有效占用，但會保留註冊會員、取消訂單及退役場地，故 courtCount 可能大於 6。

想查看櫃台「現場／電話代訂」完整流程，另跑 `booking-phase5.postman_collection.json`；營業／定價設定見 phase3。phase3 會暫時調整設定，先完成 demo 清理後再跑，避免與有效預約衝突。不要同時跑多個會修改全館設定的 Runner。

## 3. 示範付款的開關

```http
POST /api/v1/demo/orders/{orderId}/confirm-payment
Authorization: Bearer <ADMIN token>
Content-Type: application/json

{"amount":300}
```

amount 必須等於訂單 totalAmount。回應 `{"mode":"DEMO","order":{...}}`。這只是模擬資料狀態，不會向綠界請款或退款；退款 PENDING_REVIEW 只表示待金流同學處理。

應用程式預設停用；本機 Compose 為方便作業 demo 設 DEMO_PAYMENTS_ENABLED=true。需要關閉時在 backend/.env 設 `DEMO_PAYMENTS_ENABLED=false`，再 `docker compose up -d backend` 重建容器設定；ADMIN 呼叫會收到 404／DEMO_PAYMENTS_DISABLED。整合真實金流時關閉示範 API。member／frontdesk 均無此付款確認權限。

保留預設 600 秒，可在 .env 設 HOLD_SECONDS=60～900；Compose 重建 backend 後生效。到期由排程每 30 秒清理，所有預約寫入也會先清理；公開日曆不會顯示已到期占用。作業 demo 直接使用 PostgreSQL 的交易鎖與唯一主鍵，不需要 Redis。

## 4. 給金流同學

共用同一個 backend Maven 專案、資料庫與 JWT/RBAC。建議在新 `payment` package 製作 ECPay 發起交易／已驗證通知、現場收款及退款。

付款金額與訂單號從 BookingRepository 查出，不能信任前端傳金額。完成金流來源、簽章、交易狀態及金額驗證後，呼叫：

```java
paymentConfirmationService.confirmVerifiedPayment(orderId, paidAmount, providerTransactionId);
```

這個 Java 服務會用共用交易鎖驗證訂單狀態及精確金額，更新 CONFIRMED／PAID 並移除時段 TTL。相同付款編號可重試；不會延長未付款訂單的期限。逾期或取消的未付款訂單不可因遲到通知重新搶回場地，回 ORDER_NOT_PAYABLE；金流同學負責補償退款／人工處理。

現場代訂是 CONFIRMED／UNPAID，付款方式 CASH／ONSITE_CARD；真實收款 API 由金流同學驗證／記帳後呼叫同一服務。報到只記錄到場，不表示已收款。已付訂單取消後 paymentStatus 保持 PAID、refundStatus=PENDING_REVIEW；退款同學需另存 refund transactions、政策快照與退款狀態，勿直接把已付金額清零。

金流服務若也修改排程，應先呼叫 BookingService.lockAndExpire()，再寫訂單／占用，保持同一個交易內與現有鎖順序。不要重新建立登入或第二套櫃台後台，也不要修改已執行的 V1～V7 migration；新增資料表請從 V8 起。

## 5. 給前端同學

本次只開 API，Vue 還是原本假資料，所以目前不能從既有 Vue 畫面完成真實後端預約。請由前端同學依 [README](README.md)、[營業／定價](API_PHASE3.md)、[保留／訂單](API_BOOKING.md)、[櫃台](API_BACKOFFICE.md) 串接。

- 場地用後端 UUID，不能直接傳前端假資料 c1／c2。
- 對會員流程依序呼叫 availability → holds → orders → 同學的金流 API；示範付款限管理員操作。
- 登入／refresh／logout 使用 credentials: include；refresh／logout 帶 X-Requested-With: BookingClient。API 與前端 hostname 同用 localhost。
- accessToken 放 Bearer header；寫入 hold／order／代訂／封場附唯一 Idempotency-Key，重試沿用同一 key。
- 更新資料用最新 version；遇到 409 更新棋盤／資料後重選，避免沿用過期畫面。
- 前端可自行把 BOOKED／HOLDING／MAINTENANCE 映射到既有日曆色塊；公開 API 不回客人資料，櫃台 timetable 要 staff token。

前端啟動仍是專案根目錄 `npm install`、`npm run dev`；後端另在 backend 目錄跑 Compose。CORS 預設允許 localhost:5173／5174。

## 6. 測試與範圍

```powershell
cd backend
docker compose --profile test run --rm tests
```

測試使用獨立 booking_test 資料庫，不修改開發資料。涵蓋註冊／JWT、角色權限、營業與費率邊界、兩人搶同時段、批次回復、價格快照、到期、重試、取消、代訂、報到、維修封場及示範付款開關。

已驗收 67 個後端測試，以及六階段 Postman 合計 152 個請求／253 個斷言；全部通過。全新資料庫的建立與完整 demo 已另外實際驗證，詳見 [VALIDATION.md](VALIDATION.md)。

為符合簡單作業 demo，沒有再擴充 Redis、改期審核、通知推播、季租、QR Code、部分報到／部分退款、進階報表。真正 ECPay 與退款仍是另一位同學的工作。版本只 commit 在本機，沒有 push 遠端。
