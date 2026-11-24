import { locale } from '@/i18n'

const messages: Record<string, [string, string]> = {
  RESULT_UNCERTAIN: ['操作结果待确认。请先核对服务端文件，再取消此任务并重新发起；直接继续可能产生重复对象。', 'The operation result is uncertain. Check the server files, then cancel this task and start again. Continuing could create duplicates.'],
  NETWORK_RETRY_EXHAUSTED: ['网络连接失败，有限重试已用尽；断点已保留，可在网络恢复后继续。', 'Network retries exhausted. Progress is retained; resume after the connection recovers.'],
  UPLOAD_SESSION_UNAVAILABLE: ['上传会话已失效，无法继续原断点；请取消此任务并重新选择源文件。', 'The upload session is unavailable. Cancel this task and select the source again.'],
  DISK_FULL: ['磁盘空间不足。释放目标磁盘空间后可继续。', 'The destination disk is full. Free space before resuming.'],
  STATE_WRITE_FAILED: ['本地任务状态无法保存，传输已停止。请检查应用数据目录的空间和权限，然后重启客户端。', 'Local task state could not be saved. Transfers stopped. Check application-data disk space and permissions, then restart the client.'],
  SETTINGS_SAVE_FAILED: ['设置未保存，请检查应用数据目录的空间和权限后重试。', 'Settings were not saved. Check application-data disk space and permissions, then retry.'],
  HTTP_407: ['代理认证失败，请检查代理用户名和密码。', 'Proxy authentication failed. Check the proxy username and password.'],
  INVALID_TARGET: ['目标路径包含符号链接或目录联接，请选择普通目录。', 'The destination contains a symbolic link or junction. Choose a regular directory.'],
  TARGET_EXISTS: ['目标已存在，未覆盖已有文件。请选择其他位置或名称。', 'The destination already exists and was not overwritten. Choose another name or location.'],
  SOURCE_CHANGED: ['源文件已变化，无法继续原上传；请重新选择源文件。', 'The source file changed. Select the source again to start a new upload.'],
  REMOTE_CHANGED: ['远端文件已变化，未发布下载结果；请重新发起下载。', 'The remote file changed. No download was published; start a new download.'],
  PERMISSION_DENIED: ['服务器拒绝访问，请确认空间或文件权限后再继续。', 'The server denied access. Check space or file permissions before resuming.'],
  INVALID_RANGE: ['服务器返回的断点范围不正确，下载已停止。', 'The server returned an invalid byte range. The download stopped.'],
  DOWNLOAD_CHECKSUM_MISMATCH: ['下载校验失败，未发布最终文件。', 'Download verification failed. No final file was published.'],
  FILE_UNAVAILABLE: ['文件或目录无法访问，可能被占用或没有权限；处理后可继续。', 'The file or directory is unavailable. Check permissions and close other programs before resuming.'],
  PARENT_NOT_READY: ['上级目录创建失败或尚未就绪，请从上级目录恢复。', 'The parent directory is not ready. Resume from the parent directory.'],
  REMOTE_CANCEL_FAILED: ['本地任务已取消，但服务端上传会话清理失败。', 'The local task was canceled, but remote upload-session cleanup failed.'],
  CANCEL_CLEANUP_FAILED: ['任务已停止，但临时分片清理失败；请解除占用后清理已结束任务。', 'The task stopped, but temporary-file cleanup failed. Close other programs, then clear finished tasks.'],
}
export function transferMessage(code: string) {
  return messages[code]?.[locale.value.startsWith('zh') ? 0 : 1] || code
}
export function queueMessages<T>(value: T): T {
  const queue = value as { tasks?: { nodes: { error: string | null }[] }[] } | null
  if (queue && Array.isArray(queue.tasks)) {
    for (const task of queue.tasks) for (const node of task.nodes) if (node.error) node.error = transferMessage(node.error)
  }
  return value
}
