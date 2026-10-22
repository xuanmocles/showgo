import request from '@/utils/request'

// 我的票夹
export const queryMyTickets = (params) => request.get('/ticket/my', { params })

// 入场核销
export const checkInTicket = (ticketId) => request.post('/ticket/check-in/' + ticketId)
