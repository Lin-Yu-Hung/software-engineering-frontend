# 第三階段：營業日曆、定價與時段查詢

Java 21 / Spring Boot / PostgreSQL，所有路徑前綴為 `/api/v1`。同學 pull 後在 backend 執行 `docker compose up --build -d postgres backend` 即可套用 V5，保留既有資料。

這份文件保留第三階段的營業與費率合約。第四階段已加入 hold／訂單占用，availability 現在是 bookingEnabled=true／inventoryMode=LIVE；建立 hold 會重新查詢價格、檢查營業與占用，並以交易取得時段。操作見 [API_BOOKING.md](API_BOOKING.md)。

## API 清單

| 方法 | 路徑 | 權限／用途 |
| --- | --- | --- |
| GET | `/venue` | 公開；場館名稱、台北時間、可提前預約日數、設定版本 |
| GET | `/availability?date=YYYY-MM-DD&courtId=UUID` | 公開；courtId 可省略，查詢所有啟用場地 |
| GET | `/backoffice/operating-hours` | ADMIN；完整 7 天設定及 version |
| PUT | `/backoffice/operating-hours` | ADMIN；原子更新完整每週設定及可提前預約日數 |
| GET | `/backoffice/calendar-exceptions?from=YYYY-MM-DD&to=YYYY-MM-DD` | ADMIN；含首尾、最多 366 天 |
| POST | `/backoffice/calendar-exceptions` | ADMIN；新增例外日，201 |
| PATCH | `/backoffice/calendar-exceptions/{exceptionId}` | ADMIN；完整當日定義與 expectedVersion |
| DELETE | `/backoffice/calendar-exceptions/{exceptionId}?expectedVersion=0` | ADMIN；移除例外，204 |
| GET | `/backoffice/pricing-rules` | ADMIN；目前完整規則與 version |
| PUT | `/backoffice/pricing-rules` | ADMIN；原子發布完整新價格版本 |

受保護端點沿用 `Authorization: Bearer <accessToken>`。MEMBER、FRONT_DESK 都不可改營業與費率。Swagger：<http://localhost:8080/swagger-ui.html>。

## 初始資料與日曆規則

- 時區固定 Asia/Taipei，基本單位 60 分鐘；每日 08:00～22:00。
- 初始可提前預約 30 天，含今天與今天加 30 天。可設定 1～365 天。
- availability 可查過去 30 天到最晚開放日期；過去日期依目前設定計算，是日曆預覽。
- 每個啟用場地回傳完整 24 格。營業外為 CLOSED；營業內且開始時間小於或等於 serverTime 為 PAST；其餘為 AVAILABLE。
- CLOSED 的 price、priceType 為 null。PAST 回傳目前費率。指定停用／退役場地回 404。
- 本階段營業時間與例外日是場館共用，個別場地封場待後續實作。
- 週六、週日為 WEEKEND，其他日為 WEEKDAY。國定假日須由管理員建立 HOLIDAY 例外日。

| 初始場地 | 平日離峰 | 平日 18 時起尖峰 | 週末／HOLIDAY 全天 |
| --- | ---: | ---: | ---: |
| 第 1、2 場地 | 350 | 500 | 500 |
| 第 3、4、5 場地 | 300 | 450 | 450 |
| 第 6 場地 | 280 | 400 | 400 |
| 未設定個別規則的新場地 | 350 | 500 | 500 |

費率規則覆蓋全天，只有實際營業時間內的格子顯示價格。

## 每週營業時間

先 GET 取得 version，PUT 帶 expectedVersion 與完整 7 天。星期一是 1、星期日是 7，每天都需出現一次；空 periods 表示休館。

```json
{
  "expectedVersion": 0,
  "advanceBookingDays": 30,
  "days": [
    {"dayOfWeek":1,"periods":[{"startHour":8,"endHour":12},{"startHour":14,"endHour":22}]},
    {"dayOfWeek":2,"periods":[{"startHour":8,"endHour":22}]},
    {"dayOfWeek":3,"periods":[{"startHour":8,"endHour":22}]},
    {"dayOfWeek":4,"periods":[{"startHour":8,"endHour":22}]},
    {"dayOfWeek":5,"periods":[{"startHour":8,"endHour":22}]},
    {"dayOfWeek":6,"periods":[{"startHour":7,"endHour":24}]},
    {"dayOfWeek":7,"periods":[]}
  ]
}
```

