# 第五階段：共用場館後台 API

本階段供簡單作業 demo；管理員包含櫃台權限，共用同一套 API。前端未修改。以下路徑皆加 `/api/v1`，需要 Bearer token。

| 方法 | 路徑 | 權限與用途 |
| --- | --- | --- |
| GET | `/backoffice/timetable?date=2026-10-05` | FRONT_DESK；公開格式 grid 加當天有效訂單及封場，含客人聯絡資料 |
| GET | `/backoffice/orders?date=2026-10-05&page=0&size=20` | FRONT_DESK；date 可省略，含已取消／到期訂單；size 1～100 |
| POST | `/backoffice/orders` | FRONT_DESK；現場或電話代訂，需 Idempotency-Key |
| GET | `/backoffice/orders/{orderId}` | FRONT_DESK；單一訂單 |
| PATCH | `/backoffice/orders/{orderId}` | FRONT_DESK；修改聯絡資料與備註，需 expectedVersion |
| POST | `/backoffice/orders/{orderId}/cancellations` | FRONT_DESK；取消未開始、未報到訂單 |
| POST | `/backoffice/orders/{orderId}/check-in` | FRONT_DESK；整張訂單手動報到 |
| POST | `/backoffice/orders/{orderId}/check-in-reversals` | FRONT_DESK；撤銷報到 |
| POST | `/backoffice/blocks` | ADMIN；批次維修封場，需 Idempotency-Key |
| GET | `/backoffice/blocks?date=2026-10-05` | ADMIN；與指定日期相交的有效封場 |
| DELETE | `/backoffice/blocks/{blockId}` | ADMIN；解除封場，204；可重試 |

## 櫃台代訂

```json
{
  "source": "PHONE",
  "paymentMethod": "CASH",
  "customer": {"name": "王同學", "phone": "0912345678"},
  "notes": "電話預約",
  "items": [{"courtId": "00000000-0000-0000-0000-000000000001", "startAt": "2026-10-05T10:00:00+08:00"}]
}
```

日期請換成未來開放日；整點、每項一小時，1～12 項。source 限 WALK_IN／PHONE；付款方式限 CASH／ONSITE_CARD。回應格式沿用 [會員訂單](API_BOOKING.md)，memberId=null、status=CONFIRMED、paymentStatus=UNPAID、expiresAt=null。櫃台已保留場地，實際收款由金流同學整合；此 API 不宣告已付款。

Idempotency-Key 使用 UUID 即可；相同人、相同 key、相同內容回原訂單，內容不同回 409。價格由伺服器算出並保存快照；不得傳總金額。批次時段全部成功或全部失敗，與會員保留、維修封場共用 PostgreSQL 防撞。

修改聯絡資料（完整替換 customer、notes，不修改預約時段或金額）：

```json
{"expectedVersion":0,"customer":{"name":"更正姓名","phone":"0987654321"},"notes":"已聯絡"}
```

取消：`{"reason":"客人取消"}`。已付款取消後標記 PENDING_REVIEW，真正退款交由金流同學處理。取消與解除封場即釋放占用。

## 報到

報到與撤銷均傳 `{"expectedVersion":0}`，使用最新回應的 version。過舊版本回 VERSION_CONFLICT。已報到的重複報到／已撤銷的重複撤銷直接回目前狀態。

僅 CONFIRMED 可報到，時間限第一個時段前 24 小時至最後一個時段結束前。現場未收款訂單也可先記錄到場，付款狀態保持 UNPAID；會員待付款訂單不可報到。訂單回應新增 checkedInAt，null 表示未報到；報到不等於收款。已報到者須先撤銷才能取消，且開始後不可取消。為簡化 demo，不實作 QR Code 與分項核銷。

## 維修封場

```json
{"reason":"燈具維修","items":[{"courtId":"00000000-0000-0000-0000-000000000001","startAt":"2026-10-05T10:00:00+08:00"}]}
```

僅能封未來、營業中、啟用場地的整點時段，每次 1～12 項；不得覆蓋會員保留或訂單。POST 回 id、reason、active、createdAt、releasedAt、items。公開 `/availability` 只回 MAINTENANCE，不公開封場原因或顧客資料。封場期間，營業調整及停用場地亦不能抹掉未來占用。

## Postman

匯入 `postman/booking-phase5.postman_collection.json`，選 Booking Local，Runner 依序執行。會建立專用場地、選取台北當天或隔天的未來營業時段，測試代訂、版本衝突、報到與撤銷、維修防撞及權限，最後釋放占用並退役場地。請勿中途跳步；若停在保留／代訂／封場，可用相對應取消 API 清理。
