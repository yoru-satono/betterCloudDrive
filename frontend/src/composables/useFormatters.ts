export function useFormatters() {
  function formatSize(bytes: number | null | undefined): string {
    if (bytes == null || bytes === 0) return '0 B'
    const k = 1024
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${units[i]}`
  }

  function formatDate(dateStr: string | null | undefined): string {
    if (!dateStr) return '-'
    const d = new Date(dateStr)
    const now = new Date()
    const diff = (now.getTime() - d.getTime()) / 1000
    if (diff < 60) return '刚刚'
    if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
    if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
    if (diff < 604800) return `${Math.floor(diff / 86400)} 天前`
    return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' })
  }

  function formatDateFull(dateStr: string | null | undefined): string {
    if (!dateStr) return '-'
    return new Date(dateStr).toLocaleString('zh-CN')
  }

  function formatPercent(used: number, total: number): string {
    if (!total) return '0%'
    return `${Math.min(100, Math.round((used / total) * 100))}%`
  }

  return { formatSize, formatDate, formatDateFull, formatPercent }
}
