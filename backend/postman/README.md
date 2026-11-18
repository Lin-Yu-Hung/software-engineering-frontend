# Postman 匯入與操作

## 純 API 版：手動 Send、Body 有範例

匯入 `booking-api.postman_collection.json`，名稱是「羽球場館預約系統｜純 API 與 Body 範例」。共有 43 個 API，每個方法／路徑只列一次，依功能分組，無自動測試或自動建資料腳本。

需要 JSON body 的請求都已放入可編輯的範例。GET、DELETE 不送 body；refresh／logout 使用 cookie 和標頭，也沒有 body。

1. 先啟動本機後端，collection 的 `Variables` → `baseUrl` 預設是 `http://localhost:8080`。若要測 Azure，改為 `https://ccu-booking-api-1fd823.azurewebsites.net`。已選取的 Environment 若也有 `baseUrl`，會優先覆蓋 collection 設定，請一起確認。
2. 「註冊會員」已有姓名、電話與密碼範例；`memberEmail` 必須是未註冊過的 Email。這會真的新增一位會員。
3. 送出「帳號登入」，把回應 `accessToken` 複製到同名 collection 變數。需要驗證的請求會繼承此 Bearer token。管理員／櫃台操作請先用對應角色登入，並更新此變數。
4. 查詢公開場地，把要預約的場地 `id` 放入 `courtId`。
5. 設定 `queryDate` 為預約範圍內的未來日期（YYYY-MM-DD），查詢可預約時段，再把 AVAILABLE 時段的 `startAt` 放入 `slotStartAt`。檔案裡的日期僅供格式參考，之後要自行更新。
6. 送出「保留場地時段」，把回應 `id` 放入 `holdId`；在十分鐘內送出「由保留建立待付款訂單」，再把回應 `id` 放入 `orderId`。後續可查詢或取消此訂單。

`holdId`、`orderId`、`exceptionId`、`blockId` 初始為空，須先透過建立或查詢取得。需要 `expectedVersion` 的範例填 0，操作前請先 GET 最新資料，把版本改成回應中的 `version`；重複送出舊版本可能回傳 409。

需要 `Idempotency-Key` 的請求會使用 Postman 內建 `{{$guid}}` 產生新值。若要重送同一個操作，請把標頭改成相同固定識別碼；重新選時段或建立另一張訂單則使用新的識別碼。

手動按 Send 的新增、更新與刪除會改動所連資料庫。純 API 版不是 Runner 自動建資料流程，不能在完全不修改變數的情況下一次跑完全部 API。Azure 目前停用固定示範帳號與示範付款；會員註冊可自行使用，管理員權限需由既有管理員帳號提供。

## 流程版：Runner 按階段產生測試資料

`booking-all.postman_collection.json` 是六階段整合版，包含 152 個案例，保留原腳本與各階段授權設定。使用本機 DEMO 後端，選取完整階段並依序執行；不要任意跳過登入、初始化或還原設定等步驟。

Phase 2–6 會寫入會員、場地、預約等測試資料；Phase 3 會暫時調整並還原設定，應執行完整階段。Phase 6 需要示範帳號與啟用模擬付款。這版供本機使用，請勿直接用整套 Runner 對 Azure production 操作。

## 更新檔案

在專案根目錄執行：

```powershell
node scripts/build-postman-api.mjs
node scripts/merge-postman.mjs
```

兩份 JSON 都可直接匯入 Postman，不需要先匯入六個舊階段檔案。
