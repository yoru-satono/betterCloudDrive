import type { FileEntity } from '@/types/file'

export const FILE_ACTION_EVENT = 'bcd:file-action'

export type FileActionType = 'preview' | 'enter-folder' | 'context-menu' | 'open-location'

export interface FileActionDetail {
  action: FileActionType
  file: FileEntity
  event?: MouseEvent
  afterAction?: () => void
  handled?: boolean
}

export function dispatchFileAction(detail: FileActionDetail) {
  window.dispatchEvent(new CustomEvent<FileActionDetail>(FILE_ACTION_EVENT, { detail }))
  return detail.handled === true
}

export function onFileAction(handler: (detail: FileActionDetail) => void) {
  const listener = (event: Event) => {
    handler((event as CustomEvent<FileActionDetail>).detail)
  }
  window.addEventListener(FILE_ACTION_EVENT, listener)
  return () => window.removeEventListener(FILE_ACTION_EVENT, listener)
}
