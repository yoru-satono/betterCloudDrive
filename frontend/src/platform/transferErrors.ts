import { i18n } from '@/i18n'

const messages: Record<string, string> = {
  "RESULT_UNCERTAIN": "desktopTransfer.errors.resultUncertain",
  "NETWORK_RETRY_EXHAUSTED": "desktopTransfer.errors.networkRetryExhausted",
  "UPLOAD_SESSION_UNAVAILABLE": "desktopTransfer.errors.uploadSessionUnavailable",
  "DISK_FULL": "desktopTransfer.errors.diskFull",
  "STATE_WRITE_FAILED": "desktopTransfer.errors.stateWriteFailed",
  "SETTINGS_SAVE_FAILED": "desktopTransfer.errors.settingsSaveFailed",
  "HTTP_407": "desktopTransfer.errors.http407",
  "INVALID_TARGET": "desktopTransfer.errors.invalidTarget",
  "TARGET_EXISTS": "desktopTransfer.errors.targetExists",
  "SOURCE_CHANGED": "desktopTransfer.errors.sourceChanged",
  "REMOTE_CHANGED": "desktopTransfer.errors.remoteChanged",
  "PERMISSION_DENIED": "desktopTransfer.errors.permissionDenied",
  "INVALID_RANGE": "desktopTransfer.errors.invalidRange",
  "DOWNLOAD_CHECKSUM_MISMATCH": "desktopTransfer.errors.downloadChecksumMismatch",
  "FILE_UNAVAILABLE": "desktopTransfer.errors.fileUnavailable",
  "PARENT_NOT_READY": "desktopTransfer.errors.parentNotReady",
  "REMOTE_CANCEL_FAILED": "desktopTransfer.errors.remoteCancelFailed",
  "CANCEL_CLEANUP_FAILED": "desktopTransfer.errors.cancelCleanupFailed"
}
export function transferMessage(code: string) {
  return messages[code] ? i18n.global.t(messages[code]) : code
}
export function queueMessages<T>(value: T): T {
  const queue = value as { tasks?: { nodes: { error: string | null }[] }[] } | null
  if (queue && Array.isArray(queue.tasks)) {
    for (const task of queue.tasks) for (const node of task.nodes) if (node.error) node.error = transferMessage(node.error)
  }
  return value
}
