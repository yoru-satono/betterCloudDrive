// Explicit shared-artifact acceptance; fixtures are allocated by web_cutover.py.
import { test, expect } from './fixtures/test'
import { loginViaUi, uploadSampleFile, uniqueName } from './helpers/api'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

test('shares Web previews, real refresh, plain text, PDF rendering and browser video', async ({ page, request, e2eUser }) => {
  test.setTimeout(120_000)
  const root = process.env.BCD_SHARED_FIXTURES!
  if (!root || !process.env.JWT_ACCESS_SECRET) throw new Error('SHARED_ACCEPTANCE_ENVIRONMENT_REQUIRED')
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nS8AAAAASUVORK5CYII=', 'base64')
  const specs = [
    ['png', png], ['html', Buffer.from('<script>parent.sharedUnsafe=true</script><p>literal source</p>')],
    ['xml', Buffer.from('<root>literal XML</root>')], ['pdf', fs.readFileSync(path.join(root, 'preview.pdf'))],
    ['webm', fs.readFileSync(path.join(root, 'preview.webm'))],
  ] as const
  const uploads = []
  for (const [extension, bytes] of specs) uploads.push({ extension, ...await uploadSampleFile(request, e2eUser.accessToken, null, uniqueName('shared')+'.'+extension, bytes) })
  await loginViaUi(page, e2eUser)
  expect(await page.evaluate(() => window.__BCD_RUNTIME__)).toBeUndefined()
  const oldToken = await page.evaluate(() => localStorage.getItem('accessToken'))
  const pieces = oldToken!.split('.')
  const payload = JSON.parse(Buffer.from(pieces[1]!, 'base64url').toString())
  payload.iat = Math.floor(Date.now()/1000)-7200; payload.exp = Math.floor(Date.now()/1000)-3600
  const unsigned = pieces[0]+'.'+Buffer.from(JSON.stringify(payload)).toString('base64url')
  const expired = unsigned+'.'+crypto.createHmac('sha256', Buffer.from(process.env.JWT_ACCESS_SECRET!, 'base64')).update(unsigned).digest('base64url')
  let refreshes = 0
  page.on('request', req => { if (req.url().endsWith('/auth/refresh')) refreshes++ })
  await page.evaluate(token => localStorage.setItem('accessToken', token), expired)
  await page.reload()
  await expect(page.getByTestId('file-card-'+uploads[0]!.fileName)).toBeVisible()
  expect(refreshes).toBe(1)
  expect(await page.evaluate(() => localStorage.getItem('accessToken'))).not.toBe(expired)
  const dialog = page.getByRole('dialog', { name: '文件预览' })
  for (const file of uploads) {
    await page.getByTestId('file-card-'+file.fileName).click({ button: 'right' })
    await page.getByRole('menu').getByRole('menuitem', { name: '预览', exact: true }).click()
    await expect(dialog).toBeVisible()
    if (file.extension==='png') await expect.poll(() => dialog.locator('img').evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
    else if (file.extension==='html' || file.extension==='xml') {
      const source = dialog.frameLocator('iframe').locator('body')
      await expect(source).toContainText(file.extension==='html' ? '<script>' : '<root>')
      expect(await page.evaluate(() => !!(window as any).sharedUnsafe)).toBe(false)
    } else if (file.extension==='webm') {
      const video = dialog.locator('video')
      await expect(video).toBeVisible()
      try {
        await video.evaluate((element: HTMLVideoElement) => {
          element.muted = true
          void element.play().catch(error => { element.dataset.playError = error.name })
        })
        await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime), { timeout: 10_000 }).toBeGreaterThan(.5)
        expect(await video.evaluate((element: HTMLVideoElement) => element.videoWidth)).toBe(640)
        expect(await video.getAttribute('data-play-error')).toBeNull()
      } finally {
        const stats = await video.evaluate((element: HTMLVideoElement) => ({ readyState: element.readyState,
          networkState: element.networkState, width: element.videoWidth, currentTime: element.currentTime,
          errorCode: element.error?.code ?? 0, playError: element.dataset.playError ?? '' }))
        test.info().annotations.push({ type: 'shared-preview-media', description: JSON.stringify(stats) })
      }
    } else {
      const frame = dialog.locator('iframe')
      await expect(frame).toBeVisible()
      const shot = path.join(root, 'web-pdf.png')
      await expect.poll(async () => {
        await frame.screenshot({ path: shot })
        return execFileSync(process.env.BCD_TEST_PYTHON!, ['-c',
          'import sys;sys.path.insert(0,sys.argv[1]);from rendering import pdf_page_has_ink;print(int(pdf_page_has_ink(open(sys.argv[2],"rb").read())))',
          process.env.BCD_RENDERING_MODULE!, shot], { encoding: 'utf8' }).trim()
      }, { timeout: 20_000 }).toBe('1')
    }
    await dialog.getByRole('button', { name: '关闭' }).last().click()
  }
})
