import { APIRequestContext } from '@playwright/test'
import { test, expect } from './fixtures/test'
import {
  addTeamMember,
  authHeaders,
  backendURL,
  createTeam,
  createTeamVersionRows,
  createUser,
  loginViaUi,
  uniqueName,
  uploadTeamSampleFile,
} from './helpers/api'

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'
type Actor = {
  name: 'owner' | 'admin' | 'editor' | 'viewer' | 'outsider'
  token: string
}

test.describe('team permission matrix', () => {
  test('enforces team role permissions on API routes', async ({ request, e2eUser }) => {
    const admin = await createUser(request)
    const editor = await createUser(request)
    const viewer = await createUser(request)
    const outsider = await createUser(request)
    const target = await createUser(request)
    const adminTarget = await createUser(request)

    const team = await createTeam(request, e2eUser.accessToken, uniqueName('matrix_team'))
    const file = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('matrix_file')}.txt`,
      'team matrix file',
    )
    const versionFile = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('matrix_version')}.txt`,
      'team matrix version',
    )
    await createTeamVersionRows(versionFile.fileId, e2eUser.userId, [1])
    await addTeamMember(request, e2eUser.accessToken, team.id, admin.username, 'ADMIN')
    await addTeamMember(request, e2eUser.accessToken, team.id, editor.username, 'EDITOR')
    await addTeamMember(request, e2eUser.accessToken, team.id, viewer.username, 'VIEWER')

    const actors: Actor[] = [
      { name: 'owner', token: e2eUser.accessToken },
      { name: 'admin', token: admin.accessToken },
      { name: 'editor', token: editor.accessToken },
      { name: 'viewer', token: viewer.accessToken },
      { name: 'outsider', token: outsider.accessToken },
    ]
    const writeActors = actors.filter(actor => ['owner', 'admin', 'editor'].includes(actor.name))
    const manageActors = actors.filter(actor => ['owner', 'admin'].includes(actor.name))
    const readOnlyActors = actors.filter(actor => actor.name === 'viewer')
    const deniedActors = actors.filter(actor => actor.name === 'outsider')

    for (const actor of actors.filter(actor => actor.name !== 'outsider')) {
      await expectApiCode(request, actor, 'GET', `/api/v1/teams/${team.id}/files`, 200)
      await expectApiCode(request, actor, 'GET', `/api/v1/teams/${team.id}/files/${file.fileId}`, 200)
      await expectApiCode(request, actor, 'GET', `/api/v1/teams/${team.id}/files/${versionFile.fileId}/versions`, 200)
    }
    await expectForbidden(request, actors.find(actor => actor.name === 'outsider')!, 'GET', `/api/v1/teams/${team.id}/files`)

    for (const actor of writeActors) {
      await expectApiCode(request, actor, 'POST', `/api/v1/teams/${team.id}/files/folder`, 200, {
        parentId: null,
        folderName: uniqueName(`${actor.name}_folder`),
      })
      await expectApiCode(request, actor, 'POST', `/api/v1/teams/${team.id}/shares`, 200, {
        fileId: file.fileId,
        maxVisits: 5,
      })
    }
    for (const actor of [...readOnlyActors, ...deniedActors]) {
      await expectForbidden(request, actor, 'POST', `/api/v1/teams/${team.id}/files/folder`, {
        parentId: null,
        folderName: uniqueName(`${actor.name}_forbidden_folder`),
      })
      await expectForbidden(request, actor, 'POST', `/api/v1/teams/${team.id}/shares`, {
        fileId: file.fileId,
      })
    }

    const tag = await expectApiData<{ id: number }>(request, actors[0], 'POST', `/api/v1/teams/${team.id}/tags`, {
      tagName: uniqueName('matrix_tag'),
      color: '#00d4aa',
    })
    await expectApiCode(request, actors.find(actor => actor.name === 'admin')!, 'POST', `/api/v1/teams/${team.id}/tags`, 200, {
      tagName: uniqueName('matrix_admin_tag'),
      color: '#1890ff',
    })
    await expectForbidden(request, actors.find(actor => actor.name === 'editor')!, 'POST', `/api/v1/teams/${team.id}/tags`, {
      tagName: uniqueName('matrix_editor_tag'),
      color: '#ff4d4f',
    })
    await expectApiCode(request, actors.find(actor => actor.name === 'editor')!, 'POST', `/api/v1/teams/${team.id}/tags/${tag.id}/files`, 200, {
      fileIds: [file.fileId],
    })
    await expectForbidden(request, actors.find(actor => actor.name === 'viewer')!, 'POST', `/api/v1/teams/${team.id}/tags/${tag.id}/files`, {
      fileIds: [file.fileId],
    })

    for (const actor of manageActors) {
      await expectApiCode(request, actor, 'GET', `/api/v1/teams/${team.id}/audit-logs?page=1&size=5`, 200)
    }
    for (const actor of actors.filter(actor => ['editor', 'viewer', 'outsider'].includes(actor.name))) {
      await expectForbidden(request, actor, 'GET', `/api/v1/teams/${team.id}/audit-logs?page=1&size=5`)
      await expectForbidden(request, actor, 'POST', `/api/v1/teams/${team.id}/members`, {
        username: target.username,
        role: 'VIEWER',
      })
      await expectForbidden(request, actor, 'POST', `/api/v1/teams/${team.id}/invites`, {
        email: `${uniqueName(actor.name)}@test.local`,
        role: 'VIEWER',
      })
    }

    await expectApiCode(request, actors.find(actor => actor.name === 'admin')!, 'POST', `/api/v1/teams/${team.id}/members`, 200, {
      username: target.username,
      role: 'VIEWER',
    })
    await expectForbidden(request, actors.find(actor => actor.name === 'admin')!, 'POST', `/api/v1/teams/${team.id}/members`, {
      username: adminTarget.username,
      role: 'ADMIN',
    })
    await expectForbidden(request, actors.find(actor => actor.name === 'admin')!, 'POST', `/api/v1/teams/${team.id}/invites`, {
      email: `${uniqueName('admin_invite_admin')}@test.local`,
      role: 'ADMIN',
    })
    await expectForbidden(request, actors.find(actor => actor.name === 'admin')!, 'DELETE', `/api/v1/teams/${team.id}`)
  })

  test('hides write-only actions from team viewers in the web UI', async ({ page, request, e2eUser }) => {
    const viewer = await createUser(request)
    const team = await createTeam(request, e2eUser.accessToken, uniqueName('viewer_ui_team'))
    const file = await uploadTeamSampleFile(
      request,
      e2eUser.accessToken,
      team.id,
      null,
      `${uniqueName('viewer_ui')}.txt`,
      'viewer ui content',
    )
    await addTeamMember(request, e2eUser.accessToken, team.id, viewer.username, 'VIEWER')

    await loginViaUi(page, viewer)
    await page.goto(`/teams/${team.id}/files`)
    await expect(page.getByTestId(`file-card-${file.fileName}`)).toBeVisible()
    await expect(page.getByRole('button', { name: '上传文件' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '新建文件夹' })).toHaveCount(0)

    await page.getByTestId(`file-card-${file.fileName}`).click({ button: 'right' })
    const menu = page.getByRole('menu')
    await expect(menu.getByRole('menuitem', { name: '下载' })).toBeVisible()
    await expect(menu.getByRole('menuitem', { name: '收藏' })).toBeVisible()
    await expect(menu.getByRole('menuitem', { name: '分享' })).toHaveCount(0)
    await expect(menu.getByRole('menuitem', { name: '标签' })).toHaveCount(0)
    await expect(menu.getByRole('menuitem', { name: '删除' })).toHaveCount(0)
  })
})

