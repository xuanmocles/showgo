/**
 * 安全返回：
 * - 有应用内历史时走 router.back()，保留用户来时的页面状态（比如首页的筛选条件）
 * - 直接打开链接 / 刷新后没有历史时，回退到指定页面，避免退出站外或原地打转
 *
 * 注意不要用 router.push 做"返回"，那会新增历史记录，
 * 两个页面互相 push 时会形成"详情 → 选座 → 详情"的回退死循环。
 */
export function goBack(router, fallback = '/index') {
  const state = typeof window !== 'undefined' ? window.history.state : null
  if (state && state.back) {
    router.back()
    return
  }
  router.replace(fallback)
}
