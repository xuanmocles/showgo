import request from '@/utils/request'

// 场次座位图：状态来自 Redis BitField
export const getSeatMap = (sessionId) => request.get('/seat/map/' + sessionId)

// 连座推荐
export const recommendSeats = (data) => request.post('/seat/recommend', data)

// 锁座：返回 lockToken，15 分钟内需完成下单
export const lockSeats = (data) => request.post('/seat/lock', data)

// 座位变更实时推送（SSE）
export const seatStreamUrl = (sessionId) => `/api/seat/stream/${sessionId}`

// 候补排队
export const joinWaitlist = (data) => request.post('/seat/waitlist/join', data)

export const cancelWaitlist = (sessionId) =>
  request.post('/seat/waitlist/cancel', { sessionId })

export const getWaitlistStatus = (sessionId) => request.get('/seat/waitlist/status/' + sessionId)