startHour 是 0～23 的整數，endHour 是 1～24 的整數，開始小於結束。區間為 `[startHour,endHour)`，可相鄰但不可重疊。24 代表翌日 00:00；跨午夜營業須拆成兩個日期的區間。

例外日新增、修改、刪除也會使 operating-hours 的 version 遞增。版本過舊回 409 STALE_VERSION，不覆蓋其他人的修改。

## 例外日

POST JSON（日期換成今天以後）：

```json
{
  "date": "2026-10-10",
  "label": "國定假日特別營業",
  "dayType": "HOLIDAY",
  "periods": [{"startHour":10,"endHour":18}]
}
```

同一日期只能有一筆，當天營業區間與 dayType 都取代每週規則。休館傳 `periods: []`；補班日可將星期六設為 WEEKDAY 並指定營業時間。

POST 回傳 id、version。PATCH 傳完整四個欄位再加 expectedVersion。不能新增或修改過去日期；DELETE 可移除例外，當天恢復依每週規則判斷。

## 費率版本與整數金額

GET 取得 version、rules，PUT 發布完整新版本；舊版保留在 pricing_versions／pricing_rules。

```json
{
  "expectedVersion": 0,
  "rules": [
    {"courtId":null,"dayType":"WEEKDAY","startHour":0,"endHour":24,"price":350,"priceType":"OFF_PEAK","priority":0},
    {"courtId":null,"dayType":"WEEKDAY","startHour":18,"endHour":24,"price":500,"priceType":"PEAK","priority":10},
    {"courtId":null,"dayType":"WEEKEND","startHour":0,"endHour":24,"price":500,"priceType":"WEEKEND","priority":0},
    {"courtId":null,"dayType":"HOLIDAY","startHour":0,"endHour":24,"price":500,"priceType":"HOLIDAY","priority":0}
  ]
}
```

- courtId 為 null 表示場館預設，UUID 表示個別場地。
- 按當天 dayType 篩選後，個別場地優先於場館預設；同一範圍取較高 priority（0～100）。全場高 priority 不會蓋過個別場地規則。
- 相同 courtId、dayType、priority 的區間不可重疊。三種 dayType 的場館預設都需覆蓋 00～24 時。
- 最多 200 條。price 使用 Java long／PostgreSQL bigint，限 TWD 整數元 1～100000；小數、負數、0、超額與溢位皆拒絕。
- priceType 可用 OFF_PEAK、PEAK、WEEKEND、HOLIDAY、SPECIAL。
- 指定日期的單獨促銷價尚未實作。後續訂單須保存成交單價與 pricingVersion；收款讀訂單確定金額。

## 時段回應

availability 包含 date、timezone、serverTime、slotMinutes、currency、bookingEnabled、inventoryMode、dayType、scheduleSource、operatingPeriods、scheduleVersion、pricingVersion、updatedAt、courts、slots。讀取在一致的資料庫快照中完成。

每格範例：

```json
{
  "courtId":"00000000-0000-0000-0000-000000000001",
  "startAt":"2026-10-10T08:00:00+08:00",
  "endAt":"2026-10-10T09:00:00+08:00",
  "status":"AVAILABLE",
  "price":500,
  "priceType":"WEEKEND"
}
```

date 必填，courtId 選填。格式錯誤與日期超出查詢範圍回 400。公開回應只包含場地與時段資訊。

## Postman 與驗證

匯入 `postman/booking-phase3.postman_collection.json`，沿用 Booking Local，Runner 執行全部 31 個請求。使用第二階段示範管理員與櫃台帳號，日期自動依後端時間選取。

流程新增特殊營業日、改為休館、移除測試例外，暫時延長某星期營業至 24 時、發布 371 元測試費率，並驗證輸入與權限。第 24、25 個請求會還原原本費率、營業時間。價格版本與歷史遞增是正常結果。

請在本機開發資料庫完整執行，期間避免同時編輯設定。若中斷，originalHours／originalRules collection variables 保存原內容，exceptionId 保存測試例外 ID；先確認目前版本與設定再完成還原。還原遇到版本衝突也會回 409。

完整測試指令：`docker compose --profile test run --rm tests`，使用獨立 booking_test；涵蓋台北時間、營業區間、日曆覆蓋、金額邊界、價格版本保存、權限與併發衝突。

下一階段需加入預約占用、防撞、hold 過期釋出，以及營業時段縮減／場地退役對既有預約的保護。
