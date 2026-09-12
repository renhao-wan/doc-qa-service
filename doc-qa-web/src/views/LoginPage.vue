<template>
  <div class="h-screen flex items-center justify-center bg-[#f9fbff]">
    <div class="w-[380px] bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
      <!-- Logo 与应用名称 -->
      <div class="flex flex-col items-center mb-8">
        <SvgIcon name="ai-robot-logo" customCss="w-12 h-12 text-gray-700 mb-3" />
        <span class="text-2xl font-bold font-sans tracking-wide text-gray-800">Doc QA</span>
        <span class="text-sm text-gray-400 mt-1">企业私有知识库问答服务</span>
      </div>

      <!-- 登录表单 -->
      <a-form :model="formState" layout="vertical" @finish="handleLogin">
        <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="formState.username" size="large" placeholder="请输入用户名" />
        </a-form-item>

        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="formState.password" size="large" placeholder="请输入密码" />
        </a-form-item>

        <a-button type="primary" html-type="submit" size="large" block :loading="loading">
          登录
        </a-button>
      </a-form>

      <!-- 演示账号提示：便于面试现场直接演示 -->
      <div class="mt-6 text-xs text-gray-400 text-center leading-5">
        演示账号：<span class="text-gray-500">demo / demo123</span>
        <br />
        另一个账号：<span class="text-gray-500">demo2 / demo123</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const formState = reactive({
  username: '',
  password: '',
})

const handleLogin = async () => {
  // 防止回车与按钮点击同时触发
  if (loading.value) {
    return
  }

  loading.value = true
  try {
    const res = await login(formState.username, formState.password)

    // 业务失败（用户名或密码错误）走的是 HTTP 200 + success:false
    if (!res.data.success) {
      message.error(res.data.message)
      return
    }

    authStore.setLoginInfo(res.data.data)
    router.push('/')
  } catch (error) {
    console.error('登录失败: ', error)
    message.error('登录失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>