async function expectApiCode(
  request: APIRequestContext,
  actor: Actor,
  method: Method,
  path: string,
  expectedCode: number,
  data?: Record<string, unknown>,
) {
  const body = await apiCall(request, actor, method, path, data)
  expect(body.code, `${actor.name} ${method} ${path}`).toBe(expectedCode)
}

async function expectForbidden(
  request: APIRequestContext,
  actor: Actor,
  method: Method,
  path: string,
  data?: Record<string, unknown>,
) {
  const body = await apiCall(request, actor, method, path, data)
  expect(body.code, `${actor.name} ${method} ${path}`).toBe(403)
}

async function expectApiData<T>(
  request: APIRequestContext,
  actor: Actor,
  method: Method,
  path: string,
  data?: Record<string, unknown>,
) {
  const body = await apiCall(request, actor, method, path, data)
  expect(body.code, `${actor.name} ${method} ${path}`).toBe(200)
  return body.data as T
}

async function apiCall(
  request: APIRequestContext,
  actor: Actor,
  method: Method,
  path: string,
  data?: Record<string, unknown>,
) {
  const res = await request.fetch(`${backendURL}${path}`, {
    method,
    headers: authHeaders(actor.token),
    data,
  })
  return res.json() as Promise<{ code: number; data?: unknown; message?: string }>
}
