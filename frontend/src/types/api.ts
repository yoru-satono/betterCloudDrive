type ApiResponseData<T> = T extends void ? T | null | undefined : T

export interface ApiResponse<T> {
  code: number
  message: string
  data: ApiResponseData<T>
  timestamp?: number
  requestId?: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}
