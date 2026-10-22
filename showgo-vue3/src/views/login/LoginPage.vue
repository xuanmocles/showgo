<script setup>
import { ref } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores'
import router from '@/router'
import { userGetCode, userLogin } from '@/api/user'

const userStore = useUserStore()
const formRef = ref()
const disabled = ref(false)
const codeBtnMsg = ref('获取验证码')
const form = ref({ phone: '', code: '' })

const goBack = () => router.push('/index')

const sendCode = async () => {
  try {
    await formRef.value.validateField('phone')
    await userGetCode(form.value.phone)
    ElMessage.success('验证码已发送，开发环境请查看后端日志')
    disabled.value = true
    let time = 60
    codeBtnMsg.value = time + 's 后重发'
    const timer = setInterval(() => {
      time--
      codeBtnMsg.value = time + 's 后重发'
      if (time <= 0) {
        clearInterval(timer)
        codeBtnMsg.value = '获取验证码'
        disabled.value = false
      }
    }, 1000)
  } catch (e) {
    disabled.value = false
  }
}

const login = async () => {
  try {
    await formRef.value.validate()
    const res = await userLogin(form.value)
    userStore.setToken(res.data)
    ElMessage.success('登录成功')
    router.push('/index')
  } catch (error) {
    console.error('登录失败', error)
  }
}

const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { len: 6, message: '验证码为 6 位', trigger: 'blur' }
  ]
}
</script>

<template>
  <div class="page">
    <div class="header">
      <el-icon class="back" @click="goBack"><ArrowLeft /></el-icon>
      <span class="title">手机号登录</span>
    </div>
    <div class="brand">
      <div class="logo">星幕票务</div>
      <div class="slogan">未注册的手机号将自动创建账号</div>
    </div>
    <el-form ref="formRef" :model="form" :rules="rules" class="form">
      <el-form-item prop="phone">
        <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" />
      </el-form-item>
      <el-form-item prop="code">
        <div class="code-row">
          <el-input v-model="form.code" placeholder="请输入验证码" maxlength="6" />
          <el-button :disabled="disabled" @click="sendCode">{{ codeBtnMsg }}</el-button>
        </div>
      </el-form-item>
      <el-button class="submit" type="primary" @click="login">登录 / 注册</el-button>
      <div class="tip" @click="router.push('/index')">先随便逛逛 →</div>
    </el-form>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #fff;
}
.header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
}
.back {
  cursor: pointer;
  font-size: 18px;
}
.title {
  font-weight: 600;
}
.brand {
  padding: 40px 24px 24px;
}
.logo {
  font-size: 28px;
  font-weight: 700;
  color: #ff5c39;
  letter-spacing: 2px;
}
.slogan {
  margin-top: 8px;
  font-size: 13px;
  color: #999;
}
.form {
  padding: 0 24px;
}
.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.submit {
  width: 100%;
  margin-top: 10px;
}
.tip {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
  color: #999;
  cursor: pointer;
}
</style>
