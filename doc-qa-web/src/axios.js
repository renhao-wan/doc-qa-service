import axios from "axios";
import router from "@/router";
import { useAuthStore } from "@/stores/authStore";

// 创建 Axios 实例
const instance = axios.create({
    baseURL: "/api", // 你的 API 基础 URL
    timeout: 7000, // 请求超时时间
})

// 鉴权类错误码：登录状态失效
const AUTH_ERROR_CODES = ['30001']

// 请求拦截：带上 token
instance.interceptors.request.use((config) => {
    // ⚠️ 必须在函数内调用 useAuthStore()，不能提到模块顶层——
    //    模块加载时 Pinia 还没被 app.use() 安装，顶层调用会直接抛错
    const authStore = useAuthStore()
    if (authStore.token) {
        config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
})

// 响应拦截：识别鉴权失败
instance.interceptors.response.use(
    (response) => {
        const res = response.data

        // ⚠️ 本项目的业务错误是 **HTTP 200 + success:false** 的形态，
        //    HTTP 状态码这一层全是 200。所以必须检查**响应体**，
        //    只看 response.status 永远等不到鉴权失败。
        if (res && res.success === false && AUTH_ERROR_CODES.includes(res.errorCode)) {
            const authStore = useAuthStore()
            authStore.clear()

            // 登录页自身不跳转，否则「登录接口返回 30001」会陷入死循环
            if (router.currentRoute.value.path !== '/login') {
                router.push('/login')
            }
        }

        return response
    },
    (error) => Promise.reject(error)
)

// 暴露出去
export default instance;
