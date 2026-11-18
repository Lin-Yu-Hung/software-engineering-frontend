import { writeFileSync } from 'node:fs'

const folders = []
let current
const folder = (name, description) => {
  current = { name, description, item: [] }
  folders.push(current)
}
function api(method, path, name, description, { body, publicApi = false, idempotent = false, cookie = false } = {}) {
  const header = [{ key: 'Accept', value: 'application/json' }]
  if (body !== undefined) header.push({ key: 'Content-Type', value: 'application/json' })
  if (idempotent) header.push({ key: 'Idempotency-Key', value: '{{$guid}}', description: '每次送出自動產生新的識別碼；測試重送時請改填相同固定值。' })
  if (cookie) header.push({ key: 'X-Requested-With', value: 'BookingClient', description: '使用 refresh cookie 時必填；cookie 由 Postman Cookie Jar 自動攜帶。' })
  current.item.push({ name, request: {
    method, description, header, url: `{{baseUrl}}${path}`,
    ...(publicApi ? { auth: { type: 'noauth' } } : {}),
    ...(body !== undefined ? { body: { mode: 'raw', raw: JSON.stringify(body, null, 2), options: { raw: { language: 'json' } } } } : {}),
  }, response: [] })
}
const contact = { name: '王小明', phone: '0912345678' }
const slots = [{ courtId: '{{courtId}}', startAt: '{{slotStartAt}}' }]
const cancel = { reason: '顧客行程變更，取消預約' }
folder('01｜公開查詢', '不需登入。先查看場館日期與可用時段，再手動設定預約變數。')
api('GET', '/api/v1/health', '檢查系統與資料庫', '查詢資料庫連線與 Flyway 版本，不需要 body。', { publicApi: true })
api('GET', '/api/v1/courts', '查詢開放場地清單', '取得啟用中的場地；將回應的 id 複製至 collection 的 courtId 變數。不需要 body。', { publicApi: true })
api('GET', '/api/v1/courts/{{courtId}}', '查詢單一場地', '以 courtId 查詢啟用中的場地，不需要 body。', { publicApi: true })
api('GET', '/api/v1/venue', '查詢場館資訊', '取得場館目前時間與預約範圍；請以此決定 queryDate。不需要 body。', { publicApi: true })
api('GET', '/api/v1/availability?date={{queryDate}}&courtId={{courtId}}', '查詢可預約時段', '請先填 queryDate（YYYY-MM-DD）；將 AVAILABLE 時段的 startAt 複製到 slotStartAt。只查詢，不需要 body。', { publicApi: true })
folder('02｜註冊與登入', '手動送出。登入成功後，將回應 accessToken 複製至 collection 的 accessToken 變數。')
api('POST', '/api/v1/auth/register', '註冊會員', '建立 MEMBER 帳號。email 不得重複；密碼 8–72 字元（UTF-8 不超過 72 bytes）；姓名必填、最多 100 字元；電話 6–20 字元，允許數字、加號、括號、空白與連字號。重複操作請先換 memberEmail。', { publicApi: true, body: { email: '{{memberEmail}}', password: '{{memberPassword}}', ...contact } })
api('POST', '/api/v1/auth/login', '帳號登入', '會員、櫃台及管理員共用此 API；請以具備所需角色的帳密登入，並手動複製回應 accessToken 至同名變數。Postman 會儲存 refresh cookie。Azure 不提供固定示範管理員帳號。', { publicApi: true, body: { email: '{{memberEmail}}', password: '{{memberPassword}}' } })
api('POST', '/api/v1/auth/refresh', '更新登入權杖', '先登入取得 refresh cookie；保留 Cookie Jar。無 body，需 X-Requested-With 標頭。成功後手動更新 accessToken 變數。', { publicApi: true, cookie: true })
api('POST', '/api/v1/auth/logout', '登出目前工作階段', '以目前 refresh cookie 登出並撤銷工作階段；無 body，需 X-Requested-With 標頭。', { publicApi: true, cookie: true })
folder('03｜會員資料', '需登入。此資料夾繼承整個 collection 的授權設定，使用 accessToken 變數。')
api('GET', '/api/v1/me', '取得我的會員資料', '取得目前登入者資料及角色，不需要 body。')
api('PATCH', '/api/v1/me', '修改我的會員資料', '可修改姓名與電話；不會修改角色或密碼。', { body: contact })
folder('04｜會員預約與訂單', '需 MEMBER 身分。依查空場 → 保留 → 建立訂單順序手動操作；各 ID 都需自行由前一步回應複製。')
api('POST', '/api/v1/holds', '保留場地時段', '先設定 courtId、slotStartAt。每次可選 1–12 個未來整點時段，保留約十分鐘。同時段被占用會回 409。回應 id 請填入 holdId。', { body: { items: slots }, idempotent: true })
api('GET', '/api/v1/holds/{{holdId}}', '查詢我的保留', '先填入自己建立的 holdId，不需要 body。')
api('DELETE', '/api/v1/holds/{{holdId}}', '釋放我的保留', '只釋放指定且屬於自己的保留；無 body。釋放後不能再用此保留建立訂單。')
api('POST', '/api/v1/orders', '由保留建立待付款訂單', '先設定有效 holdId；會員付款方式使用 CREDIT_CARD。金額由後端計算。本請求只建立待付款訂單，不會實際刷卡。回應 id 請填入 orderId。', { body: { holdId: '{{holdId}}', customer: contact, paymentMethod: 'CREDIT_CARD' }, idempotent: true })
api('GET', '/api/v1/orders?scope=all&page=0&size=20', '查詢我的訂單清單', 'scope 可用 all、upcoming、past；page 從 0 開始，size 為 1–100。只查詢，不需要 body。')
api('GET', '/api/v1/orders/{{orderId}}', '查詢我的訂單明細', '只能查詢自己的 orderId；無 body。回應 version 可供後台操作使用。')
api('POST', '/api/v1/orders/{{orderId}}/cancellations', '取消我的訂單', '請填入自己的 orderId 與取消原因；成功後釋放時段。已付款訂單的退款仍需金流流程處理。', { body: cancel })
folder('05｜管理員場地設定', '需 ADMIN 身分；請先以管理員登入並更新 accessToken。')
api('GET', '/api/v1/backoffice/courts', '查詢後台所有場地', '包含停用場地，不需要 body。')
api('GET', '/api/v1/backoffice/courts/{{courtId}}', '查詢後台場地明細', '取得目前場地與 version；無 body。修改前先查詢最新版本。')
api('POST', '/api/v1/backoffice/courts', '新增場地', '場地名稱不可與啟用中的場地重複。新增成功後可將 id 填入 courtId。', { body: { name: 'Postman 示範場地', type: '標準 PU', status: 'ACTIVE', displayOrder: 10 } })
api('PATCH', '/api/v1/backoffice/courts/{{courtId}}', '修改場地', '範例 expectedVersion=0，請改為此場地最新 version；版本不同會回 409。', { body: { name: 'Postman 示範場地（更新）', type: '標準 PU', status: 'ACTIVE', displayOrder: 11, expectedVersion: 0 } })
api('DELETE', '/api/v1/backoffice/courts/{{courtId}}?expectedVersion=0', '停用並退役場地', '請把 query 的 expectedVersion=0 改為場地最新 version；歷史紀錄保留，不需要 body。')
folder('06｜管理員營業日程', '需 ADMIN 身分。PUT 會完整取代設定，請先 GET 目前設定與版本。')
api('GET', '/api/v1/backoffice/operating-hours', '查詢每週營業時間', '取得 version 與目前營業時段，不需要 body。')
api('PUT', '/api/v1/backoffice/operating-hours', '完整更新每週營業時間', '請先查詢目前設定並填入最新 expectedVersion；days 必須包含週一至週日，dayOfWeek=1–7。範例每天 08:00–22:00，整份送出會覆蓋原設定。', { body: { expectedVersion: 0, advanceBookingDays: 30, days: Array.from({ length: 7 }, (_, i) => ({ dayOfWeek: i + 1, periods: [{ startHour: 8, endHour: 22 }] })) } })
api('GET', '/api/v1/backoffice/calendar-exceptions?from={{queryDate}}&to={{queryDate}}', '查詢特殊營業日', '依 from、to 的 YYYY-MM-DD 日期範圍查詢，不需要 body。')
const exception = { date: '{{queryDate}}', label: '特別營業日', dayType: 'HOLIDAY', periods: [{ startHour: 9, endHour: 18 }] }
api('POST', '/api/v1/backoffice/calendar-exceptions', '新增特殊營業日', '同日期只能有一筆例外；dayType 可用 WEEKDAY、WEEKEND、HOLIDAY。periods=[] 代表當日不營業。回應 id 請填入 exceptionId。', { body: exception })
api('PATCH', '/api/v1/backoffice/calendar-exceptions/{{exceptionId}}', '修改特殊營業日', '請填入 exceptionId 及目前 version；以完整日定義替換此筆例外。', { body: { expectedVersion: 0, ...exception, label: '調整後的特別營業日' } })
api('DELETE', '/api/v1/backoffice/calendar-exceptions/{{exceptionId}}?expectedVersion=0', '刪除特殊營業日', '把 expectedVersion 改為此筆最新版本；刪除後依每週營業時間處理，無 body。')
folder('07｜管理員費率', '需 ADMIN 身分。更新會取代目前規則，先查詢最新版本及原規則。')
api('GET', '/api/v1/backoffice/pricing-rules', '查詢目前費率規則', '取得 version、幣別與費率列表，不需要 body。')
api('PUT', '/api/v1/backoffice/pricing-rules', '完整更新費率規則', '範例包含三種日別的全天基本費率，courtId=null 代表所有場地。請先填入最新 expectedVersion。送出會取代整份費率規則；price 是每小時新臺幣整數。', { body: { expectedVersion: 0, rules: ['WEEKDAY', 'WEEKEND', 'HOLIDAY'].map(dayType => ({ courtId: null, dayType, startHour: 0, endHour: 24, price: 300, priceType: 'OFF_PEAK', priority: 0 })) } })
folder('08｜櫃台訂單與報到', '需 FRONT_DESK 或 ADMIN。請登入具備權限的帳號後更新 accessToken。')
api('GET', '/api/v1/backoffice/timetable?date={{queryDate}}', '查詢每日排程棋盤', '以日期查詢排程、訂單及封場，無 body。')
api('GET', '/api/v1/backoffice/orders?date={{queryDate}}&page=0&size=20', '查詢後台訂單清單', '可依日期分頁查詢；無 body。')
api('POST', '/api/v1/backoffice/orders', '建立電話或現場代訂', 'source 限 PHONE 或 WALK_IN；paymentMethod 限 CASH 或 ONSITE_CARD。先選可用時段，再設定 courtId、slotStartAt。回應 id 請填入 orderId。', { body: { source: 'PHONE', paymentMethod: 'CASH', customer: contact, notes: '電話預約，現場收費', items: slots }, idempotent: true })
api('GET', '/api/v1/backoffice/orders/{{orderId}}', '查詢後台訂單明細', '查詢指定 orderId 的最新狀態與 version，無 body。')
api('PATCH', '/api/v1/backoffice/orders/{{orderId}}', '修改訂單聯絡資料', '將 expectedVersion 改為訂單最新 version；可更新顧客聯絡資料與備註。', { body: { expectedVersion: 0, customer: contact, notes: '顧客已確認聯絡電話' } })
api('POST', '/api/v1/backoffice/orders/{{orderId}}/cancellations', '由後台取消訂單', '提供取消原因並取消訂單；會依目前訂單狀態檢查是否可取消。', { body: cancel })
api('POST', '/api/v1/backoffice/orders/{{orderId}}/check-in', '辦理顧客報到', '訂單必須符合報到條件；expectedVersion 請使用訂單最新版本。', { body: { expectedVersion: 0 } })
api('POST', '/api/v1/backoffice/orders/{{orderId}}/check-in-reversals', '撤銷顧客報到', '更正已報到的訂單；expectedVersion 請使用報到後最新版本。', { body: { expectedVersion: 0 } })
folder('09｜管理員維修封場', '需 ADMIN 身分；封場與一般預約共用時段防撞規則。')
api('POST', '/api/v1/backoffice/blocks', '建立維修封場', '先設定 courtId、slotStartAt；回應 id 請填入 blockId。', { body: { reason: '燈具維修', items: slots }, idempotent: true })
api('GET', '/api/v1/backoffice/blocks?date={{queryDate}}', '查詢指定日期封場', '依日期查看封場清單，不需要 body。')
api('DELETE', '/api/v1/backoffice/blocks/{{blockId}}', '解除維修封場', '解除指定封場並釋放時段，不需要 body。')
folder('10｜本機示範付款', '需 ADMIN 且 DEMO_PAYMENTS_ENABLED=true；Azure 目前停用此功能。')
api('POST', '/api/v1/demo/orders/{{orderId}}/confirm-payment', '模擬付款成功', '只供本機展示，不會實際扣款。範例 amount=300，必須改成指定訂單 totalAmount 的精確整數金額。', { body: { amount: 300 } })

