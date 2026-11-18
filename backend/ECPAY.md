# 綠界金流串接手冊（信用卡測試刷卡）

本文件說明後端如何串接**綠界全方位金流（AIO）信用卡付款**、本機如何測試刷卡，以及常見問題。
目前使用綠界**測試環境**，不會真的扣款。

> 官方文件：[信用卡一次付清](https://developers.ecpay.com.tw/2866/)、[付款結果通知](https://developers.ecpay.com.tw/2878/)、[檢查碼機制](https://developers.ecpay.com.tw/?p=2902)、[測試介接資訊](https://developers.ecpay.com.tw/2856/)

---

## 1. 目前完成度

| 項目 | 狀態 |
| --- | --- |
| 產生已簽章的綠界付款表單 | ✅ 完成 |
| 接收綠界付款通知並入帳 | ✅ 完成，已用測試卡實際刷卡驗證 |
| 付款完成後導回前端 | ✅ 後端完成（前端頁面尚未串接） |
| 異常處理（過期才付款、金額不符、重複付款） | ✅ 標記為 `NEEDS_REFUND` |
| 前端結帳頁、我的訂單頁串接 | ❌ 尚未完成（`BookingCheckout.vue`、`MyBookings.vue` 仍是假資料） |
| 後台顯示待退款、呼叫綠界退款 API | ❌ 尚未完成 |
| 部署到 Azure | ❌ 尚未部署 |

---

## 2. 付款流程

```
前端 / Swagger              後端 (8080)                          綠界
 │ ① POST /holds              鎖定時段（10 分鐘）
 │ ② POST /orders             建立待付款訂單
 │ ③ POST /orders/{id}/payments/ecpay
 │                            產生 MerchantTradeNo、CheckMacValue
 │ ◀── 回傳 { action, fields }
 │ ④ 用 fields 組成 <form> POST 到 action ─────────────────▶ 綠界刷卡頁
 │                                                            │ 刷卡成功
 │                          ⑤ /notify ◀── 綠界伺服器 POST ────┤
 │                             驗證簽章 → 入帳 → 回 "1|OK"    │
 │                          ⑥ /result ◀── 使用者瀏覽器 POST ──┘
 │ ◀── 303 轉址到前端 /my-bookings?orderId=…&payment=success
```

**重點**

- **⑤ `/notify` 是入帳的主要依據**：由綠界伺服器直接呼叫，不經過前端；使用者付完款關掉瀏覽器也會入帳。沒收到 `1|OK` 時綠界會重送（5～15 分鐘後，當天最多 4 次）。
- **⑥ `/result` 是給使用者看結果用的**：使用者可能關掉分頁而不送達。它同樣有簽章，所以也會入帳；兩邊誰先到都行，重複處理只會入帳一次。
- 前端**永遠不能**自己判斷「付款成功」，一律以後端訂單狀態為準（`GET /orders/{id}`）。

---

## 3. API

| 方法 | 路徑 | 權限 | 呼叫者 | 說明 |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/orders/{orderId}/payments/ecpay` | MEMBER | 前端 | 產生付款表單欄位，每次呼叫產生新的 MerchantTradeNo |
| POST | `/api/v1/payments/ecpay/notify` | 公開（驗簽章） | **綠界伺服器** | 付款結果通知（ReturnURL），回應純文字 `1|OK` |
| POST | `/api/v1/payments/ecpay/result` | 公開（驗簽章） | **使用者瀏覽器** | 付款完成導回（OrderResultURL），303 轉址到前端 |

`notify`、`result` 不需要 JWT，安全性靠 **CheckMacValue 簽章**；在 Swagger 手動按只會得到 `400` 或 `payment=unknown`，這是正常的。

**發起付款的回應範例**

```json
{
  "action": "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
  "merchantTradeNo": "P261118233746BC9E3FF",
  "fields": {
    "MerchantID": "3002607",
    "MerchantTradeNo": "P261118233746BC9E3FF",
    "MerchantTradeDate": "2026/11/18 23:37:46",
    "PaymentType": "aio",
    "TotalAmount": "350",
    "TradeDesc": "羽球場地預約",
    "ItemName": "羽球場地 1 小時",
    "ReturnURL": "https://…/api/v1/payments/ecpay/notify",
    "OrderResultURL": "http://localhost:8080/api/v1/payments/ecpay/result",
    "ClientBackURL": "http://localhost:5173/my-bookings",
    "ChoosePayment": "Credit",
    "EncryptType": "1",
    "CheckMacValue": "…"
  }
}
```

**可能的錯誤**

| 代碼 | HTTP | 原因 |
| --- | --- | --- |
| `ORDER_NOT_FOUND` | 404 | 訂單不存在，或不是自己的訂單 |
| `ORDER_NOT_PAYABLE` | 409 | 訂單已付款、已取消或已過期 |
| `PAYMENT_WINDOW_TOO_SHORT` | 409 | 保留剩不到 3 分鐘，請重新選時段（避免付款途中保留到期） |
| `ECPAY_NOT_CONFIGURED` | 503 | 沒有設定 `ECPAY_RETURN_URL` |

---

## 4. 前端串接方式（待實作）

前端拿到回應後，**必須用 form POST**（不能用 fetch/axios）把使用者帶到綠界：

```js
const { action, fields } = await api.post(`/orders/${orderId}/payments/ecpay`)
const form = document.createElement('form')
form.method = 'POST'
form.action = action
for (const [name, value] of Object.entries(fields)) {
  const input = document.createElement('input')
  input.type = 'hidden'; input.name = name; input.value = value
  form.appendChild(input)
}
document.body.appendChild(form)
form.submit()
```

付款後使用者會回到 `/my-bookings?orderId=…&payment=success|failed|unknown`。
前端應讀取 `orderId` 後呼叫 `GET /api/v1/orders/{orderId}` 顯示真實狀態；若 notify 比瀏覽器晚到，狀態可能還是 `PENDING_PAYMENT`，可間隔 2 秒重查 2～3 次。

---

## 5. 設定（環境變數）

| 變數 | 預設值 | 說明 |
| --- | --- | --- |
| `ECPAY_RETURN_URL` | （空） | **必填**。綠界伺服器呼叫的通知網址，必須是公開網址。留空則停用綠界付款 |
| `ECPAY_ORDER_RESULT_URL` | `http://localhost:8080/api/v1/payments/ecpay/result` | 付款後瀏覽器導回的後端網址 |
| `ECPAY_FRONTEND_RESULT_URL` | `http://localhost:5173/my-bookings` | 後端驗證完後，把使用者送回的前端頁面 |
| `ECPAY_MERCHANT_ID` | `3002607` | 商店代號（預設為綠界公開測試商店） |
| `ECPAY_HASH_KEY` | `pwFHCqoQZGmho4w6` | 測試商店 HashKey |
| `ECPAY_HASH_IV` | `EkRm7iFT261dpevs` | 測試商店 HashIV |
| `ECPAY_CHECKOUT_URL` | `https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5` | 測試環境付款網址 |
| `ECPAY_MIN_REMAINING_SECONDS` | `180` | 保留剩餘秒數低於此值就拒絕發起付款 |
| `ECPAY_ACCEPT_SIMULATED` | `false` | 是否接受綠界廠商後台的「模擬付款」（沒有實際扣款） |

> 預設的商店代號與金鑰是綠界**公開**的測試資料。正式上線必須換成正式商店資料，並放在環境變數，不可寫進程式或 commit。

---

## 6. 本機測試環境

### 6.1 啟動後端（Docker）

```powershell
cd backend
docker compose up --build -d postgres backend
```

### 6.2 開啟 ngrok

綠界伺服器連不到你的 `localhost`，所以 notify 需要 ngrok 提供公開網址。

1. 到 <https://dashboard.ngrok.com/signup> 註冊（免費方案即可），複製 **Your Authtoken**
2. 綁定帳號（只需一次）：`ngrok config add-authtoken <你的token>`（token 不要外流或 commit）
3. 在 dashboard 的 **Domains** 領取免費固定網域
4. 開通道（視窗要一直開著）：

   ```
   ngrok http --url <你的網域>.ngrok-free.dev 8080
   ```

### 6.3 設定 `backend/.env`

複製 `.env.example` 為 `.env`（已被 git 忽略），填入：

```
ECPAY_RETURN_URL=https://<你的網域>.ngrok-free.dev/api/v1/payments/ecpay/notify
```

套用設定：`docker compose up -d backend`（只改 `.env` 不需要 `--build`）。

**確認通道**：瀏覽器開 `https://<你的網域>.ngrok-free.dev/api/v1/health`，看到 `"status":"UP"` 即可。

---

## 7. 手動測試刷卡（Swagger）

開啟 <http://localhost:8080/swagger-ui.html>。**步驟 3～6 需在 10 分鐘內完成**（保留時限），且發起付款時至少要剩 3 分鐘。

1. **註冊**：`POST /api/v1/auth/register`

   ```json
   { "email": "paytest1@example.com", "password": "Test12345!", "name": "測試會員", "phone": "0912345678" }
   ```

2. **登入**：`POST /api/v1/auth/login`，複製 `accessToken`，按右上角 **Authorize** 貼上（token 15 分鐘過期）
3. **保留時段**：`POST /api/v1/holds`，Idempotency-Key 填 `hold-test-0001`（每次換新）

   ```json
   { "items": [ { "courtId": "00000000-0000-0000-0000-000000000001", "startAt": "2026-11-20T10:00:00+08:00" } ] }
   ```

   日期請改成未來日期；營業時間 08:00～22:00。複製回應的 `id`（holdId）。
4. **建立訂單**：`POST /api/v1/orders`，Idempotency-Key 填 `order-test-0001`

   ```json
   { "holdId": "<holdId>", "customer": { "name": "測試會員", "phone": "0912345678" }, "paymentMethod": "CREDIT_CARD" }
   ```

   複製回應的 `id`（orderId，**不是** `orderNumber`）。
5. **發起付款**：`POST /api/v1/orders/{orderId}/payments/ecpay`，複製**整段**回應 JSON
6. **到綠界刷卡**：用瀏覽器開啟 [`backend/tools/ecpay-test.html`](tools/ecpay-test.html)，貼上 JSON，按「送到綠界」

   | 欄位 | 測試值 |
   | --- | --- |
   | 卡號 | `4311-9522-2222-2222`（或 `4311-9511-1111-1111`） |
   | 有效期限 | 任何未來月份/年份 |
   | 安全碼 | 任意三碼，例如 `222` |
   | 3D 簡訊驗證碼 | `1234` |

7. **確認結果**
   - 瀏覽器網址出現 `payment=success`（前端沒開會顯示「拒絕連線」，不影響入帳）
   - ngrok 視窗出現 `POST /api/v1/payments/ecpay/notify 200 OK`
   - `GET /api/v1/orders/{orderId}` 顯示 `"status": "CONFIRMED"`、`"paymentStatus": "PAID"`

---

## 8. 資料庫

### 8.1 `payment_transactions`（V8 新增）

每次發起付款一筆。綠界不接受重複的 MerchantTradeNo，所以重新付款會產生新的一筆。

| 欄位 | 說明 |
| --- | --- |
| `merchant_trade_no` | 送給綠界的交易編號，`P` + 19 碼英數，共 20 碼 |
| `amount` | 發起時的訂單金額 |
| `status` | 見下表 |
| `gateway_trade_no` | 綠界交易編號，付款成功後也會寫入訂單的 `paid_reference` |
| `rtn_code` / `rtn_msg` | 綠界回傳碼（`1` = 成功）與訊息；異常時存原因代碼 |
| `raw_notify` | 綠界通知的原始內容，方便對帳除錯 |

| status | 意義 |
| --- | --- |
| `INITIATED` | 已產生付款表單，尚未收到結果 |
| `PAID` | 付款成功，訂單已改為 `CONFIRMED / PAID` |
| `FAILED` | 刷卡失敗，訂單仍可重新付款 |
| `NEEDS_REFUND` | **錢已扣但訂單無法接受**，需要人工退款（見 8.2） |

### 8.2 什麼情況會 `NEEDS_REFUND`

| 情況 | `rtn_msg` |
| --- | --- |
| 付款成功時保留已過期（訂單 `EXPIRED`） | `ORDER_NOT_PAYABLE` |
| 訂單已取消後才付款成功 | `ORDER_NOT_PAYABLE` |
| 同一訂單開了兩次付款都刷成功，第二筆 | `ORDER_NOT_PAYABLE` |
| 綠界實收金額與訂單金額不符 | `AMOUNT_MISMATCH` |
| 預約時段已開始才收到付款 | `BOOKING_ALREADY_STARTED` |

這些情況後端仍回 `1|OK`（否則綠界會一直重送），只記錄不入帳。目前只記在資料表與後端 log，後台尚無畫面。

### 8.3 查詢資料

```powershell
cd backend
docker compose exec postgres psql -U booking -d booking
```

```sql
SELECT merchant_trade_no, amount, status, gateway_trade_no, rtn_code, rtn_msg, notified_at
FROM payment_transactions ORDER BY created_at DESC;

SELECT order_number, status, payment_status, paid_reference, created_at AT TIME ZONE 'Asia/Taipei' AS created_tw
FROM booking_orders ORDER BY created_at DESC LIMIT 10;
```

圖形化工具連線：Host `localhost`、Port `5433`、Database `booking`、User `booking`、Password `booking_dev_password`。

> 資料庫時間一律存 **UTC**（例如 `15:35+00` = 台灣 `23:35`）。查詢時加 `AT TIME ZONE 'Asia/Taipei'`；前端顯示時再轉台灣時間。

---

## 9. 安全檢查（notify / result 的處理順序）

1. 驗證 `CheckMacValue`（固定時間比對）與 `MerchantID`，失敗回 `400 0|CheckMacValue Error`
2. 用 `MerchantTradeNo` 找付款紀錄，找不到也回 400
3. 已是 `PAID` / `NEEDS_REFUND` → 直接回 `1|OK`（綠界重送）
4. `RtnCode != 1` → `FAILED`
5. `SimulatePaid = 1` 且未開啟 `ECPAY_ACCEPT_SIMULATED` → 忽略
6. `TradeAmt` 必須等於發起時金額，否則 `NEEDS_REFUND`
7. 呼叫既有的 `PaymentConfirmationService.confirmVerifiedPayment()` 入帳；被拒絕則 `NEEDS_REFUND`

---

## 10. 常見問題

| 現象 | 原因與解法 |
| --- | --- |
| 付款後瀏覽器「拒絕連線」`localhost:5173` | 前端沒啟動。入帳不受影響；在專案根目錄 `npm run dev` |
| `PAYMENT_WINDOW_TOO_SHORT` | 保留剩不到 3 分鐘，重新保留時段與建立訂單 |
| `HOLD_NOT_ACTIVE` | 保留已超過 10 分鐘，重做保留（換新的 Idempotency-Key） |
| orderId 欄位 `Value must be a Guid` | 填的是 `orderNumber`（`B2026…`），要填 `id` |
| Swagger 回 `401` | token 過期（15 分鐘），重新登入並 Authorize |
| 訂單一直 `PENDING_PAYMENT` | 檢查 ngrok 是否開著、`.env` 的 `ECPAY_RETURN_URL` 是否正確、是否已 `docker compose up -d backend`；看 `docker compose logs --tail 50 backend` |
| 資料庫工具 `getaddrinfo ENOTFOUND booking` | Host 要填 `localhost`，不是 `booking` |
| 測試或腳本說 schema 不是 V8 | 後端未重建；`docker compose up --build -d backend` |

---

## 11. 部署到 Azure

> 部署會在**共用 Neon 資料庫**執行 V8（新增 `payment_transactions` 表，不影響既有資料，但無法撤銷）。部署前請先通知組員。

1. commit 並部署新版後端（見 [AZURE_DEPLOYMENT.md](AZURE_DEPLOYMENT.md)）
2. 在 Azure App Service 的「環境變數」加入（Azure 不讀 `.env`）：

   ```
   ECPAY_RETURN_URL=https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/payments/ecpay/notify
   ECPAY_ORDER_RESULT_URL=https://ccu-booking-api-1fd823.azurewebsites.net/api/v1/payments/ecpay/result
   ECPAY_FRONTEND_RESULT_URL=<前端網址>/my-bookings
   ```

3. 使用 Azure 時**不需要 ngrok**。未設定 `ECPAY_RETURN_URL` 時只有發起付款 API 回 503，其他功能不受影響。

---

## 12. 程式位置

| 檔案 | 用途 |
| --- | --- |
| `src/main/java/com/booking/payment/CheckMacValue.java` | 檢查碼產生與驗證 |
| `src/main/java/com/booking/payment/EcpayPaymentService.java` | 產生付款表單、處理付款通知 |
| `src/main/java/com/booking/payment/EcpayController.java` | 三個 API |
| `src/main/java/com/booking/payment/PaymentRepository.java` | `payment_transactions` 存取 |
| `src/main/resources/db/migration/V8__create_payment_transactions.sql` | 資料表（已執行的 migration 不可修改） |
| `src/test/java/com/booking/payment/CheckMacValueTests.java` | 用綠界官方範例驗證檢查碼 |
| `src/test/java/com/booking/EcpayPaymentIntegrationTests.java` | 簽章偽造、金額 ±1、重複通知、過期付款等整合測試 |
| `tools/ecpay-test.html` | 手動測試用：貼上付款回應後送到綠界 |

執行測試：

```powershell
cd backend
docker compose --profile test run --rm tests
```
