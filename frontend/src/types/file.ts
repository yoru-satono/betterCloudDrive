export interface FileEntity {
  id: number
  userId: number
  teamId?: number | null
  parentId: number | null
  fileName: string
  fileType: 'file' | 'folder'
  mimeType: string | null
  fileSize: number
  storagePath: string | null
  thumbnailPath?: string | null
  md5Hash: string | null
  isDeleted: boolean
  versionCount: number
  createdAt: string
  updatedAt: string
}

export interface FileVersionEntity {
  id: number
  fileId: number
  versionNumber: number
  fileSize: number
  md5Hash: string | null
  createdAt: string
}

export interface RecycleBinItem {
  id: number
  parentId: number | null
  fileName: string
  fileType: 'file' | 'folder'
  mimeType: string | null
  fileSize: number
  md5Hash: string | null
  versionCount: number
  createdAt: string
  updatedAt: string
  deletedAt: string
}

export interface UploadSession {
  sessionId: string
  chunkSize?: number
  totalChunks: number
  uploadedChunks?: number
  missingChunks?: number[]
}

export interface BreadcrumbItem {
  id: number | null
  name: string
}
