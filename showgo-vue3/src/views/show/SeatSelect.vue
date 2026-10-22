<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { getSeatMap, recommendSeats, lockSeats, seatStreamUrl, joinWaitlist, getWaitlistStatus } from '@/api/seat'
import { issueAccessToken, createTicketOrder, payTicketOrder } from '@/api/order'
import { goBack } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.sessionId)
const showId = route.params.id

const seatMap = ref(null)
const loading = ref(false)
const selected = ref([])
const recommendCount = ref(2)
const lockInfo = ref(null)
const accessToken = ref('')
const waitlist = ref(null)
let eventSource = null

const seatCodeOf = (area, row, col) =>
  `${area.areaCode}-${String(row + 1).padStart(2, '0')}-${String(col + 1).padStart(2, '0')}`

const seatIndex = (area, row, col) => area.startIndex + row * area.seatPerRow + col

const selectedCodes = computed(() => {
  if (!seatMap.value) return []
  return selected.value.map((item) => item.code)
})

const totalAmount = computed(() => {
  if (!seatMap.value) return 0
  return selected.value.reduce((sum, item) => sum + (item.price || 0), 0)
})

const amountText = computed(() => `¥${(totalAmount.value / 100).toFixed(2)}`)

const seatClass = (status, picked) => ({
  seat: true,
  available: status === 0 && !picked,
  picked,
  locked: status === 1,
  sold: status === 2,
  disabled: status === 3
})

const pick = (area, row, col, status) => {
  if (status !== 0) {
    ElMessage.warning(status === 1 ? '该座位已被他人锁定' : '该座位不可选')
    return
  }
  const index = seatIndex(area, row, col)
  const existing = selected.value.findIndex((item) => item.index === index)
  if (existing >= 0) {
    selected.value.splice(existing, 1)
    return
  }
  if (selected.value.length >= 6) {
    ElMessage.warning('单笔订单最多选择 6 个座位')
    return
  }
  if (selected.value.length > 0) {
    const first = selected.value[0]
    if (first.areaId !== area.areaId || first.row !== row) {
      ElMessage.warning('请选择同一分区同一排的连续座位')
      return
    }
  }
  selected.value.push({
    index,
    areaId: area.areaId,
    row,
    col,
    price: area.price,
    code: seatCodeOf(area, row, col)
  })
  selected.value.sort((a, b) => a.index - b.index)
  for (let i = 1; i < selected.value.length; i++) {
    if (selected.value[i].index !== selected.value[i - 1].index + 1) {
      ElMessage.warning('座位必须连续')
      selected.value = selected.value.filter((item) => item.index !== index)
      return
    }
  }
}

const load = async () => {
  loading.value = true
  try {
    const res = await getSeatMap(sessionId)
    seatMap.value = res.data
  } finally {
    loading.value = false
  }
}

const recommend = async () => {
  try {
    const res = await recommendSeats({ sessionId, count: recommendCount.value })
    const data = res.data
    const area = seatMap.value.areas.find((item) => item.areaId === data.areaId)
    selected.value = data.seatIndexes.map((index, i) => {
      const offset = index - area.startIndex
      const row = Math.floor(offset / area.seatPerRow)
      const col = offset % area.seatPerRow
      return { index, areaId: area.areaId, row, col, price: area.price, code: data.seatCodes[i] }
    })
    ElMessage.success(`已为你推荐 ${data.areaName} 的连续座位`)
  } catch (e) {
    ElMessage.warning('当前没有满足条件的连续空位')
  }
}

const lock = async () => {
  if (!selected.value.length) {
    ElMessage.warning('请先选择座位')
    return
  }
  try {
    const res = await lockSeats({ sessionId, seatIndexes: selected.value.map((item) => item.index) })
    lockInfo.value = res.data
    ElMessage.success('锁座成功，请在 15 分钟内完成下单')
    await load()
  } catch (e) {
    await load()
  }
}

const submit = async () => {
  if (!lockInfo.value) {
    ElMessage.warning('请先锁座')
    return
  }
  try {
    if (!accessToken.value) {
      const tokenRes = await issueAccessToken(sessionId)
      accessToken.value = tokenRes.data
    }
    const res = await createTicketOrder(
      { sessionId, lockToken: lockInfo.value.lockToken },
      accessToken.value
    )
    const order = res.data
    await payTicketOrder({ orderId: order.id, payType: 1 })
    ElMessage.success('支付成功，已出票')
    // 支付完成后用 replace：避免用户返回时又回到已经失效的选座页
    router.replace('/tickets')
  } catch (e) {
    accessToken.value = ''
  }
}

const subscribe = () => {
  eventSource = new EventSource(seatStreamUrl(sessionId))
  eventSource.addEventListener('seat-change', (event) => {
    const data = JSON.parse(event.data)
    if (!seatMap.value) return
    seatMap.value.areas.forEach((area) => {
      data.seatIndexes.forEach((index) => {
        const offset = index - area.startIndex
        if (offset >= 0 && offset < area.seatCount) {
          const row = Math.floor(offset / area.seatPerRow)
          const col = offset % area.seatPerRow
          area.seats[row][col] = data.status
        }
      })
    })
  })
}

