import { isQtRuntime } from '@/platform/environment'
import { defineStore } from 'pinia'
import { ref, onScopeDispose } from 'vue'
import { previewFile } from '@/api/download'
import { getOfficeConfig, getTeamOfficeConfig } from '@/api/office'
import type { FileEntity } from '@/types/file'
import type { OnlyOfficeConfigResponse } from '@/types/office'
import { i18n, locale } from '@/i18n'
import { invoke } from '@/platform/desktop'
import { identityRevision } from '@/platform/identity'
import { toast } from 'vue-sonner'

const OFFICE = new Set(['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'ods', 'csv', 'ppt', 'pptx', 'odp'])
const isText = (file: FileEntity) => file.mimeType?.startsWith('text/') || ['application/xml', 'application/xhtml+xml'].includes(file.mimeType || '') || /\.(html?|xml)$/i.test(file.fileName)
const videoMime = (file: FileEntity) => file.mimeType?.startsWith('video/') ? file.mimeType
  : ({ mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', mkv: 'video/x-matroska', mov: 'video/quicktime', avi: 'video/x-msvideo' } as Record<string, string>)[file.fileName.split('.').pop()?.toLowerCase() || '']
const previewTarget = (file: FileEntity) => isText(file) ? { ...file, mimeType: 'text/plain' }
  : videoMime(file) ? { ...file, mimeType: videoMime(file) } : file
export const usePreviewStore = defineStore('preview', () => {
  const open = ref(false), target = ref<FileEntity | null>(null), teamId = ref<number | null>(null)
  const url = ref(''), error = ref(''), loading = ref(false)
  const officeConfig = ref<OnlyOfficeConfigResponse | null>(null), officeMode = ref(false)
  let revision = 0, mediaRevision = 0
  const revokeUrl = () => { if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
  const current = (request: number, identity: number) => request === revision && identity === identityRevision()
  const failed = () => i18n.global.t('file.preview.thisFileCannotBePreviewedAtTheMoment')
  function closePreview() {
    revision++
    open.value = false
    target.value = null
    teamId.value = null
    error.value = ''
    loading.value = false
    officeConfig.value = null
    officeMode.value = false
    revokeUrl()
  }
  const invalidate = () => { mediaRevision++; closePreview() }
  window.addEventListener('bcd:identity-invalidated', invalidate)
  onScopeDispose(() => { invalidate(); window.removeEventListener('bcd:identity-invalidated', invalidate) })
  async function refreshOfficeConfig() {
    if (!target.value || !officeMode.value) return
    const request = ++revision, identity = identityRevision(), file = target.value, team = teamId.value
    loading.value = true
    error.value = ''
    officeConfig.value = null
    try {
      const response = team ? await getTeamOfficeConfig(team, file.id) : await getOfficeConfig(file.id)
      if (current(request, identity)) officeConfig.value = response.data.data
    } catch { if (current(request, identity)) error.value = failed() }
    finally { if (current(request, identity)) loading.value = false }
  }
  async function openPreview(file: FileEntity, context?: { teamId?: number }) {
    if (isQtRuntime() && videoMime(file)) {
      const request = ++mediaRevision, identity = identityRevision()
      try { await invoke('media:open', { fileId: file.id, fileName: file.fileName, teamId: context?.teamId, locale: locale.value }) }
      catch (failure) {
        if (request === mediaRevision && identity === identityRevision() && !String(failure).includes('MEDIA_SUPERSEDED')) toast.error(failed())
      }
      return
    }
    const request = ++revision, identity = identityRevision()
    revokeUrl()
    target.value = previewTarget(file)
    teamId.value = context?.teamId ?? null
    open.value = true
    loading.value = true
    error.value = ''
    officeConfig.value = null
    officeMode.value = OFFICE.has(file.fileName.split('.').pop()?.toLowerCase() || '')
    if (officeMode.value) return refreshOfficeConfig()
    try {
      const response = await (context?.teamId ? previewFile(file.id, context.teamId) : previewFile(file.id))
      if (!current(request, identity)) return
      // Uploaded HTML/XML is source text, never executable same-origin content.
      const data = isText(file) ? new Blob([response.data], { type: 'text/plain;charset=utf-8' })
        : videoMime(file) ? new Blob([response.data], { type: videoMime(file) }) : response.data
      url.value = URL.createObjectURL(data)
    } catch { if (current(request, identity)) error.value = failed() }
    finally { if (current(request, identity)) loading.value = false }
  }
  const replaceTarget = (file: FileEntity) => { target.value = previewTarget(file) }
  return { open, target, teamId, url, error, loading, officeConfig, officeMode, openPreview, refreshOfficeConfig, replaceTarget, closePreview }
})
