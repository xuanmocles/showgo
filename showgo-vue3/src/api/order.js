import request from '@/utils/request'

// 领取选座访问令牌（限流 + 一次性令牌）
export const issueAccessToken = (sessionId) => request.get('/ticket-order/access-token/' + sessionId)

// 用锁座凭证下单
export const createTicketOrder = (data, accessToken) =>
  request.post('/ticket-order/create', data, { params: { accessToken } })

// 支付并出票
export const payTicketOrder = (data) => request.post('/ticket-order/pay', data)

// 取消未支付订单
export const cancelTicketOrder = (data) => request.post('/ticket-order/cancel', data)

// 退票
export const refundTicketOrder = (data) => request.post('/ticket-order/refund', data)

export const getTicketOrder = (orderId) => request.get('/ticket-order/' + orderId)

export const queryMyOrders = (data) => request.post('/ticket-order/my', data)

// 手动触发某场次对账
export const reconcileSession = (sessionId) => request.post('/ticket-order/reconcile/' + sessionId)
