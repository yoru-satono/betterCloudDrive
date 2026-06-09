<template>
  <div class="file-browser animate-fade-in">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn btn-primary btn-sm" @click="showCreateFolder = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/><line x1="12" y1="11" x2="12" y2="17"/><line x1="9" y1="14" x2="15" y2="14"/></svg>
          新建文件夹
        </button>
        <button class="btn btn-secondary btn-sm" @click="triggerUpload">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17,8 12,3 7,8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          上传文件
        </button>
        <input ref="fileInput" type="file" multiple hidden @change="onFileSelected" />
      </div>
      <div class="toolbar-right">
        <button class="btn btn-ghost btn-sm btn-icon" :class="{ active: files.viewMode === 'list' }" @click="files.setViewMode('list')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
        </button>
        <button class="btn btn-ghost btn-sm btn-icon" :class="{ active: files.viewMode === 'grid' }" @click="files.setViewMode('grid')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
        </button>
      </div>
    </div>

    <!-- File Grid View -->
    <div v-if="files.viewMode === 'grid'" class="file-grid stagger">
      <div v-if="files.currentParentId !== null" class="file-card parent-entry" @click="goToParent">
        <div class="file-card-icon"><span class="icon-back">📂</span></div>
        <div class="file-card-name">..</div>
        <div class="file-card-meta text-muted">返回上级</div>
      </div>
      <div v-for="f in files.files" :key="f.id" class="file-card" @click="onFileClick(f)" @contextmenu.prevent="openContextMenu($event, f)">
        <div class="file-card-icon">
          <span v-if="f.fileType === 'folder'" class="icon-folder">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="var(--amber)" stroke="var(--amber)" stroke-width="1"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
          </span>
          <span v-else class="icon-file" :style="{ color: fileColor(f) }">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="currentColor" opacity="0.15" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14,2 14,8 20,8"/></svg>
          </span>
        </div>
        <div class="file-card-name truncate">{{ f.fileName }}</div>
        <div class="file-card-meta text-mono text-muted">{{ f.fileType === 'folder' ? '文件夹' : formatSize(f.fileSize) }}</div>
      </div>
      <div v-if="files.files.length === 0 && !files.loading" class="empty-state">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="1" opacity="0.3"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
        <p>此目录为空</p>
        <span class="text-muted">拖拽文件到此处或点击上传</span>
      </div>
    </div>

    <!-- File List View -->
    <div v-else class="file-list">
      <div class="list-header">
        <span class="col-name" @click="files.sortBy='fileName'; files.fetchFiles(files.currentParentId)">名称 {{ files.sortBy === 'fileName' ? (files.order === 'asc' ? '↑' : '↓') : '' }}</span>
        <span class="col-size" @click="files.sortBy='fileSize'; files.fetchFiles(files.currentParentId)">大小</span>
        <span class="col-date" @click="files.sortBy='createdAt'; files.fetchFiles(files.currentParentId)">修改日期</span>
        <span class="col-actions"></span>
      </div>
      <div v-if="files.currentParentId !== null" class="file-row parent-row" @click="goToParent">
        <span class="col-name"><span class="file-icon-sm">📂</span><span class="file-name-text">..</span></span>
        <span class="col-size"></span>
        <span class="col-date text-muted">返回上级目录</span>
        <span class="col-actions"></span>
      </div>
      <div v-if="files.files.length === 0 && !files.loading" class="empty-state">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="1" opacity="0.3"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
        <p>此目录为空</p>
        <span class="text-muted">点击上方按钮创建文件夹或上传文件</span>
      </div>
      <div v-for="f in files.files" :key="f.id" class="file-row" @click="onFileClick(f)" @contextmenu.prevent="openContextMenu($event, f)">
        <span class="col-name">
          <span class="file-icon-sm">
            <svg v-if="f.fileType === 'folder'" width="20" height="20" viewBox="0 0 24 24" fill="var(--amber)" stroke="var(--amber)" stroke-width="1.5"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="currentColor" opacity="0.12" stroke="currentColor" stroke-width="1.5" :style="{ color: fileColor(f) }"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14,2 14,8 20,8"/></svg>
          </span>
          <span class="file-name-text truncate">{{ f.fileName }}</span>
        </span>
        <span class="col-size text-mono text-muted">{{ f.fileType === 'folder' ? '—' : formatSize(f.fileSize) }}</span>
        <span class="col-date text-muted">{{ formatDate(f.createdAt) }}</span>
        <span class="col-actions" @click.stop>
          <button class="btn btn-ghost btn-icon btn-sm" @click="openContextMenu($event, f)">⋯</button>
        </span>
      </div>
    </div>

    <!-- Upload progress -->
    <div v-if="uploading" class="upload-bar animate-fade-in">
      <div class="upload-info">
        <span class="upload-name truncate">{{ uploadFileName }}</span>
        <span class="upload-pct text-mono text-accent">{{ uploadProgress }}%</span>
      </div>
      <div class="upload-track"><div class="upload-fill" :style="{ width: uploadProgress + '%' }"></div></div>
    </div>

    <!-- Pagination -->
    <div v-if="files.pages > 1" class="pagination">
      <button class="btn btn-ghost btn-sm" :disabled="files.page <= 1" @click="files.fetchFiles(files.currentParentId, files.page - 1)">上一页</button>
      <span class="text-muted text-mono">{{ files.page }} / {{ files.pages }}</span>
      <button class="btn btn-ghost btn-sm" :disabled="files.page >= files.pages" @click="files.fetchFiles(files.currentParentId, files.page + 1)">下一页</button>
    </div>

    <!-- Dialogs -->
    <div v-if="showCreateFolder" class="dialog-overlay" @click.self="showCreateFolder = false">
      <div class="dialog animate-scale-in">
        <h3>新建文件夹</h3>
        <input v-model="newFolderName" class="input" placeholder="文件夹名称" @keyup.enter="createFolder" />
        <div class="dialog-actions">
          <button class="btn btn-ghost btn-sm" @click="showCreateFolder = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="createFolder">创建</button>
        </div>
      </div>
    </div>

    <div v-if="showRename" class="dialog-overlay" @click.self="showRename = false">
      <div class="dialog animate-scale-in">
        <h3>重命名</h3>
        <input v-model="renameValue" class="input" @keyup.enter="doRename" />
        <div class="dialog-actions">
          <button class="btn btn-ghost btn-sm" @click="showRename = false">取消</button>
          <button class="btn btn-primary btn-sm" @click="doRename">确定</button>
        </div>
      </div>
    </div>

    <!-- Context Menu (teleported to body to avoid CSS offset) -->
    <Teleport to="body">
      <div v-if="contextMenu.visible" class="ctx-overlay" @click="contextMenu.visible = false" @contextmenu.prevent="contextMenu.visible = false"></div>
      <div v-if="contextMenu.visible" class="context-menu animate-scale-in" :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }">
        <button v-if="contextMenu.file?.fileType === 'folder'" class="ctx-item" @click="enterFolder(contextMenu.file); contextMenu.visible = false">打开</button>
        <button class="ctx-item" @click="startRename(contextMenu.file); contextMenu.visible = false">重命名</button>
        <button class="ctx-item" @click="handleDelete(contextMenu.file); contextMenu.visible = false">删除</button>
        <button class="ctx-item" @click="downloadFile(contextMenu.file); contextMenu.visible = false">下载</button>
        <button class="ctx-item ctx-danger" @click="handleDelete(contextMenu.file); contextMenu.visible = false">移至回收站</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useFilesStore, type FileItem } from '@/stores/files'
