import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { FileEntity } from '@/types/file'
import { previewFile } from '@/api/download'
import { getOfficeConfig, getTeamOfficeConfig } from '@/api/office'
import type { OnlyOfficeConfigResponse } from '@/types/office'

const OFFICE_EXTENSIONS = new Set(['doc', 'docx', 'odt', 'rtf', 'xls', 'xlsx', 'ods', 'csv', 'ppt', 'pptx', 'odp'])

export const usePreviewStore = defineStore('preview', () => {
  const open = ref(false)
  const target = ref<FileEntity | null>(null)
  const teamId = ref<number | null>(null)
  const url = ref('')
  const error = ref('')
  const loading = ref(false)
  const officeConfig = ref<OnlyOfficeConfigResponse | null>(null)
  const officeMode = ref(false)

  async function openPreview(file: FileEntity, context?: { teamId?: number }) {
    target.value = file
    teamId.value = context?.teamId ?? null
    open.value = true
    loading.value = true
    error.value = ''
    officeConfig.value = null
    officeMode.value = isOfficeFile(file)
    revokeUrl()
    try {
      if (officeMode.value) {
        await refreshOfficeConfig()
      } else {
        const res = teamId.value ? await previewFile(file.id, teamId.value) : await previewFile(file.id)
        url.value = URL.createObjectURL(res.data)
      }
    } catch {
      error.value = __t('file.preview.thisFileCannotBePreviewedAtTheMoment')
    } finally {
      loading.value = false
    }
  }

  async function refreshOfficeConfig() {
    if (!target.value || !officeMode.value) return
    loading.value = true
    error.value = ''
    officeConfig.value = null
    try {
      const res = teamId.value ? await getTeamOfficeConfig(teamId.value, target.value.id) : await getOfficeConfig(target.value.id)
      officeConfig.value = res.data.data
    } catch {
      error.value = __t('file.preview.thisFileCannotBePreviewedAtTheMoment')
    } finally {
      loading.value = false
    }
  }

  function replaceTarget(file: FileEntity) {
    target.value = file
  }

  function closePreview() {
    open.value = false
    target.value = null
    teamId.value = null
    error.value = ''
    loading.value = false
    officeConfig.value = null
    officeMode.value = false
    revokeUrl()
  }

  function revokeUrl() {
    if (url.value) {
      URL.revokeObjectURL(url.value)
      url.value = ''
    }
  }

  return { open, target, teamId, url, error, loading, officeConfig, officeMode, openPreview, refreshOfficeConfig, replaceTarget, closePreview }
})

function isOfficeFile(file: FileEntity) {
  const dot = file.fileName.lastIndexOf('.')
  if (dot < 0) return false
  return OFFICE_EXTENSIONS.has(file.fileName.slice(dot + 1).toLowerCase())
}
