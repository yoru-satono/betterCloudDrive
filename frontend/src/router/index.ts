import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { isDesktopRuntime } from '@/config/runtime'

const routes = [
  {
    path: '/',
    component: () => import('@/layouts/PublicLayout.vue'),
    children: [
      { path: '',                 redirect: '/login' },
      { path: 'login',            name: 'Login',          component: () => import('@/pages/auth/LoginPage.vue'),          meta: { guest: true } },
      { path: 'register',         name: 'Register',       component: () => import('@/pages/auth/RegisterPage.vue'),       meta: { guest: true } },
      { path: 'forgot-password',  name: 'ForgotPassword', component: () => import('@/pages/auth/ForgotPasswordPage.vue'), meta: { guest: true } },
      { path: 's/:shareCode',     name: 'PublicShare',    component: () => import('@/pages/PublicSharePage.vue') },
    ]
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '',                redirect: '/files' },
      { path: 'files',           name: 'Files',       component: () => import('@/pages/FileBrowserPage.vue') },
      { path: 'files/:folderId', name: 'Folder',      component: () => import('@/pages/FileBrowserPage.vue') },
      { path: 'recycle-bin',     name: 'RecycleBin',  component: () => import('@/pages/RecycleBinPage.vue') },
      { path: 'shares',          name: 'Shares',      component: () => import('@/pages/SharesPage.vue') },
      { path: 'favorites',       name: 'Favorites',   component: () => import('@/pages/FavoritesPage.vue') },
      { path: 'tags',            name: 'Tags',        component: () => import('@/pages/TagsPage.vue') },
      { path: 'profile',         name: 'Profile',     component: () => import('@/pages/ProfilePage.vue') },
    ]
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      { path: '',       name: 'AdminDashboard', component: () => import('@/pages/admin/AdminDashboard.vue') },
      { path: 'users',  name: 'AdminUsers',     component: () => import('@/pages/admin/AdminUsers.vue') },
      { path: 'users/:userId/files', name: 'AdminUserFiles', component: () => import('@/pages/admin/AdminUserFiles.vue') },
      { path: 'logs',   name: 'AdminLogs',      component: () => import('@/pages/admin/AdminLogs.vue') },
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/files' }
]

const router = createRouter({
  history: isDesktopRuntime() ? createWebHashHistory() : createWebHistory(),
  routes
})

router.beforeEach(async (to, _from, next) => {
  const token = localStorage.getItem('accessToken')
  const isLoggedIn = !!token

  if (to.meta.requiresAuth && !isLoggedIn) return next('/login')

  if (to.meta.requiresAdmin) {
    const { useAuthStore } = await import('@/stores/auth')
    const auth = useAuthStore()
    if (!auth.user) await auth.fetchMe()
    if (!auth.isAdmin) return next('/files')
  }

  if (to.meta.guest && isLoggedIn) return next('/files')

  next()
})

export default router