import api from '@/api/client'

const route = useRoute()
const router = useRouter()
const files = useFilesStore()

const fileInput = ref<HTMLInputElement>()
const showCreateFolder = ref(false)
const newFolderName = ref('')
const showRename = ref(false)
const renameValue = ref('')
const renameTarget = ref<FileItem | null>(null)
const contextMenu = ref({ visible: false, x: 0, y: 0, file: null as FileItem | null })

function onFileClick(f: FileItem) {
  if (f.fileType === 'folder') enterFolder(f)
}
function goToParent() {
  // Navigate to the parent folder (the second-last breadcrumb)
  const parent = files.breadcrumb.length > 1 ? files.breadcrumb[files.breadcrumb.length - 2] : null
  if (parent) {
    files.breadcrumb.pop()
    if (parent.id) router.push(`/files/${parent.id}`)
    else router.push('/files')
    files.fetchFiles(parent.id)
  }
}
function enterFolder(f: FileItem) {
  // Check if folder is already in breadcrumb (user re-entering a parent)
  const idx = files.breadcrumb.findIndex(b => b.id === f.id)
  if (idx >= 0) {
    files.breadcrumb = files.breadcrumb.slice(0, idx + 1)
  } else {
    files.breadcrumb.push({ id: f.id, name: f.fileName })
  }
  if (f.id) router.push(`/files/${f.id}`)
  files.fetchFiles(f.id)
}

