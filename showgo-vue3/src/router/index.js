import { createRouter, createWebHistory } from 'vue-router'

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
    }
  ]
})

export default router
