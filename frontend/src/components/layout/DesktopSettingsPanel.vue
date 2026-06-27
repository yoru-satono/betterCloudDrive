<script setup lang="ts">
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
  { value: 'system', label: '跟随系统' },
  { value: 'manual', label: '手动代理' },
  { value: 'disabled', label: '关闭代理' },
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
    toast.error(e instanceof Error ? e.message : '读取设置失败')
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
    toast.success('设置已保存')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '保存设置失败')
  } finally {
    saving.value = false
  }
}

function validateSettings() {
  const uploadConcurrency = Number(maxConcurrentUploads.value)
  const downloadConcurrency = Number(maxConcurrentDownloads.value)
  if (!Number.isInteger(uploadConcurrency) || uploadConcurrency < 1 || uploadConcurrency > 16) {
    return '上传并发数需在 1-16 之间'
  }
  if (!Number.isInteger(downloadConcurrency) || downloadConcurrency < 1 || downloadConcurrency > 16) {
    return '下载并发数需在 1-16 之间'
  }
  if (uploadLimit.value && Number(uploadLimit.value) < 0) return '上传限速不能为负数'
  if (downloadLimit.value && Number(downloadLimit.value) < 0) return '下载限速不能为负数'
  if (settings.value.proxyMode === 'manual') {
    const lower = settings.value.proxyUrl.trim().toLowerCase()
    if (!lower.startsWith('http://') && !lower.startsWith('https://') && !lower.startsWith('socks5://')) {
      return '代理地址必须以 http://、https:// 或 socks5:// 开头'
    }
  }
  return ''
}
</script>

<template>
  <div class="desktop-settings-panel">
    <div class="desktop-settings-panel__toolbar">
      <span v-if="loading" class="desktop-settings-panel__status">正在读取设置...</span>
      <span v-else class="desktop-settings-panel__status">客户端内置上传和下载会使用这些设置。</span>
      <OButton variant="primary" :loading="saving" :disabled="loading || !isDesktop" @click="saveSettings">
        保存
      </OButton>
    </div>

    <div v-if="!isDesktop" class="settings-empty">
      设置仅在 Tauri 客户端中生效。
    </div>

    <template v-else>
      <section class="settings-section">
        <div class="settings-section__head">
          <h3>传输</h3>
          <span>留空或 0 表示不限速，单位为 MB/s。</span>
        </div>
        <div class="settings-grid">
          <OInput v-model="uploadLimit" label="上传限速" type="number" placeholder="不限速" />
          <OInput v-model="downloadLimit" label="下载限速" type="number" placeholder="不限速" />
          <OInput v-model="maxConcurrentUploads" label="上传并发数" type="number" placeholder="3" />
          <OInput v-model="maxConcurrentDownloads" label="下载并发数" type="number" placeholder="3" />
        </div>
      </section>

      <section class="settings-section">
        <div class="settings-section__head">
          <h3>下载位置</h3>
          <span>设置后，单文件和文件夹下载将直接保存到该目录。</span>
        </div>
        <div class="download-dir">
          <OInput
            :model-value="settings.defaultDownloadDir || ''"
            label="默认下载位置"
            placeholder="未设置，下载时询问"
            disabled
          />
          <div class="download-dir__actions">
            <OButton variant="ghost" @click="chooseDownloadDir">选择目录</OButton>
            <OButton variant="subtle" @click="settings.defaultDownloadDir = null">清空</OButton>
          </div>
        </div>
      </section>

      <section class="settings-section">
        <div class="settings-section__head">
          <h3>代理</h3>
          <span>代理设置只影响客户端内置上传和下载。</span>
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
            label="代理地址"
            placeholder="http://127.0.0.1:7890"
            :disabled="!isManualProxy"
          />
          <OInput
            v-model="settings.proxyUsername"
            label="用户名"
            placeholder="可选"
            :disabled="!isManualProxy"
          />
          <OInput
            v-model="settings.proxyPassword"
            label="密码"
            type="password"
            placeholder="可选"
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
