<script setup>
import { onMounted, ref } from 'vue'
import FootBar from '@/components/FootBar.vue'
import { queryMyTickets, checkInTicket } from '@/api/ticket'

const tickets = ref([])
const loading = ref(false)

const STATUS = {
  1: { text: '有效', type: 'success' },
  2: { text: '已退票', type: 'info' },
  3: { text: '已入场', type: 'warning' }
}

const load = async () => {
  loading.value = true
  try {
    const res = await queryMyTickets({})
    tickets.value = res.data || []
  } finally {
    loading.value = false
  }
}

const checkIn = async (ticket) => {
  await checkInTicket(ticket.id)
  ElMessage.success('核销成功，祝观演愉快')
  load()
}

const money = (fen) => `¥${((fen || 0) / 100).toFixed(2)}`

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="topbar">我的票夹</div>
    <div v-loading="loading" class="list">
      <div v-for="ticket in tickets" :key="ticket.id" class="ticket">
        <div class="left">
          <div class="seat">{{ ticket.seatCode }}</div>
          <div class="meta">{{ ticket.areaName }} · 场次 {{ ticket.sessionId }}</div>
          <div class="meta">{{ money(ticket.price) }}</div>
        </div>
        <div class="right">
          <el-tag size="small" :type="STATUS[ticket.status]?.type">
            {{ STATUS[ticket.status]?.text }}
          </el-tag>
          <el-button
            v-if="ticket.status === 1"
            size="small"
            type="primary"
            plain
            @click="checkIn(ticket)"
          >
            入场核销
          </el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !tickets.length" description="还没有票，去挑一场演出吧" />
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
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}
.ticket {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px;
  background: #fff;
  border-radius: 12px;
  border-left: 4px solid #ff5c39;
}
.seat {
  font-size: 16px;
  font-weight: 700;
}
.meta {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}
.right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}
.foot-space {
  height: 64px;
}
</style>
