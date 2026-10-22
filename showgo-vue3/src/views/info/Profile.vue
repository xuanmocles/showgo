<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import FootBar from '@/components/FootBar.vue'
import { useUserStore } from '@/stores'
import { getUser, getUserInfo, updateUserInfo } from '@/api/user'

const router = useRouter()
const userStore = useUserStore()
const user = ref(null)
const info = ref({ city: '', introduce: '', gender: 0 })
const saving = ref(false)

const load = async () => {
  const [userRes, infoRes] = await Promise.all([getUser(), getUserInfo()])
  user.value = userRes.data
  info.value = infoRes.data || { city: '', introduce: '', gender: 0 }
}

const save = async () => {
  saving.value = true
  try {
    await updateUserInfo(info.value)
    ElMessage.success('资料已更新')
  } finally {
    saving.value = false
  }
}

const logout = () => {
  userStore.setToken('')
  userStore.resetUserInfo()
  router.push('/login')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="topbar">个人中心</div>
    <div class="card user-card">
      <div class="avatar">{{ user?.nickName?.slice(0, 1) || '星' }}</div>
      <div class="user-info">
        <div class="nick">{{ user?.nickName || '未登录' }}</div>
        <div class="level">会员等级 Lv.{{ info.level || 1 }} · 积分 {{ info.credits || 0 }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">常驻城市</div>
      <el-input v-model="info.city" placeholder="填写城市，方便推荐同城演出" />
      <div class="card-title">个人介绍</div>
      <el-input v-model="info.introduce" type="textarea" :rows="3" placeholder="说说你的观演偏好" />
      <el-button class="save" type="primary" :loading="saving" @click="save">保存资料</el-button>
    </div>

    <div class="card">
      <el-button class="block-btn" @click="router.push('/orders')">我的订单</el-button>
      <el-button class="block-btn" @click="router.push('/tickets')">我的票夹</el-button>
      <el-button class="block-btn" type="danger" plain @click="logout">退出登录</el-button>
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
.card {
  margin: 14px 16px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
}
.user-card {
  display: flex;
  align-items: center;
  gap: 14px;
}
.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff7a45, #ff4d4f);
  color: #fff;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.nick {
  font-size: 17px;
  font-weight: 700;
}
.level {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}
.card-title {
  font-size: 13px;
  color: #666;
  margin: 10px 0 6px;
}
.save {
  margin-top: 14px;
  width: 100%;
}
.block-btn {
  width: 100%;
  margin: 6px 0 0;
}
.foot-space {
  height: 64px;
}
</style>
