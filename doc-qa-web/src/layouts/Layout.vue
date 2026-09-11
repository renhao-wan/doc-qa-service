<template>
    <div class="h-screen flex overflow-hidden overflow-x-hidden">
        <!-- 左边栏 -->
        <Sidebar
            :sidebarOpen="sidebarOpen"
            @toggle-sidebar="toggleSidebar"
            />
        
        <!-- 主内容区域 -->
        <div :class="sidebarOpen ? 'ml-64' : 'ml-0'" class="flex flex-col flex-1 transition-all duration-300">
            <!-- 插槽 -->
            <slot name="main-content"></slot>
        </div>

        <!-- 吸附底部的提示文字 -->
        <div v-if="showFooterText"
            :class="sidebarOpen ? 'ml-64' : 'ml-0'"
            class="bg-white fixed bottom-0 left-0 right-0 flex items-center justify-center text-xs text-gray-400 transition-all duration-300 py-2">
          内容由 AI 生成，请仔细甄别
        </div>

    </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import Sidebar from '@/components/Sidebar.vue'
// 导入获取历史对话列表的API
import { findHistoryChatPageList } from '@/api/chat'
import { useRouter } from 'vue-router'

const router = useRouter()

// 定义props
const props = defineProps({
  showFooterText: { // 是否展示 footer 提示文字
    type: Boolean,
    default: true
  }
});

// 左边栏状态
const sidebarOpen = ref(true)

// 切换侧边栏显示/隐藏
const toggleSidebar = () => {
  sidebarOpen.value = !sidebarOpen.value;
}

</script>