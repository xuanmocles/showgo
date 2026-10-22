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

// 注册与登录走同一入口：手机号首次验证通过即完成注册
const register = async () => {
  await formRef.value.validate()
  const res = await userLogin(form.value)
  userStore.setToken(res.data)
  ElMessage.success('注册成功，欢迎来到星幕票务')
  router.push('/index')
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
      <el-icon class="back" @click="router.push('/login')"><ArrowLeft /></el-icon>
      <span class="title">注册星幕票务</span>
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
      <el-button class="submit" type="primary" @click="register">立即注册</el-button>
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
.form {
  padding: 30px 24px 0;
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
</style>
