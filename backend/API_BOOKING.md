# 第四階段：預約與訂單核心（作業 demo）

所有路徑加 `/api/v1`。會員 API 需 MEMBER 的 Bearer token；只能操作自己的資料。ADMIN 與 FRONT_DESK 使用 [backoffice API](API_BACKOFFICE.md)。前端不修改，前端同學依這份合約串接。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| POST | `/holds` | 原子保留 1～12 個未來整點時段，201 |
| GET | `/holds/{holdId}` | 查保留、期限、金額與轉出的訂單 |
| DELETE | `/holds/{holdId}` | 釋放未轉訂單的保留，204 |
| POST | `/orders` | 將 hold 轉成待付款訂單，201 |
| GET | `/orders?scope=all&page=0&size=20` | 本人訂單，scope 可用 all／upcoming／past |
| GET | `/orders/{orderId}` | 本人訂單明細 |
| POST | `/orders/{orderId}/cancellations` | 整張取消，釋出時段並保留歷史 |

POST holds／orders 需 `Idempotency-Key` 標頭，8～100 字元（英數及 . _ : -）。同 key 與同內容重試回同一資源目前狀態，不延長期限；同 key 不同內容回 409。新一次操作使用新 key。其他會員資料回 404。

## 保留與建立訂單

先查 `/venue` 取得後端台北日期，再查 `/availability?date=YYYY-MM-DD` 選 AVAILABLE 格子。startAt 是 ISO 時間含 offset，只接受台北整點，一格固定 1 小時。

```json
{"items":[{"courtId":"00000000-0000-0000-0000-000000000001","startAt":"2026-10-10T10:00:00+08:00"}]}
```

伺服器算價格，回 id、status、expiresAt、totalAmount、pricingVersion、items。每會員最多一個 ACTIVE hold；重複 item 回 400，任何一格不合法或被占用，整批失敗。先前失敗的請求不保存 key，可修正後重試。

預設 TTL 10 分鐘（HOLD_SECONDS 可設 60～900 秒）。PostgreSQL 同一交易保護全部格子，另有 `(court_id,start_at)` 主鍵防撞，簡單 demo 不需另外安裝 Redis。每 30 秒清理逾期資料；寫入或本人查詢前也會清理，公開日曆立即忽略已逾期占用。

```json
{"holdId":"<hold UUID>","customer":{"name":"測試會員","phone":"0912345678"},"paymentMethod":"CREDIT_CARD"}
```

建立訂單保存原 hold 的單價與 pricingVersion，不延長原期限。hold 變 CONSUMED；order 是 PENDING_PAYMENT／UNPAID，時段占用移交訂單。改費率不重算既有 hold／order。訂單回 id、orderNumber、source、status、paymentStatus、refundStatus、customer、notes、totalAmount、version、items 等欄位。

availability 已改為 `bookingEnabled:true`、`inventoryMode:LIVE`；包含 AVAILABLE／HOLDING／BOOKED／MAINTENANCE／CLOSED／PAST，不公開會員、顧客、訂單、hold ID 或維修理由。同學依 serverTime 與 expiresAt 顯示倒數即可。

## 取消與營業保護

```json
{"reason":"demo 取消"}
```

目前只做整張訂單取消；已開始的預約不可取消，已報到者須先由櫃台撤銷報到。未付為 NOT_REQUIRED，已付為 PENDING_REVIEW，金額與付款狀態保留；這不是退款成功，退款試算與執行由金流同學處理。同一已取消訂單再取消回原狀態。訂單新增 checkedInAt 欄位，null 表示未報到。

停用／退役場地或縮減營業時間不得排除未來有效 hold／order，回 409 COURT_OCCUPIED／OCCUPIED_SCHEDULE。

維修封場同樣受占用保護。簡單 demo 的 ADMIN 示範付款另見 [DEMO_GUIDE.md](DEMO_GUIDE.md)，僅更新示範狀態，不會向綠界請款。

## 金流同學整合

共用本專案，在驗證 ECPay 簽章、交易編號與實收金額後，注入 `PaymentConfirmationService` 並呼叫：

```java
confirmVerifiedPayment(orderId, paidAmount, providerTransactionReference);
```

金額必須等於訂單 totalAmount；成功會 CONFIRMED／PAID 並移除占用 TTL。同筆交易回調是冪等的。會員 HTTP API 無法直接標記已付款；不實作 ECPay 密鑰、付款頁或退款接口。遲到的付款若訂單已逾期，回 ORDER_NOT_PAYABLE；金流同學須處理補償退款，不能復活已售給其他人的格子。

## 測試與環境

V6 自動建 hold、order、item、占用與請求 key 表。沿用 `docker compose up --build -d postgres backend`。Postman 匯入 `booking-phase4.postman_collection.json`，沿用 Booking Local；流程建立專用 demo 場地和會員，最後取消／釋放預約並退役測試場地。

獨立 PostgreSQL 測試涵蓋多人搶同時段、批次原子性、重試、價格快照、期限轉移與逾期、所有權、取消釋出、付款確認金額與營業修改保護。