// When navigating via breadcrumb link or URL, trim breadcrumb
watch(() => route.params.folderId, (folderId) => {
  if (!folderId) {
    files.breadcrumb = [{ id: null, name: '根目录' }]
    return
  }
  const fid = Number(folderId)
  const idx = files.breadcrumb.findIndex(b => b.id === fid)
  if (idx >= 0) {
    files.breadcrumb = files.breadcrumb.slice(0, idx + 1)
  }
  // If not found (direct URL), keep existing breadcrumb + push will happen on next enterFolder
})
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFileName = ref('')

function triggerUpload() { fileInput.value?.click() }

async function onFileSelected(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  uploading.value = true
  uploadFileName.value = file.name
  uploadProgress.value = 0

  try {
    const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE)

    // Init chunked upload
    const initRes = await api.post('/upload/init', {
      parentId: files.currentParentId,
      fileName: file.name,
      fileSize: file.size,
      totalChunks
    })
    const sessionId = initRes.data.data.sessionId

    // Upload chunks
    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE
      const chunk = file.slice(start, start + CHUNK_SIZE)
      const form = new FormData()
      form.append('file', chunk, 'blob')
      await api.post(`/upload/${sessionId}/chunk?chunkNumber=${i}`, form, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      uploadProgress.value = Math.round(((i + 1) / totalChunks) * 100)
    }

    // Complete
    await api.post(`/upload/${sessionId}/complete`)
    uploadProgress.value = 100
    files.fetchFiles(files.currentParentId)
  } catch (err: any) {
    console.error('Upload failed:', err)
    alert('上传失败: ' + (err.response?.data?.message || err.message))
  } finally {
    uploading.value = false
    target.value = ''
  }
}
function openContextMenu(e: MouseEvent, f: FileItem) {
  contextMenu.value = { visible: true, x: e.clientX, y: e.clientY, file: f }
}

async function createFolder() {
  if (!newFolderName.value.trim()) return
  try {
    await files.createFolder(files.currentParentId, newFolderName.value.trim())
    newFolderName.value = ''
    showCreateFolder.value = false
  } catch (err: any) {
    const msg = err.response?.data?.message || err.message || '创建失败'
    alert('创建文件夹失败: ' + msg)
  }
}
function startRename(f: FileItem | null) {
  if (!f) return
  renameTarget.value = f
  renameValue.value = f.fileName
  showRename.value = true
}
async function doRename() {
  if (!renameTarget.value || !renameValue.value.trim()) return
  try {
    await files.renameFile(renameTarget.value.id, renameValue.value.trim())
    showRename.value = false
  } catch (err: any) {
    const msg = err.response?.data?.message || err.message || '重命名失败'
    alert('重命名失败: ' + msg)
  }
}
async function handleDelete(f: FileItem | null) {
  if (!f || !confirm(`确定删除 "${f.fileName}"？`)) return
  try {
    await files.deleteFiles([f.id])
  } catch (err: any) {
    const msg = err.response?.data?.message || err.message || '删除失败'
    alert('删除失败: ' + msg)
  }
}
function downloadFile(f: FileItem | null) {
  if (!f || f.fileType === 'folder') return
  window.open(`/api/v1/download/${f.id}`, '_blank')
}

