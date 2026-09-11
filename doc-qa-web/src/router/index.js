import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'

// 统一在这里声明所有路由
const routes = [
    {
        path: '/', // 路由地址
        name: 'Index', // 命名路由
        component: () => import('@/views/Index.vue'), // 对应组件
        meta: { // meta 信息
            title: '小哈 AI 机器人首页' // 页面标题
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

// ES6 模块导出语句，它用于将 router 对象导出，以便其他文件可以导入和使用这个对象
export default router
