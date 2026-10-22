import { createRouter, createWebHistory } from 'vue-router'
import { getAdminToken } from '@/api/admin'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/index' },
    {
      path: '/index',
      name: 'indexPage',
      component: () => import('@/views/index.vue')
    },
    {
      path: '/login',
      name: 'LoginPage',
      component: () => import('@/views/login/LoginPage.vue')
    },
    {
      path: '/register',
      name: 'RegisterPage',
      component: () => import('@/views/register/RegisterPage.vue')
    },
    {
      path: '/show/:id',
      name: 'ShowDetail',
      component: () => import('@/views/show/ShowDetail.vue')
    },
    {
      path: '/show/:id/seat/:sessionId',
      name: 'SeatSelect',
      component: () => import('@/views/show/SeatSelect.vue')
    },
    {
      path: '/orders',
      name: 'MyOrders',
      component: () => import('@/views/order/MyOrders.vue')
    },
    {
      path: '/tickets',
      name: 'MyTickets',
      component: () => import('@/views/ticket/MyTickets.vue')
    },
    {
      path: '/profile',
      name: 'Profile',
      component: () => import('@/views/info/Profile.vue')
    },
    {
      path: '/admin/login',
      name: 'AdminLogin',
      component: () => import('@/views/admin/AdminLogin.vue')
    },
    {
      path: '/admin',
      redirect: '/admin/shows'
    },
    {
      path: '/admin/shows',
      name: 'AdminShows',
      component: () => import('@/views/admin/AdminShows.vue'),
      meta: { admin: true }
    },
    {
      path: '/admin/shows/:showId/sessions',
      name: 'AdminSessions',
      component: () => import('@/views/admin/AdminSessions.vue'),
      meta: { admin: true }
    }
  ]
})

// 管理端路由守卫：没有管理端 token 直接去登录页
router.beforeEach((to) => {
  if (to.meta?.admin && !getAdminToken()) {
    return { path: '/admin/login' }
  }
})

export default router
