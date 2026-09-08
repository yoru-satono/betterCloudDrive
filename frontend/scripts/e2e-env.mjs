import fs from 'node:fs'
import path from 'node:path'

const DEFAULT_ONLYOFFICE_JWT_SECRET = 'betterclouddrive-onlyoffice-dev-secret'

export function parseEnvText(contents) {
  const values = {}
  for (const rawLine of contents.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue

    const assignment = line.startsWith('export ') ? line.slice(7).trimStart() : line
    const separator = assignment.indexOf('=')
    if (separator <= 0) continue

    const key = assignment.slice(0, separator).trim()
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue

    let value = assignment.slice(separator + 1).trim()
    if (value.length >= 2) {
      const quote = value[0]
      if ((quote === '"' || quote === "'") && value.at(-1) === quote) {
        value = value.slice(1, -1)
      }
    }
    values[key] = value
  }
  return values
}

export function loadWorkspaceE2EEnv(workspaceRoot, inheritedEnv = process.env) {
  const rootEnv = readEnvFile(path.join(workspaceRoot, '.env'))
  const databaseEnv = readEnvFile(path.join(workspaceRoot, 'config/env-files/database.env'))
  const backendEnv = readEnvFile(path.join(workspaceRoot, 'config/env-files/backend.env'))

  validateMatchingValues(
    'POSTGRES_PASSWORD and DB_PASSWORD must match in config/env-files/database.env',
    databaseEnv.POSTGRES_PASSWORD,
    databaseEnv.DB_PASSWORD,
  )
  validateMatchingValues(
    'ONLYOFFICE_JWT_SECRET must match in .env and config/env-files/backend.env',
    rootEnv.ONLYOFFICE_JWT_SECRET || DEFAULT_ONLYOFFICE_JWT_SECRET,
    backendEnv.ONLYOFFICE_JWT_SECRET || DEFAULT_ONLYOFFICE_JWT_SECRET,
  )

  const env = {
    ...rootEnv,
    ...databaseEnv,
    ...backendEnv,
    ...inheritedEnv,
  }
  env.E2E_POSTGRES_HOST ||= '127.0.0.1'
  env.E2E_POSTGRES_PORT ||= env.HOST_POSTGRES_PORT || '5432'
  return env
}

function readEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return {}
  return parseEnvText(fs.readFileSync(filePath, 'utf8'))
}

function validateMatchingValues(message, left, right) {
  if (left !== undefined && right !== undefined && left !== right) {
    throw new Error(message)
  }
}
