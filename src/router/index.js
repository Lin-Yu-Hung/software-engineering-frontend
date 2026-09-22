import { createRouter, createWebHistory } from 'vue-router'
import AdminDashboard from '../views/admin/AdminDashboard.vue'
import ClientHome from '../views/client/ClientHome.vue'

const routes = [
  {
    path: '/',
    name: 'ClientHome',
    component: ClientHome,
    meta: { title: '前台預約 - 羽球場館線上預約系統' },
  },
  {
    path: '/admin',
    name: 'AdminDashboard',
    component: AdminDashboard,
    meta: { title: '後台管理看板 - 羽球場館線上預約系統' },
  },
  {
    path: '/backoffice',
    redirect: '/admin',
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router

