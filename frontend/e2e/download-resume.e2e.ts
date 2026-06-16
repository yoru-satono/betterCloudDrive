import type { APIRequestContext } from '@playwright/test'
import { test, expect } from './fixtures/test'
import {
  authHeaders,
  backendURL,
  createFolder,
  uniqueName,
  uploadSampleFile,
} from './helpers/api'

test('supports resumable browser downloads for files and cached folder zips', async ({ request, e2eUser }) => {
  const fileName = `${uniqueName('resume-file')}.txt`
  const fileBytes = Buffer.from(`browser range resume ${Date.now()}\n`.repeat(32))
  const file = await uploadSampleFile(request, e2eUser.accessToken, null, fileName, fileBytes)

  const fileUrl = await createDownloadTicket(request, e2eUser.accessToken, `/api/v1/download/${file.fileId}/ticket`)
  const splitAt = Math.floor(fileBytes.length / 2)
  const fileHead = await request.get(fileUrl, {
    headers: { Range: `bytes=0-${splitAt - 1}` },
  })
  await expect(fileHead, await fileHead.text()).toBeOK()
  expect(fileHead.status()).toBe(206)
  expect(fileHead.headers()['accept-ranges']).toBe('bytes')
  expect(fileHead.headers()['content-range']).toBe(`bytes 0-${splitAt - 1}/${fileBytes.length}`)

  const fileTail = await request.get(fileUrl, {
    headers: { Range: `bytes=${splitAt}-` },
  })
  await expect(fileTail, await fileTail.text()).toBeOK()
  expect(fileTail.status()).toBe(206)
  expect(Buffer.concat([await fileHead.body(), await fileTail.body()])).toEqual(fileBytes)

  const folder = await createFolder(request, e2eUser.accessToken, uniqueName('resume-folder'))
  await uploadSampleFile(request, e2eUser.accessToken, folder.id, 'child.txt', 'folder zip range resume')

  const folderUrl = await createDownloadTicket(request, e2eUser.accessToken, `/api/v1/download/folders/${folder.id}/zip/ticket`)
  const zipFirstByte = await request.get(folderUrl, {
    headers: { Range: 'bytes=0-0' },
  })
  await expect(zipFirstByte, await zipFirstByte.text()).toBeOK()
  expect(zipFirstByte.status()).toBe(206)
  expect(zipFirstByte.headers()['accept-ranges']).toBe('bytes')
  const totalZipSize = totalSizeFromContentRange(zipFirstByte.headers()['content-range'])
  expect(totalZipSize).toBeGreaterThan(1)

  const zipRemainder = await request.get(folderUrl, {
    headers: { Range: 'bytes=1-' },
  })
  await expect(zipRemainder, await zipRemainder.text()).toBeOK()
  expect(zipRemainder.status()).toBe(206)
  expect(zipRemainder.headers()['content-range']).toBe(`bytes 1-${totalZipSize - 1}/${totalZipSize}`)

  const zipBytes = Buffer.concat([await zipFirstByte.body(), await zipRemainder.body()])
  expect(zipBytes.length).toBe(totalZipSize)
  expect(zipBytes.subarray(0, 2).toString('utf8')).toBe('PK')
})

async function createDownloadTicket(request: APIRequestContext, token: string, endpoint: string) {
  const response = await request.post(`${backendURL}${endpoint}`, {
    headers: authHeaders(token),
  })
  await expect(response, await response.text()).toBeOK()
  const body = await response.json() as { data: { url: string } }
  return `${backendURL}${body.data.url}`
}

function totalSizeFromContentRange(value: string | undefined) {
  const match = value?.match(/^bytes \d+-\d+\/(\d+)$/)
  expect(match, `Invalid Content-Range: ${value}`).toBeTruthy()
  return Number(match![1])
}
