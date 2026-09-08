import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { loadWorkspaceE2EEnv, parseEnvText } from './e2e-env.mjs'

const temporaryRoots = []

afterEach(() => {
  for (const root of temporaryRoots.splice(0)) {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

describe('E2E environment loading', () => {
  it('parses comments, exports, quotes, and values containing equals signs', () => {
    expect(parseEnvText(`
# ignored
PLAIN=value
export DOUBLE="two words"
SINGLE='three words'
TOKEN=header.payload=signature
`)).toEqual({
      PLAIN: 'value',
      DOUBLE: 'two words',
      SINGLE: 'three words',
      TOKEN: 'header.payload=signature',
    })
  })

  it('loads workspace credentials, maps the published port, and preserves explicit overrides', () => {
    const root = createWorkspace({
      root: 'HOST_POSTGRES_PORT=15432\nONLYOFFICE_JWT_SECRET=office-secret\n',
      database: 'POSTGRES_USER=bcd_user\nPOSTGRES_PASSWORD=db-secret\nDB_PASSWORD=db-secret\n',
      backend: 'ONLYOFFICE_JWT_SECRET=office-secret\n',
    })

    const env = loadWorkspaceE2EEnv(root, { POSTGRES_USER: 'remote_user' })

    expect(env.POSTGRES_USER).toBe('remote_user')
    expect(env.POSTGRES_PASSWORD).toBe('db-secret')
    expect(env.ONLYOFFICE_JWT_SECRET).toBe('office-secret')
    expect(env.E2E_POSTGRES_HOST).toBe('127.0.0.1')
    expect(env.E2E_POSTGRES_PORT).toBe('15432')
  })

  it('rejects mismatched database credentials without exposing either value', () => {
    const root = createWorkspace({
      database: 'POSTGRES_PASSWORD=postgres-secret\nDB_PASSWORD=backend-secret\n',
    })

    expect(() => loadWorkspaceE2EEnv(root, {})).toThrowError(
      'POSTGRES_PASSWORD and DB_PASSWORD must match in config/env-files/database.env',
    )
    try {
      loadWorkspaceE2EEnv(root, {})
    } catch (error) {
      expect(String(error)).not.toContain('postgres-secret')
      expect(String(error)).not.toContain('backend-secret')
    }
  })

  it('rejects mismatched OnlyOffice secrets without exposing either value', () => {
    const root = createWorkspace({
      root: 'ONLYOFFICE_JWT_SECRET=document-server-secret\n',
      backend: 'ONLYOFFICE_JWT_SECRET=backend-secret\n',
    })

    expect(() => loadWorkspaceE2EEnv(root, {})).toThrowError(
      'ONLYOFFICE_JWT_SECRET must match in .env and config/env-files/backend.env',
    )
    try {
      loadWorkspaceE2EEnv(root, {})
    } catch (error) {
      expect(String(error)).not.toContain('document-server-secret')
      expect(String(error)).not.toContain('backend-secret')
    }
  })
})

function createWorkspace(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bcd-e2e-env-'))
  temporaryRoots.push(root)
  fs.mkdirSync(path.join(root, 'config/env-files'), { recursive: true })
  if (files.root) fs.writeFileSync(path.join(root, '.env'), files.root)
  if (files.database) fs.writeFileSync(path.join(root, 'config/env-files/database.env'), files.database)
  if (files.backend) fs.writeFileSync(path.join(root, 'config/env-files/backend.env'), files.backend)
  return root
}
