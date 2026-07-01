export interface TagEntity {
  id: number
  userId: number
  teamId?: number | null
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
