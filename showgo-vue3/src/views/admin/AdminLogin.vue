<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { adminLogin, setAdminToken } from '@/api/admin'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = ref({ username: 'admin', password: 'admin123' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const submit = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const res = await adminLogin(form.value)
    setAdminToken(res.data)
    ElMessage.success('登录成功')
    router.replace('/admin/shows')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="brand">星幕票务 · 管理端</div>
      <div class="tip">演出与场次维护</div>
      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button class="submit" type="primary" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
      <div class="hint">演示账号：admin / admin123（可用环境变量覆盖）</div>
      <div class="back" @click="router.push('/index')">← 返回用户端</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2b47, #0f1626);
}
.card {
  width: 360px;
  padding: 32px 28px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
}
.brand {
  font-size: 20px;
  font-weight: 700;
  color: #1f2b47;
}
.tip {
  margin: 6px 0 24px;
  font-size: 13px;
  color: #999;
}
.submit {
  width: 100%;
}
.hint {
  margin-top: 14px;
  font-size: 12px;
  color: #aaa;
}
.back {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: #999;
  cursor: pointer;
}
</style>