const joinQueue = async () => {
  const res = await joinWaitlist({ sessionId, expectCount: recommendCount.value })
  waitlist.value = res.data
  ElMessage.success('已加入候补，有票时会自动为你锁座并通知')
}

onMounted(async () => {
  await load()
  subscribe()
  try {
    const tokenRes = await issueAccessToken(sessionId)
    accessToken.value = tokenRes.data
  } catch (e) {
    accessToken.value = ''
  }
  try {
    const res = await getWaitlistStatus(sessionId)
    waitlist.value = res.data
  } catch (e) {
    waitlist.value = null
  }
})

onUnmounted(() => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
})
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="topbar">
      <el-icon class="back" @click="goBack(router, '/show/' + showId)"><ArrowLeft /></el-icon>
      <div class="head-info">
        <div class="name">{{ seatMap?.showName || '选座' }}</div>
        <div class="meta">
          {{ seatMap?.venueName }} · {{ seatMap?.sessionName }}
        </div>
      </div>
      <div class="available">余票 {{ seatMap?.seatAvailable ?? '-' }}</div>
    </div>

    <div class="toolbar">
      <el-select v-model="recommendCount" size="small" style="width: 92px">
        <el-option v-for="n in 6" :key="n" :label="n + ' 连座'" :value="n" />
      </el-select>
      <el-button size="small" :icon="Refresh" @click="recommend">智能推荐连座</el-button>
      <el-button v-if="waitlist?.status === 1" size="small" type="warning" plain>
        候补中（前面 {{ waitlist.aheadCount }} 人）
      </el-button>
      <el-button v-else size="small" plain @click="joinQueue">售罄登记候补</el-button>
    </div>

    <div class="stage">舞 台 / STAGE</div>

    <div v-for="area in seatMap?.areas" :key="area.areaId" class="area">
      <div class="area-head">
        <span class="area-name" :style="{ color: area.color }">{{ area.areaName }}</span>
        <span class="area-price">¥{{ (area.price / 100).toFixed(0) }}</span>
      </div>
      <div class="rows">
        <div v-for="(rowSeats, row) in area.seats" :key="row" class="row">
          <span class="row-no">{{ row + 1 }}</span>
          <span
            v-for="(status, col) in rowSeats"
            :key="col"
            :class="
              seatClass(
                status,
                selected.some((item) => item.index === seatIndex(area, row, col))
              )
            "
            :title="seatCodeOf(area, row, col)"
            @click="pick(area, row, col, status)"
          />
        </div>
      </div>
    </div>

    <div class="legend">
      <span><i class="dot available" />可售</span>
      <span><i class="dot picked" />已选</span>
      <span><i class="dot locked" />锁定中</span>
      <span><i class="dot sold" />已售</span>
    </div>

    <div class="bottom-bar">
      <div class="selected">
        <div class="codes">{{ selectedCodes.length ? selectedCodes.join('、') : '请选择座位' }}</div>
        <div class="amount">{{ amountText }}</div>
      </div>
      <el-button v-if="!lockInfo" type="primary" :disabled="!selectedCodes.length" @click="lock">
        锁定座位
      </el-button>
      <el-button v-else type="danger" @click="submit">提交订单并支付</el-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f8fa;
  padding-bottom: 96px;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
}
.back {
  cursor: pointer;
  font-size: 18px;
}
.head-info {
  flex: 1;
}
.name {
  font-weight: 600;
}
.meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
.available {
  font-size: 12px;
  color: #ff5c39;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.stage {
  margin: 6px 16px 16px;
  height: 34px;
  line-height: 34px;
  text-align: center;
  border-radius: 6px;
  color: #fff;
  font-size: 13px;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #4b6cb7, #182848);
}
.area {
  margin: 0 12px 18px;
  background: #fff;
  border-radius: 10px;
  padding: 12px 8px;
  overflow-x: auto;
}
.area-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 8px;
  padding: 0 4px;
}
.area-price {
  color: #ff5c39;
}
.row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}
.row-no {
  width: 18px;
  font-size: 11px;
  color: #bbb;
  text-align: right;
}
.seat {
  width: 16px;
  height: 16px;
  border-radius: 3px;
  display: inline-block;
  cursor: pointer;
}
.seat.available {
  background: #e8f1ff;
  border: 1px solid #a8c8ff;
}
.seat.picked {
  background: #ff5c39;
  border: 1px solid #ff5c39;
}
.seat.locked {
  background: #ffe58f;
  border: 1px solid #ffd666;
  cursor: not-allowed;
}
.seat.sold {
  background: #d9d9d9;
  border: 1px solid #d9d9d9;
  cursor: not-allowed;
}
.seat.disabled {
  background: transparent;
  border: 1px dashed #eee;
  cursor: not-allowed;
}
.legend {
  display: flex;
  gap: 16px;
  padding: 0 16px 12px;
  font-size: 12px;
  color: #666;
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
}
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 76px;
  padding: 10px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-top: 1px solid #f0f0f0;
}
.selected {
  flex: 1;
  margin-right: 12px;
  overflow: hidden;
}
.codes {
  font-size: 12px;
  color: #666;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.amount {
  font-size: 18px;
  color: #ff5c39;
  font-weight: 700;
}
</style>
