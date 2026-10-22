import request from '@/utils/request'

// 演出列表：支持分类、城市、关键词、分页
export const queryShows = (params) => request.get('/show/list', { params })

// 演出详情：含全部可售场次
export const getShowDetail = (id) => request.get('/show/' + id)

// 热销场次榜（Redis ZSet）
export const queryHotSessions = (limit = 8) =>
  request.get('/show/hot/sessions', { params: { limit } })

// 演出分类
export const queryCategories = () => request.get('/show/categories')

// 城市列表
export const queryCities = () => request.get('/venue/cities')

// 附近场馆（Redis GEO）
export const queryNearbyVenues = (x, y, page = 1) =>
  request.get('/venue/nearby', { params: { x, y, page } })
