import { readFileSync, writeFileSync } from 'node:fs'

const read = name => JSON.parse(readFileSync(new URL(`../backend/postman/${name}`, import.meta.url), 'utf8'))
const stages = [
  ['booking-phase1.postman_collection.json', '第一階段｜資料庫與公開場地', '檢查資料庫健康狀態、場地查詢與 OpenAPI。'],
  ['booking-phase2.postman_collection.json', '第二階段｜會員與場地管理', '測試註冊登入、會員權限及管理員場地維護。'],
  ['booking-phase3.postman_collection.json', '第三階段｜營業時間與費率', '測試營業日曆、每週營業時間及場地價格設定。'],
  ['booking-phase4.postman_collection.json', '第四階段｜會員預約與取消', '測試時段保留、訂單建立、防止重複預約及取消。'],
  ['booking-phase5.postman_collection.json', '第五階段｜櫃台與維修封場', '測試櫃台代訂、報到、取消及管理員維修封場。'],
  ['booking-phase6.postman_collection.json', '第六階段｜完整預約示範', '測試模擬付款、櫃台報到、取消與封場完整流程。'],
]
const endpoints = [
  ['/api/v1/auth/register', '註冊會員'], ['/api/v1/auth/login', '會員登入'], ['/api/v1/auth/refresh', '更新登入權杖'], ['/api/v1/auth/logout', '會員登出'],
  ['/api/v1/me', '查詢或更新會員資料'], ['/api/v1/courts', '查詢公開場地'], ['/api/v1/backoffice/courts', '管理場地'],
  ['/api/v1/venue', '查詢場館資訊'], ['/api/v1/availability', '查詢可預約時段'], ['/api/v1/backoffice/operating-hours', '管理每週營業時間'],
  ['/api/v1/backoffice/calendar-exceptions', '管理特殊營業日'], ['/api/v1/backoffice/pricing-rules', '管理場地費率'],
  ['/api/v1/holds', '保留或釋放預約時段'], ['/api/v1/orders', '建立或查詢預約訂單'],
  ['/api/v1/backoffice/timetable', '查詢櫃台每日排程'], ['/api/v1/backoffice/blocks', '管理維修封場'],
  ['/api/v1/backoffice/orders', '管理後台訂單'], ['/api/v1/demo/orders', '模擬示範付款'], ['/api/v1/health', '檢查系統及資料庫健康狀態'],
]
const findEndpoint = raw => {
  const path = String(raw).replace('{{baseUrl}}', '').split('?')[0]
  return endpoints.find(([prefix]) => path === prefix || path.startsWith(`${prefix}/`))?.[1] || '執行 API 請求'
}
const collectionVariables = new Map()
const folders = stages.map(([filename, folderName, folderDescription], stageIndex) => {
  const source = read(filename)
  for (const variable of source.variable || []) {
    if (!collectionVariables.has(variable.key)) collectionVariables.set(variable.key, variable)
  }
  let number = 0
  const items = structuredClone(source.item)
  const visit = entries => entries.forEach(item => {
    if (item.request) {
      number++
      const method = String(item.request.method || 'GET').toUpperCase()
      const endpoint = findEndpoint(typeof item.request.url === 'string' ? item.request.url : item.request.url?.raw)
      const originalName = item.name
      item.name = `${String(number).padStart(2, '0')}｜${endpoint}（${originalName}）`
      item.description = `用途：${endpoint}。\n\n執行方式：${folderDescription} 請依此階段順序使用 Collection Runner 執行；本請求的原始測試與變數處理腳本均已保留。HTTP 方法：${method}。原測試案例：${originalName}。`
      item.request.description = item.description
    } else {
      item.description ||= '本資料夾包含此階段的相關 API 請求，請依順序執行。'
      visit(item.item || [])
    }
  })
  visit(items)
  return { name: folderName, description: folderDescription, ...(source.auth ? { auth: source.auth } : {}), item: items }
})

const collection = {
  info: {
    name: '羽球場館預約系統｜完整 API 測試（Phase 1–6）',
    description: '整合六階段 API 測試。預設 baseUrl 為 http://localhost:8080，請先啟動本機後端，再用 Collection Runner 執行所需階段。Phase 2–6 會建立測試會員、訂單及其他測試資料；Phase 3 會暫時調整營業時間與費率並在流程中還原。Phase 6 的管理員、櫃台及模擬付款需要本機 DEMO 設定；Azure 雲端已停用這些示範功能，不建議對 production 執行完整測試。',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
  },
  variable: [...collectionVariables.values()],
  item: folders,
}
writeFileSync(new URL('../backend/postman/booking-all.postman_collection.json', import.meta.url), `${JSON.stringify(collection, null, 2)}\n`)
console.log(`Created unified collection with ${folders.length} Chinese-described stage folders.`)
