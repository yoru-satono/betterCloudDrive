export interface OnlyOfficeConfigResponse {
  documentServerUrl: string
  editable: boolean
  config: Record<string, unknown>
}

export type OnlyOfficeDocEditor = {
  destroyEditor?: () => void
}

export type OnlyOfficeWindow = Window & {
  DocsAPI?: {
    DocEditor: new (elementId: string, config: Record<string, unknown>) => OnlyOfficeDocEditor
  }
}
