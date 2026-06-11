export interface TagEntity {
  id: number
  userId: number
  tagName: string
  color: string | null
  fileCount: number
  createdAt: string
}

export interface FileTagEntity {
  fileId: number
  tagId: number
  tagName: string
  color: string | null
}
