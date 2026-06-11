export interface FileEntity {
  id: number
  userId: number
  parentId: number | null
  fileName: string
  fileType: 'file' | 'folder'
  mimeType: string | null
  fileSize: number
  storagePath: string | null
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
  storagePath: string
  fileSize: number
  md5Hash: string | null
  createdAt: string
}

export interface UploadSession {
  uploadId: string
  fileId: number
  fileName: string
  totalSize: number
  chunkSize: number
  totalChunks: number
  uploadedChunks: number[]
}

export interface BreadcrumbItem {
  id: number | null
  name: string
}
