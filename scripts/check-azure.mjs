import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID, randomBytes } from 'node:crypto'
import assert from 'node:assert/strict'

const deployment = JSON.parse(readFileSync(resolve('tmp/azure-backend-deployment.json'), 'utf8'))
const url = new URL(deployment.url)
if (url.protocol !== 'https:') throw new Error('Azure API must use HTTPS.')
let ready = false
for (let attempt = 1; attempt <= 8; attempt++) {
  console.log(`Checking Azure HTTPS readiness (${attempt}/8): ${url.origin}/api/v1/health`)
  try {
    const response = await fetch(`${url.origin}/api/v1/health`, { signal: AbortSignal.timeout(45000) })
    if (response.ok) {
      const health = await response.json()
      if (health.status === 'UP' && health.database === 'UP') {
        console.log(`Azure health UP; database=${health.databaseName}; schema=${health.schemaVersion}`)
        ready = true
        break
      }
    }
  } catch { /* Java and Neon may need a cold start before the first request. */ }
  await new Promise(done => setTimeout(done, 3000))
}
if (!ready) throw new Error('Azure API is not ready. Inspect the App Service startup and logs before retrying.')
const swagger = await fetch(`${url.origin}/v3/api-docs`, { signal: AbortSignal.timeout(45000) })
if (!swagger.ok) throw new Error(`Azure OpenAPI returned HTTP ${swagger.status}.`)
const document = await swagger.json()
if (!document.paths?.['/api/v1/holds']) throw new Error('Azure OpenAPI is missing the booking API.')
console.log('PASS Azure HTTPS OpenAPI: HTTP 200 with booking endpoints')
const server = new URL(document.servers?.[0]?.url || url.origin, url.origin)
assert.equal(server.protocol, 'https:', 'Swagger must generate HTTPS API requests behind the Azure proxy')
console.log('PASS Azure Swagger uses HTTPS')
const email = `azure-session-${randomUUID()}@example.test`
const password = `AzureProof-${randomBytes(18).toString('base64url')}`
const post = (path, body, cookie) => fetch(`${url.origin}/api/v1${path}`, {
  method: 'POST', signal: AbortSignal.timeout(45000),
  headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'BookingClient', ...(cookie ? { Cookie: cookie } : {}) },
  body: body ? JSON.stringify(body) : undefined,
})
const registered = await post('/auth/register', { email, password, name: 'Azure session proof', phone: '0912345678' })
assert.equal(registered.status, 201)
console.log('PASS Azure session registration: HTTP 201')
const loggedIn = await post('/auth/login', { email, password })
assert.equal(loggedIn.status, 200)
console.log('PASS Azure session login: HTTP 200')
const cookieHeader = loggedIn.headers.getSetCookie().find(cookie => cookie.startsWith('booking_refresh='))
assert.ok(cookieHeader && /;\s*Secure(?:;|$)/i.test(cookieHeader) && /;\s*HttpOnly(?:;|$)/i.test(cookieHeader))
console.log('PASS Azure refresh cookie is Secure and HttpOnly')
const cookie = cookieHeader.split(';')[0]
const refreshed = await post('/auth/refresh', undefined, cookie)
assert.equal(refreshed.status, 200)
console.log('PASS Azure refresh: HTTP 200')
const refreshedCookie = refreshed.headers.getSetCookie().find(value => value.startsWith('booking_refresh='))?.split(';')[0]
assert.ok(refreshedCookie && refreshedCookie !== cookie)
console.log('PASS Azure refresh rotates the cookie')
const accessToken = (await refreshed.json()).accessToken
const loggedOut = await post('/auth/logout', undefined, refreshedCookie)
assert.equal(loggedOut.status, 204)
console.log('PASS Azure logout: HTTP 204')
const revoked = await fetch(`${url.origin}/api/v1/me`, { headers: { Authorization: `Bearer ${accessToken}` }, signal: AbortSignal.timeout(45000) })
assert.equal(revoked.status, 401)
console.log('PASS Azure logout revokes access: HTTP 401')
process.env.NEON_API_URL = url.origin
process.env.NEON_PROOF_FILE = 'tmp/azure-e2e.json'
await import('./check-neon.mjs')
if (!process.exitCode) {
  const proofFile = resolve(process.env.NEON_PROOF_FILE)
  const proof = JSON.parse(readFileSync(proofFile, 'utf8'))
  writeFileSync(proofFile, JSON.stringify({ ...proof, bookingChecks: proof.checks, httpsSessionChecks: 9, checks: proof.checks + 9 }, null, 2))
  console.log(`Azure verification passed: 9 HTTPS/session checks + ${proof.checks} booking API checks = ${proof.checks + 9} checks.`)
}
