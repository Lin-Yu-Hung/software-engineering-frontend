import { randomBytes } from 'node:crypto'
import { existsSync, appendFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const envFile = process.env.NEON_ENV_FILE
  ? resolve(root, process.env.NEON_ENV_FILE)
  : resolve(root, existsSync(resolve(root, '.env')) ? '.env' : '.env.local')

if (!existsSync(envFile)) {
  console.error('Neon env file missing. Run neon deploy in the project root first.')
  process.exit(1)
}
// These values are passed only to the Spring Boot container, never to Vue code.
process.loadEnvFile(envFile)
if (!process.env.DATABASE_URL || !process.env.DATABASE_URL_UNPOOLED) {
  console.error('Both DATABASE_URL and DATABASE_URL_UNPOOLED are required. Run neon deploy again.')
  process.exit(1)
}
if (!process.env.JWT_SECRET) {
  process.env.JWT_SECRET = randomBytes(48).toString('base64url')
  appendFileSync(envFile, `\n# Local Spring Boot session signing key; preserve across restarts.\nJWT_SECRET=${process.env.JWT_SECRET}\n`)
  console.log('Created a local JWT signing key in the ignored env file.')
}
const project = process.env.NEON_COMPOSE_PROJECT || 'booking-neon'
if (!/^[a-z0-9][a-z0-9_-]*$/.test(project)) {
  console.error('Invalid NEON_COMPOSE_PROJECT.')
  process.exit(1)
}
const stop = process.argv.includes('--stop')
const args = ['compose', '-p', project, '-f', resolve(root, 'backend/compose.neon.yaml'),
  ...(stop ? ['stop', 'backend'] : ['up', '--build', '-d', 'backend'])]
const child = spawn('docker', args, {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env, NEON_ENV_FILE: envFile },
})
child.on('error', () => {
  console.error('Docker could not start. Open Docker Desktop, then retry this command.')
  process.exitCode = 1
})
child.on('exit', (code) => {
  process.exitCode = code ?? 1
  if (code === 0 && !stop) {
    const port = process.env.NEON_API_PORT || '8081'
    console.log(`Neon-backed Spring Boot is starting: http://localhost:${port}/api/v1/health`)
    console.log('Wait for database migrations and application startup before sending requests.')
  }
})