function formatSize(bytes?: number): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}
function formatDate(d?: string): string {
  if (!d) return ''
  return new Date(d).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
function fileColor(f: FileItem): string {
  const ext = f.fileName?.split('.').pop()?.toLowerCase()
  const map: Record<string, string> = { pdf: 'var(--red)', jpg: 'var(--purple)', jpeg: 'var(--purple)', png: 'var(--purple)', gif: 'var(--purple)', mp4: 'var(--blue)', mp3: 'var(--amber)', zip: 'var(--amber)', txt: 'var(--accent)', md: 'var(--accent)', json: 'var(--amber)' }
  return map[ext || ''] || 'var(--text-secondary)'
}

watch(() => route.params.folderId, (id) => {
  const fid = id ? Number(id) : null
  files.fetchFiles(fid)
})

onMounted(() => {
  const fid = route.params.folderId ? Number(route.params.folderId) : null
  files.fetchFiles(fid)
})
</script>

<style scoped>
.file-browser { height: 100%; display: flex; flex-direction: column; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; gap: 12px; flex-wrap: wrap; }
.toolbar-left, .toolbar-right { display: flex; gap: 8px; }
.btn-icon.active { background: var(--accent-glow); color: var(--accent); }

.file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
.file-card {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 20px 12px; background: var(--bg-surface); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); cursor: pointer;
  transition: all var(--duration-fast);
}
.file-card:hover { border-color: var(--border-active); background: var(--bg-elevated); transform: translateY(-2px); }
.file-card-name { font-size: 13px; text-align: center; max-width: 100%; }
.file-card-meta { font-size: 11px; }

.list-header {
  display: flex; align-items: center; padding: 8px 12px;
  border-bottom: 1px solid var(--border-default); font-size: 11px;
  text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); font-weight: 500;
}
.file-row {
  display: flex; align-items: center; padding: 10px 12px;
  border-bottom: 1px solid var(--border-subtle); cursor: pointer;
  transition: background var(--duration-fast);
}
.file-row:hover { background: var(--bg-hover); }
.parent-row { opacity: 0.7; font-style: italic; }
.parent-row:hover { opacity: 1; background: var(--bg-hover); }
.parent-entry { opacity: 0.7; }
.parent-entry:hover { opacity: 1; }
.icon-back { font-size: 28px; }
.col-name { flex: 1; display: flex; align-items: center; gap: 10px; min-width: 0; cursor: pointer; }
.col-size { width: 100px; text-align: right; cursor: pointer; }
.col-date { width: 150px; text-align: right; cursor: pointer; }
.col-actions { width: 40px; text-align: center; }
.file-name-text { font-weight: 500; font-size: 13px; }
.file-icon-sm { flex-shrink: 0; }

.empty-state {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 60px 20px; text-align: center; color: var(--text-muted);
}
.empty-state p { font-weight: 500; color: var(--text-secondary); }

.upload-bar { margin-bottom: 16px; padding: 12px 16px; background: var(--bg-surface); border: 1px solid var(--border-subtle); border-radius: var(--radius-md); }
.upload-info { display: flex; justify-content: space-between; margin-bottom: 6px; font-size: 13px; }
.upload-track { height: 4px; background: var(--bg-hover); border-radius: 2px; overflow: hidden; }
.upload-fill { height: 100%; background: var(--accent); border-radius: 2px; transition: width 0.3s; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 20px; }

/* Dialogs */
.dialog-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.dialog { background: var(--bg-elevated); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 24px; width: 360px; max-width: 90vw; display: flex; flex-direction: column; gap: 16px; }
.dialog h3 { font-size: 16px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }

/* Context menu */
.ctx-overlay { position: fixed; inset: 0; z-index: 299; }
.context-menu {
  position: fixed; z-index: 300; background: var(--bg-elevated);
  border: 1px solid var(--border-default); border-radius: var(--radius-md);
  padding: 4px; min-width: 160px; box-shadow: var(--shadow-elevated);
}
.ctx-item {
  display: block; width: 100%; text-align: left; padding: 8px 12px;
  background: none; border: none; color: var(--text-primary);
  font-size: 13px; cursor: pointer; border-radius: var(--radius-sm);
  font-family: var(--font-body);
}
.ctx-item:hover { background: var(--bg-hover); }
.ctx-danger { color: var(--red); }
.ctx-danger:hover { background: var(--red-glow); }

@media (max-width: 767px) {
  .file-grid { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 8px; }
  .file-card { padding: 14px 8px; }
  .col-size, .col-date { display: none; }
}
</style>
