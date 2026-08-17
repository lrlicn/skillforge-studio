/** 将字节数格式化为适合界面扫描的单位。 */
export function formatBytes(value?: number | null): string {
  if (value == null || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const unitIndex = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  const amount = value / 1024 ** unitIndex
  return `${amount >= 10 || unitIndex === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[unitIndex]}`
}

/** 使用中文区域格式展示服务端时间，空值统一显示占位符。 */
export function formatDate(value?: string): string {
  if (!value) return '--'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
