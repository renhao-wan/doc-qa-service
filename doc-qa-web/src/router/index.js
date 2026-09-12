import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'

// 统一在这里声明所有路由
const routes = [
    {
        path: '/login', // 登录页
        name: 'Login',
        component: () => import('@/views/LoginPage.vue'),
        meta: {
            title: '登录',
            public: true // 免登录访问
        }
    },
    {
        path: '/', // 路由地址
        name: 'Index', // 命名路由
        component: () => import('@/views/Index.vue'), // 对应组件
        meta: { // meta 信息
            title: 'Doc QA 首页' // 页面标题
        }
    },
    {
        path: '/chat/:chatId', // 路由地址
        name: 'ChatPage', // 命名路由
        component: () => import('@/views/ChatPage.vue'), // 对应组件
        meta: { // meta 信息
            title: '对话聊天页' // 页面标题
        }
    },
    {
        path: '/customer-service/chat', // 路由地址
        name: 'CustomerServiceChatPage', // 命名路由
        component: () => import('@/views/CustomerServiceChatPage.vue'), // 对应组件
        meta: { // meta 信息
            title: '智能客服聊天页' // 页面标题
        }
    }
]

// 创建路由
const router = createRouter({
    // 指定路由策略，hash 模式指的是 URL 的路径是通过 hash 符号（#）进行标识
    history: createWebHashHistory(),
    // routes: routes 的缩写
    routes,
})

// 全局前置守卫：没登录一律赶到登录页
router.beforeEach((to) => {
    // ⚠️ 必须在守卫函数内调用，不能提到模块顶层——模块加载时 Pinia 尚未安装
    const authStore = useAuthStore()

    // 未登录且目标不是公开页 → 去登录页
    if (!to.meta.public && !authStore.isLoggedIn) {
        return { name: 'Login' }
    }

    // 已登录还想去登录页 → 回首页
    if (to.name === 'Login' && authStore.isLoggedIn) {
        return { name: 'Index' }
    }

    return true
})

// ES6 模块导出语句，它用于将 router 对象导出，以便其他文件可以导入和使用这个对象
export default router
