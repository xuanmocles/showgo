<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import FootBar from '@/components/FootBar.vue'
import { queryMyOrders, payTicketOrder, cancelTicketOrder, refundTicketOrder } from '@/api/order'

const router = useRouter()
const orders = ref([])
const loading = ref(false)
const status = ref(null)

const STATUS = {
  1: { text: '待支付', type: 'warning' },
  2: { text: '已支付', type: 'success' },
  3: { text: '已出票', type: 'success' },
  4: { text: '已取消', type: 'info' },
  5: { text: '已退款', type: 'info' },
  6: { text: '已超时', type: 'danger' }
}

const load = async () => {
  loading.value = true
  try {
    const res = await queryMyOrders({ status: status.value, page: 1, pageSize: 20 })
    orders.value = res.data || []
  } finally {
    loading.value = false
  }
}

const pay = async (order) => {
  await payTicketOrder({ orderId: order.id, payType: 1 })
  ElMessage.success('支付成功，已出票')
  load()
}

const cancel = async (order) => {
  await cancelTicketOrder({ orderId: order.id, reason: '用户取消' })
  ElMessage.success('订单已取消，座位已释放')
  load()
}

const refund = async (order) => {
  await refundTicketOrder({ orderId: order.id, reason: '用户退票' })
  ElMessage.success('退票成功，座位已回流')
  load()
}

const money = (fen) => `¥${((fen || 0) / 100).toFixed(2)}`

const filters = [
  { label: '全部', value: null },
  { label: '待支付', value: 1 },
  { label: '已出票', value: 3 },
  { label: '已退款', value: 5 }
]

const pick = (value) => {
  status.value = value
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="topbar">我的订单</div>
    <div class="filters">
      <span
        v-for="item in filters"
        :key="String(item.value)"
        class="chip"
        :class="{ on: status === item.value }"
        @click="pick(item.value)"
        >{{ item.label }}</span
      >
    </div>

    <div v-loading="loading" class="list">
      <div v-for="order in orders" :key="order.id" class="order">
        <div class="order-head">
          <span class="no">订单号 {{ order.orderNo }}</span>
          <el-tag size="small" :type="STATUS[order.status]?.type">{{ STATUS[order.status]?.text }}</el-tag>
        </div>
        <div class="seats">{{ order.seatCodes }}</div>
        <div class="row">
          <span class="count">{{ order.ticketCount }} 张</span>
          <span class="amount">{{ money(order.amount) }}</span>
        </div>
        <div class="row time">
          <span>下单 {{ order.createTime }}</span>
          <span v-if="order.status === 1">请在 {{ order.expireTime }} 前完成支付</span>
        </div>
        <div class="actions">
          <el-button v-if="order.status === 1" size="small" type="primary" @click="pay(order)">
            立即支付
          </el-button>
          <el-button v-if="order.status === 1" size="small" @click="cancel(order)">取消订单</el-button>
          <el-button
            v-if="order.status === 2 || order.status === 3"
            size="small"
            type="danger"
            plain
            @click="refund(order)"
          >
            申请退票
          </el-button>
          <el-button v-if="order.status === 3" size="small" type="success" plain @click="router.push('/tickets')">
            查看票夹
          </el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !orders.length" description="还没有订单" />
    </div>

    <FootBar />
    <div class="foot-space" />
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f8fa;
}
.topbar {
  padding: 16px 20px;
  background: #fff;
  font-weight: 600;
}
.filters {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
}
.chip {
  padding: 4px 12px;
  border-radius: 14px;
  background: #fff;
  font-size: 12px;
  color: #666;
  cursor: pointer;
}
.chip.on {
  background: #fff1eb;
  color: #ff5c39;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 16px;
}
.order {
  background: #fff;
  border-radius: 12px;
  padding: 14px;
}
.order-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #999;
}
.seats {
  margin: 10px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #666;
}
.time {
  margin-top: 6px;
  font-size: 12px;
  color: #aaa;
}
.amount {
  color: #ff5c39;
  font-weight: 700;
  font-size: 16px;
}
.actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.foot-space {
  height: 64px;
}
</style>
