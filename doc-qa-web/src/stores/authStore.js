import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 认证状态：token 与用户基本信息。
// 沿用项目已有的 pinia-plugin-persistedstate（与 chatStore 一致），
// 默认落 localStorage，刷新页面不掉登录态。
export const useAuthStore = defineStore('auth', () => {

  // JWT，请求时作为 Authorization: Bearer 发送
  const token = ref('')

  const username = ref('')
  const nickname = ref('')

  const isLoggedIn = computed(() => !!token.value)

  // 登录成功后写入
  function setLoginInfo(data) {
    token.value = data.token
    username.value = data.username
    nickname.value = data.nickname
  }

  // 退出登录 / token 失效时清空
  function clear() {
    token.value = ''
    username.value = ''
    nickname.value = ''
  }

  return { token, username, nickname, isLoggedIn, setLoginInfo, clear }
},
{
  // 开启持久化
  persist: true,
})
