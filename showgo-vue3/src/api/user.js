import request from '@/utils/request'

// 发送登录验证码（示例项目验证码直接打在后端日志里）
export const userGetCode = (phone) => request.post('/user/code', null, { params: { phone } })

// 验证码登录，手机号首次登录自动注册
export const userLogin = (data) => request.post('/user/login', data)

export const userLogout = () => request.post('/user/logout')

export const getUser = () => request.get('/user/me')

export const getUserInfo = () => request.get('/user/info')

export const updateUserInfo = (data) => request.put('/user/info', data)
