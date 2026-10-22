<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getShowDetail } from '@/api/show'
import { goBack } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const show = ref(null)
const loading = ref(false)

const statusText = (status) =>
  ({ 1: '待开售', 2: '售票中', 3: '已售罄', 4: '已结束', 5: '已取消' })[status] || '未知'

const toSeat = (session) => {
  if (session.status === 4 || session.status === 5) {
    ElMessage.warning('该场次已结束或已取消')
    return
  }
  router.push(`/show/${show.value.id}/seat/${session.id}`)
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await getShowDetail(route.params.id)
    show.value = res.data
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="topbar">
      <el-icon class="back" @click="goBack(router, '/index')"><ArrowLeft /></el-icon>
      <span>演出详情</span>
    </div>

    <template v-if="show">
      <div class="head">
        <div class="title">{{ show.name }}</div>
        <div class="sub">{{ show.artist }} · {{ show.durationMinutes }} 分钟</div>
        <div class="venue">{{ show.city }} · {{ show.venueName }}</div>
      </div>

      <div class="desc">{{ show.description }}</div>

      <div class="section-title">选择场次</div>
      <div class="sessions">
        <div v-for="session in show.sessions" :key="session.id" class="session" @click="toSeat(session)">
          <div class="session-left">
            <div class="session-name">{{ session.sessionName }}</div>
            <div class="session-meta">开售 {{ session.saleBeginTime }}</div>
          </div>
          <div class="session-right">
            <el-tag
              size="small"
              :type="session.status === 2 ? 'success' : session.status === 1 ? 'warning' : 'info'"
              >{{ statusText(session.status) }}</el-tag
            >
            <div class="left">余票 {{ session.seatAvailable }} / {{ session.seatTotal }}</div>
          </div>
        </div>
        <el-empty v-if="!show.sessions?.length" description="暂无场次" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f8fa;
  padding-bottom: 30px;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  background: #fff;
  font-weight: 600;
}
.back {
  cursor: pointer;
}
.head {
  padding: 20px;
  background: linear-gradient(135deg, #4b6cb7, #182848);
  color: #fff;
}
.title {
  font-size: 20px;
  font-weight: 700;
}
.sub,
.venue {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.9;
}
.desc {
  margin: 12px 20px 0;
  padding: 14px;
  background: #fff;
  border-radius: 10px;
  font-size: 13px;
  color: #666;
  line-height: 1.7;
}
.section-title {
  margin: 20px 20px 10px;
  font-weight: 600;
}
.sessions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0 20px;
}
.session {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px;
  background: #fff;
  border-radius: 10px;
  cursor: pointer;
}
.session-name {
  font-weight: 600;
}
.session-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}
.session-right {
  text-align: right;
}
.left {
  margin-top: 6px;
  font-size: 12px;
  color: #ff5c39;
}
</style>
