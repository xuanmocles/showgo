import axios from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'showgo_admin_token'

export const getAdminToken = () => localStorage.getItem(TOKEN_KEY) || ''
export const setAdminToken = (token) => localStorage.setItem(TOKEN_KEY, token)
export const clearAdminToken = () => localStorage.removeItem(TOKEN_KEY)

// 管理端使用独立的 token，不与 C 端用户登录态混在一起
const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.request.use((config) => {
  const token = getAdminToken()
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.success === false) {
      // 参数校验失败时后端返回 data 为字段错误数组
      const detail = Array.isArray(body.data)
        ? body.data.map((item) => item.message).join('；')
        : body.errorMsg
      ElMessage.error(detail || '操作失败')
      return Promise.reject(new Error(detail || '操作失败'))
    }
    return body
  },
  (error) => {
    if (error.response?.status === 401) {
      clearAdminToken()
      ElMessage.error('管理端登录已过期，请重新登录')
      if (location.pathname !== '/admin/login') {
        location.href = '/admin/login'
      }
      return Promise.reject(error)
    }
    ElMessage.error(error.response?.data?.message || '请求失败')
    return Promise.reject(error)
  }
)

export const adminLogin = (data) => http.post('/admin/login', data)
export const adminLogout = () => http.post('/admin/logout')
export const adminVenues = () => http.get('/admin/venues')
export const adminShows = (params) => http.get('/admin/shows', { params })
export const adminSaveShow = (data) => http.post('/admin/shows', data)
export const adminShowStatus = (id, status) => http.put(`/admin/shows/${id}/status`, { status })
export const adminDeleteShow = (id) => http.delete(`/admin/shows/${id}`)
export const adminSessions = (showId) => http.get(`/admin/shows/${showId}/sessions`)
export const adminCreateSession = (data) => http.post('/admin/sessions', data)
export const adminSessionStatus = (id, status) =>
  http.put(`/admin/sessions/${id}/status`, { status })
export const adminRecomputeStock = (id) => http.post(`/admin/sessions/${id}/stock/recompute`)
