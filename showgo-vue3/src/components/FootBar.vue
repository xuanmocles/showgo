<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, Tickets, List, User, Star } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const items = [
  { path: '/index', label: '首页', icon: HomeFilled },
  { path: '/tickets', label: '票夹', icon: Tickets },
  { path: '/orders', label: '订单', icon: List },
  { path: '/profile', label: '我的', icon: User }
]

const active = computed(() => route.path)
const go = (path) => router.push(path)
</script>

<template>
  <div class="foot-bar">
    <div
      v-for="item in items"
      :key="item.path"
      class="foot-item"
      :class="{ active: active === item.path }"
      @click="go(item.path)"
    >
      <el-icon><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
    </div>
    <el-icon class="brand"><Star /></el-icon>
  </div>
</template>

<style scoped>
.foot-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-around;
  background: #fff;
  border-top: 1px solid #f0f0f0;
  z-index: 100;
}
.foot-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: #999;
  cursor: pointer;
}
.foot-item.active {
  color: #ff5c39;
}
.foot-item .el-icon {
  font-size: 20px;
}
.brand {
  display: none;
}
</style>
