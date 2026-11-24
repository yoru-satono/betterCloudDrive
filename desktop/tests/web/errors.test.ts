import { expect, it } from 'vitest'
import { setLocale } from '../../../frontend/src/i18n'
import { queueMessages, transferMessage } from '../../web/errors'
it('explains uncertain results and retained progress in both desktop languages', async () => {
  await setLocale('en-US')
  expect(transferMessage('RESULT_UNCERTAIN')).toContain('Check the server files')
  expect(transferMessage('NETWORK_RETRY_EXHAUSTED')).toContain('Progress is retained')
  await setLocale('zh-Hans')
  expect(transferMessage('RESULT_UNCERTAIN')).toContain('核对服务端')
  const queue = queueMessages({ tasks: [{ nodes: [{ error: 'DISK_FULL' }, { error: null }] }] })
  expect(queue.tasks[0]!.nodes[0]!.error).toContain('磁盘空间不足')
  expect(queue.tasks[0]!.nodes[1]!.error).toBeNull()
  expect(transferMessage('UNKNOWN_ERROR_CODE')).toBe('UNKNOWN_ERROR_CODE')
})
