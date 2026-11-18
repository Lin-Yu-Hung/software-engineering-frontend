import { randomBytes } from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const config = JSON.parse(readFileSync(resolve(root, 'azure/backend.config.json'), 'utf8'))
const artifact = resolve(root, 'backend/target/booking-backend-0.0.1-SNAPSHOT.jar')
if (!process.argv.includes('--deploy')) {
  console.log(JSON.stringify({ ...config, artifact: 'backend/target/booking-backend-0.0.1-SNAPSHOT.jar',
    database: 'existing Neon production', gitPush: false, containerRegistry: false,
    settings: 'server-only Azure encrypted application settings', demoUsersEnabled: false, demoPaymentsEnabled: false }, null, 2))
  process.exit(0)
}

if (config.sku !== 'F1') throw new Error('This deployment is limited to the F1 Free plan; review paid tiers explicitly before changing it.')
if (!existsSync(artifact)) throw new Error('Build the backend JAR before deploying.')
const envFile = resolve(root, existsSync(resolve(root, '.env')) ? '.env' : '.env.local')
process.loadEnvFile(envFile)
if (!process.env.DATABASE_URL || !process.env.DATABASE_URL_UNPOOLED) throw new Error('Run neon deploy first to populate both database URLs.')
if (!process.env.AZURE_JWT_SECRET) {
  process.env.AZURE_JWT_SECRET = randomBytes(48).toString('base64url')
  appendFileSync(envFile, `\n# Azure session signing key; do not commit.\nAZURE_JWT_SECRET=${process.env.AZURE_JWT_SECRET}\n`)
}
const secrets = [process.env.DATABASE_URL, process.env.DATABASE_URL_UNPOOLED, process.env.JWT_SECRET, process.env.AZURE_JWT_SECRET].filter(Boolean)
const redact = text => secrets.reduce((result, secret) => result.replaceAll(secret, '[REDACTED]'), text)
const portablePython = resolve(root, 'tmp/azure-cli/python.exe')
const cli = process.env.AZURE_CLI_PYTHON || (existsSync(portablePython) ? portablePython : 'az')
const prefix = cli === 'az' ? [] : ['-IBm', 'azure.cli']
const cliEnvironment = { ...process.env,
  AZURE_CONFIG_DIR: process.env.AZURE_CONFIG_DIR || resolve(root, 'tmp/azure-config'),
  AZURE_CORE_COLLECT_TELEMETRY: '0', AZURE_CORE_NO_COLOR: 'true',
  PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1',
}
function az(args, { optional = false, json = true } = {}) {
  const result = spawnSync(cli, [...prefix, ...args, '--only-show-errors', '-o', json ? 'json' : 'none'], {
    cwd: root, env: cliEnvironment, encoding: 'utf8', maxBuffer: 20 * 1024 * 1024,
  })
  if (result.error) throw new Error('Azure CLI could not start. Install Azure CLI or set AZURE_CLI_PYTHON.')
  if (result.status !== 0) {
    if (optional) return undefined
    throw new Error(redact(result.stderr || `Azure CLI failed (${result.status}).`))
  }
  return json && result.stdout.trim() ? JSON.parse(result.stdout) : undefined
}
const stage = label => console.log(`AZURE: ${label}`)
const groupArgs = ['--resource-group', config.resourceGroup]
const appArgs = ['--name', config.appName, ...groupArgs]
try {
  stage('Checking Azure login and subscription')
  const account = az(['account', 'show'])
  console.log(`Subscription: ${account.name} (${account.id})`)
  if (account.id !== config.subscriptionId) throw new Error('Select the subscriptionId from azure/backend.config.json with az account set before deploying.')
  if (!az(['group', 'show', '--name', config.resourceGroup], { optional: true })) {
    az(['group', 'create', '--name', config.resourceGroup, '--location', config.location], { json: false })
  }
  stage('Ensuring Microsoft.Web and the Linux F1 Free plan')
  az(['provider', 'register', '--namespace', 'Microsoft.Web', '--wait'], { json: false })
  let plan = az(['appservice', 'plan', 'show', '--name', config.planName, ...groupArgs], { optional: true })
  if (!plan) {
    az(['appservice', 'plan', 'create', '--name', config.planName, ...groupArgs,
      '--location', config.location, '--is-linux', '--sku', 'F1'], { json: false })
    plan = az(['appservice', 'plan', 'show', '--name', config.planName, ...groupArgs])
  }
  if (plan.sku?.name !== 'F1') throw new Error('The existing plan is not Free F1; refusing an implicit paid deployment.')
  stage('Creating the Java 21 Web App if needed')
  let app = az(['webapp', 'show', ...appArgs], { optional: true })
  if (!app) {
    az(['webapp', 'create', ...appArgs, '--plan', plan.id, '--runtime', config.runtime], { json: false })
    app = az(['webapp', 'show', ...appArgs])
  }
  if ((app.serverFarmId || app.appServicePlanId)?.toLowerCase() !== plan.id.toLowerCase()) throw new Error('The existing Web App belongs to a different App Service plan.')
  stage('Applying server-only Neon settings and HTTPS')
  const settings = {
    SPRING_PROFILES_ACTIVE: 'neon', SERVER_PORT: '80', SERVER_FORWARD_HEADERS_STRATEGY: 'framework',
    DATABASE_URL: process.env.DATABASE_URL, DATABASE_URL_UNPOOLED: process.env.DATABASE_URL_UNPOOLED,
    JWT_SECRET: process.env.AZURE_JWT_SECRET, SECURE_COOKIE: 'true',
    DEMO_USERS_ENABLED: 'false', DEMO_PAYMENTS_ENABLED: 'false',
    CORS_ALLOWED_ORIGINS: config.corsAllowedOrigins.join(','),
    JAVA_TOOL_OPTIONS: '-Xms64m -Xmx512m -XX:+ExitOnOutOfMemoryError',
    WEBSITES_CONTAINER_START_TIME_LIMIT: '600',
  }
  const privateFile = resolve(root, 'tmp/azure-appsettings.private.json')
  mkdirSync(dirname(privateFile), { recursive: true })
  writeFileSync(privateFile, JSON.stringify(settings))
  try {
    az(['webapp', 'config', 'appsettings', 'set', ...appArgs, '--settings', `@${privateFile}`], { json: false })
  } finally {
    unlinkSync(privateFile)
  }
  az(['webapp', 'update', ...appArgs, '--https-only', 'true'], { json: false })
  az(['webapp', 'config', 'set', ...appArgs, '--always-on', 'false',
    '--startup-file', 'java -jar /home/site/wwwroot/app.jar --server.port=80'], { json: false })
  stage('Uploading the JAR directly; no image registry or Git push')
  az(['webapp', 'deploy', ...appArgs, '--src-path', artifact, '--type', 'jar', '--restart', 'true', '--timeout', '600000'], { json: false })
  app = az(['webapp', 'show', ...appArgs])
  const result = { subscriptionId: account.id, ...config, url: `https://${app.defaultHostName}`,
    healthUrl: `https://${app.defaultHostName}/api/v1/health`, swaggerUrl: `https://${app.defaultHostName}/swagger-ui.html`,
    state: app.state, deployedAt: new Date().toISOString() }
  writeFileSync(resolve(root, 'tmp/azure-backend-deployment.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  console.error(`Azure deployment failed: ${redact(error.message)}`)
  process.exitCode = 1
}