const collection = {
  info: { name: '羽球場館預約系統｜純 API 與 Body 範例', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json', description: '每個 HTTP 方法與路徑僅列一次，沒有自動測試或資料產生腳本。需要 JSON body 的請求已填入範例；GET、DELETE 及以 cookie 操作的續期／登出不送 body。預設 baseUrl=http://localhost:8080。可手動改為 Azure origin。請先設定 collection Variables；ID、未來日期、最新版本及 accessToken 必須由你手動從查詢／建立回應複製。按 Send 的新增／修改／刪除請求會實際變更所連資料庫。Azure 固定示範帳號與模擬付款停用。' },
  auth: { type: 'bearer', bearer: [{ key: 'token', value: '{{accessToken}}', type: 'string' }] },
  variable: Object.entries({ baseUrl: 'http://localhost:8080', accessToken: '', memberEmail: 'postman-member@example.test', memberPassword: 'MemberDemo123!', courtId: '00000000-0000-0000-0000-000000000001', queryDate: '2026-10-23', slotStartAt: '2026-10-23T10:00:00+08:00', holdId: '', orderId: '', exceptionId: '', blockId: '' }).map(([key, value]) => ({ key, value, type: 'string' })),
  item: folders,
}
writeFileSync(new URL('../backend/postman/booking-api.postman_collection.json', import.meta.url), `${JSON.stringify(collection, null, 2)}\n`)
console.log(`Created plain API collection: ${folders.reduce((sum, group) => sum + group.item.length, 0)} operations.`)
