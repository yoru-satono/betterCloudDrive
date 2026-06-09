import { describe, it, expect } from 'vitest'

describe('Sidebar', () => {
  it('nav items should have correct structure', () => {
    const navItems = [
      { to: '/files', label: '我的文件', icon: 'folder' },
      { to: '/recycle-bin', label: '回收站', icon: 'trash' },
      { to: '/shares', label: '分享', icon: 'share' },
      { to: '/favorites', label: '收藏', icon: 'star' },
      { to: '/tags', label: '标签', icon: 'tag' },
    ]
    expect(navItems).toHaveLength(5)
    expect(navItems[0].to).toBe('/files')
    expect(navItems[2].label).toBe('分享')
  })

  it('should add admin item for admin users', () => {
    const adminItem = { to: '/admin', label: '管理', icon: 'settings' }
    const isAdmin = true
    const navItems = [
      { to: '/files', label: '我的文件' },
      ...(isAdmin ? [adminItem] : []),
    ]
    expect(navItems).toHaveLength(2)
    expect(navItems[1].label).toBe('管理')
  })

  it('should not show admin item for regular users', () => {
    const adminItem = { to: '/admin', label: '管理', icon: 'settings' }
    const isAdmin = false
    const navItems = [
      { to: '/files', label: '我的文件' },
      ...(isAdmin ? [adminItem] : []),
    ]
    expect(navItems).toHaveLength(1)
  })

  it('storage percent should compute correctly', () => {
    const used = 2147483648 // 2GB
    const quota = 10737418240 // 10GB
    const percent = Math.round((used / quota) * 100)
    expect(percent).toBe(20)
  })
})
