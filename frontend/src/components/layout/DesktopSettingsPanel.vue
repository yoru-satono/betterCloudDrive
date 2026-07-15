<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { computed, onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import { toast } from 'vue-sonner'
import {
  bytesToMbps,
  chooseDefaultDownloadDirectory,
  DEFAULT_DESKTOP_SETTINGS,
  getDesktopSettings,
  isDesktopSettingsRuntime,
  mbpsToBytes,
  saveDesktopSettings,
  type DesktopSettings,
  type ProxyMode,
} from '@/api/desktopSettings'

const loading = ref(false)
const saving = ref(false)
const settings = ref<DesktopSettings>({ ...DEFAULT_DESKTOP_SETTINGS })
const uploadLimit = ref('')
const downloadLimit = ref('')
const maxConcurrentUploads = ref(String(DEFAULT_DESKTOP_SETTINGS.maxConcurrentUploads))
const maxConcurrentDownloads = ref(String(DEFAULT_DESKTOP_SETTINGS.maxConcurrentDownloads))

const proxyModes: Array<{ value: ProxyMode; label: string }> = [
  { value: 'system', label: __t('desktop.desktopsettings.followTheSystem') },
  { value: 'manual', label: __t('desktop.desktopsettings.manualProxy') },
  { value: 'disabled', label: __t('desktop.desktopsettings.turnOffProxy') },
]

const isDesktop = computed(() => isDesktopSettingsRuntime())
const isManualProxy = computed(() => settings.value.proxyMode === 'manual')

onMounted(loadSettings)

function applySettings(nextSettings: DesktopSettings) {
  settings.value = nextSettings
  uploadLimit.value = bytesToMbps(nextSettings.uploadLimitBytesPerSec)
  downloadLimit.value = bytesToMbps(nextSettings.downloadLimitBytesPerSec)
  maxConcurrentUploads.value = String(nextSettings.maxConcurrentUploads)
  maxConcurrentDownloads.value = String(nextSettings.maxConcurrentDownloads)
}

async function loadSettings() {
  loading.value = true
  try {
    applySettings(await getDesktopSettings())
  } catch (e) {
    toast.error(e instanceof Error ? e.message : __t('desktop.desktopsettings.failedToReadSettings'))
  } finally {
    loading.value = false
  }
}

async function chooseDownloadDir() {
  const path = await chooseDefaultDownloadDirectory()
  if (path) settings.value.defaultDownloadDir = path
}

async function saveSettings() {
  const validation = validateSettings()
  if (validation) {
    toast.error(validation)
    return
  }

  saving.value = true
  try {
    applySettings(await saveDesktopSettings({
      ...settings.value,
      uploadLimitBytesPerSec: mbpsToBytes(uploadLimit.value),
      downloadLimitBytesPerSec: mbpsToBytes(downloadLimit.value),
      maxConcurrentUploads: Number(maxConcurrentUploads.value),
      maxConcurrentDownloads: Number(maxConcurrentDownloads.value),
      proxyUrl: settings.value.proxyUrl.trim(),
      proxyUsername: settings.value.proxyUsername.trim(),
    }))
    toast.success(__t('desktop.desktopsettings.settingsSaved'))
  } catch (e) {
    toast.error(e instanceof Error ? e.message : __t('desktop.desktopsettings.failedToSaveSettings'))
  } finally {
    saving.value = false
  }
}

function validateSettings() {
  const uploadConcurrency = Number(maxConcurrentUploads.value)
  const downloadConcurrency = Number(maxConcurrentDownloads.value)
  if (!Number.isInteger(uploadConcurrency) || uploadConcurrency < 1 || uploadConcurrency > 16) {
    return __t('desktop.desktopsettings.theNumberOfConcurrentUploadsMustBeBetween116')
  }
  if (!Number.isInteger(downloadConcurrency) || downloadConcurrency < 1 || downloadConcurrency > 16) {
    return __t('desktop.desktopsettings.theNumberOfConcurrentDownloadsMustBeBetween116')
  }
  if (uploadLimit.value && Number(uploadLimit.value) < 0) return __t('desktop.desktopsettings.uploadSpeedLimitCannotBeANegativeNumber')
  if (downloadLimit.value && Number(downloadLimit.value) < 0) return __t('desktop.desktopsettings.downloadSpeedLimitCannotBeANegativeNumber')
  if (settings.value.proxyMode === 'manual') {
    const lower = settings.value.proxyUrl.trim().toLowerCase()
    if (!lower.startsWith('http://') && !lower.startsWith('https://') && !lower.startsWith('socks5://')) {
      return __t('desktop.desktopsettings.proxyAddressMustStartWithHttpHttpsOrSocks5')
    }
  }
  return ''
}
</script>

<template>
  <div class="desktop-settings-panel">
    <div class="desktop-settings-panel__toolbar">
      <span v-if="loading" class="desktop-settings-panel__status">{{ __t('desktop.desktopsettings.readingSettings') }}</span>
      <span v-else class="desktop-settings-panel__status">{{ __t('desktop.desktopsettings.builtInClientUploadsAndDownloadsUseTheseSettings') }}</span>
      <OButton variant="primary" :loading="saving" :disabled="loading || !isDesktop" @click="saveSettings">{{ __t('commonUi.desktopsettings.save') }}</OButton>
    </div>

    <div v-if="!isDesktop" class="settings-empty">{{ __t('desktop.desktopsettings.settingsOnlyTakeEffectInTauriClient') }}</div>

    <template v-else>
      <section class="settings-section">
        <div class="settings-section__head">
          <h3>{{ __t('desktop.desktopsettings.transmission') }}</h3>
          <span>{{ __t('desktop.desktopsettings.leaveBlankOr0ToIndicateNoSpeedLimitIn') }}</span>
        </div>
        <div class="settings-grid">
          <OInput v-model="uploadLimit" :label="__t('desktop.desktopsettings.uploadSpeedLimit')" type="number" :placeholder="__t('desktop.desktopsettings.noSpeedLimit')" />
          <OInput v-model="downloadLimit" :label="__t('desktop.desktopsettings.downloadSpeedLimit')" type="number" :placeholder="__t('desktop.desktopsettings.noSpeedLimit')" />
          <OInput v-model="maxConcurrentUploads" :label="__t('desktop.desktopsettings.numberOfConcurrentUploads')" type="number" placeholder="3" />
          <OInput v-model="maxConcurrentDownloads" :label="__t('desktop.desktopsettings.numberOfConcurrentDownloads')" type="number" placeholder="3" />
        </div>
      </section>

      <section class="settings-section">
        <div class="settings-section__head">
          <h3>{{ __t('desktop.desktopsettings.downloadLocation') }}</h3>
          <span>{{ __t('desktop.desktopsettings.onceSetUpSingleFileAndFolderDownloadsWillBe') }}</span>
        </div>
        <div class="download-dir">
          <OInput
            :model-value="settings.defaultDownloadDir || ''"
            :label="__t('desktop.desktopsettings.defaultDownloadLocation')"
            :placeholder="__t('desktop.desktopsettings.notSetAskedWhenDownloading')"
            disabled
          />
          <div class="download-dir__actions">
            <OButton variant="ghost" @click="chooseDownloadDir">{{ __t('desktop.desktopsettings.selectDirectory') }}</OButton>
            <OButton variant="subtle" @click="settings.defaultDownloadDir = null">{{ __t('desktop.desktopsettings.clear') }}</OButton>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <div class="settings-section__head">
          <h3>{{ __t('desktop.desktopsettings.acting') }}</h3>
          <span>{{ __t('desktop.desktopsettings.proxySettingsOnlyAffectBuiltInClientUploadsAndDownloads') }}</span>
        </div>
        <div class="proxy-mode">
          <button
            v-for="mode in proxyModes"
            :key="mode.value"
            type="button"
            class="proxy-mode__item"
            :class="{ 'proxy-mode__item--active': settings.proxyMode === mode.value }"
            @click="settings.proxyMode = mode.value"
          >
            {{ mode.label }}
          </button>
        </div>
        <div class="settings-grid">
          <OInput
            v-model="settings.proxyUrl"
            :label="__t('desktop.desktopsettings.proxyAddress')"
            placeholder="http://127.0.0.1:7890"
            :disabled="!isManualProxy"
          />
          <OInput
            v-model="settings.proxyUsername"
            :label="__t('commonUi.desktopsettings.username')"
            :placeholder="__t('desktop.desktopsettings.optional')"
            :disabled="!isManualProxy"
          />
          <OInput
            v-model="settings.proxyPassword"
            :label="__t('commonUi.desktopsettings.password')"
            type="password"
            :placeholder="__t('desktop.desktopsettings.optional')"
            :disabled="!isManualProxy"
          />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.desktop-settings-panel {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.desktop-settings-panel__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}
.desktop-settings-panel__status,
.settings-section__head span,
.settings-empty {
  color: var(--text-muted);
  font-size: 12px;
}
.settings-section {
  border-top: 1px solid var(--border);
  padding: 20px 0;
}
.settings-section:last-child {
  padding-bottom: 0;
}
.settings-section__head {
  margin-bottom: 14px;
}
.settings-section__head h3 {
  font-size: 14px;
  margin-bottom: 4px;
}
.settings-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.download-dir {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  align-items: end;
}
.download-dir__actions {
  display: flex;
  gap: 8px;
}
.proxy-mode {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 3px;
  margin-bottom: 14px;
  background: var(--bg-surface);
}
.proxy-mode__item {
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
  padding: 6px 10px;
}
.proxy-mode__item--active {
  background: var(--accent-dim);
  color: var(--accent);
}
@media (max-width: 720px) {
  .settings-grid,
  .download-dir {
    grid-template-columns: 1fr;
  }
  .download-dir__actions {
    justify-content: flex-start;
  }
}
</style>
