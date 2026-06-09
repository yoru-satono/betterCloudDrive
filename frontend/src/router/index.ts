import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  // Public layout routes (login, register, forgot-password, share access)
  {
    path: '/',
    component: () => import('@/layouts/PublicLayout.vue'),
    children: [
      { path: 'login', name: 'Login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
      { path: 'register', name: 'Register', component: () => import('@/pages/RegisterPage.vue'), meta: { public: true } },
      { path: 'forgot-password', name: 'ForgotPassword', component: () => import('@/pages/ForgotPasswordPage.vue'), meta: { public: true } },
      { path: 's/:shareCode', name: 'PublicShare', component: () => import('@/pages/PublicSharePage.vue'), meta: { public: true } },
    ]
  },
  // Main layout routes (authenticated)
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/files' },
      { path: 'files', name: 'Files', component: () => import('@/pages/FileBrowserPage.vue') },
      { path: 'files/:folderId', name: 'Folder', component: () => import('@/pages/FileBrowserPage.vue') },
      { path: 'recycle-bin', name: 'RecycleBin', component: () => import('@/pages/RecycleBinPage.vue') },
      { path: 'shares', name: 'Shares', component: () => import('@/pages/SharesPage.vue') },
      { path: 'favorites', name: 'Favorites', component: () => import('@/pages/FavoritesPage.vue') },
      { path: 'tags', name: 'Tags', component: () => import('@/pages/TagsPage.vue') },
      { path: 'admin', name: 'Admin', component: () => import('@/pages/AdminPage.vue'), meta: { admin: true } },
      { path: 'profile', name: 'Profile', component: () => import('@/pages/ProfilePage.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('accessToken')
  if (!to.meta.public && !token) {
    next('/login')
  } else if (to.meta.public && token && (to.name === 'Login' || to.name === 'Register')) {
    next('/files')
  } else {
    next()
  }
})

export default router
