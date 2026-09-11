<template>
  <!-- 左边栏 -->
  <div :class="sidebarOpen ? 'translate-x-0' : '-translate-x-full'"
    class="w-64 bg-[#f9fbff] border-r border-gray-200 fixed left-0 top-0 h-full transition-transform duration-300 ease-in-out z-10 overflow-y-auto">
    <!-- 侧边栏内容区域 -->
    <div class="p-0 h-full flex flex-col">
      <!-- Logo 与应用名称 -->
      <div class="flex items-center justify-center p-4 cursor-pointer" @click="jumpToIndexPage">
        <SvgIcon name="ai-robot-logo" customCss="w-8 h-8 text-gray-700 mr-3" />
        <span class="text-2xl font-bold font-sans tracking-wide text-gray-800">小哈AI机器人</span>
      </div>

      <!-- 开启新对话按钮 -->
      <button @click="jumpToIndexPage"
        class="mx-auto mb-[28px] my-2 px-6 py-2 text-white rounded-xl transition-colors new-chat-btn w-fit cursor-pointer">
        <SvgIcon name="new-chat" customCss="w-6 h-6 mr-1.5 inline text-[#4d6bfe]" />
        开启新对话
      </button>

      <!-- 其他工具入口 -->
      <ul class="px-3 text-gray-600 mb-2">
        <li class="flex items-center py-1 px-2 hover:bg-gray-100 rounded-lg cursor-pointer" @click="jumpToCustomerServiceChatPage">
          <SvgIcon name="customer-service" customCss="w-5 h-5 mr-2 inline mb-0" />
          <span>智能客服</span>
        </li>
      </ul>

      <!-- 分割线 -->
      <div class="h-1 border-b border-gray-200 mx-4"></div>

      <!-- 历史对话区域 -->
      <div class="my-4 px-2 overflow-y-auto overflow-x-hidden flex-1" ref="historyChatContainerRef">
        <div class="space-y-1">
          <!-- 使用 sticky 定位使标题在滚动时保持可见 -->
          <div class="text-xs px-3 py-1 text-gray-500 sticky top-0 bg-[#f9fbff] z-10">历史对话</div>

          <div v-for="(historyChat, index) in historyChats" :key="index" :class="['relative px-3 py-1 rounded-xl cursor-pointer transition-colors flex items-center justify-between',
    selectedChatId === historyChat.uuid ? 'bg-[#e4edfd] text-[#4d6bfe]' : 'hover:bg-[rgb(239,246,255)]']"
            @click="jumpToChatPage(historyChat.uuid)" @mouseenter="showButton = historyChat.uuid"
            @mouseleave="showButton = null">

            <a-tooltip placement="top">
              <!-- Tooltip 提示文字 -->
              <template #title>
                <span>{{ historyChat.summary }}</span>
              </template>

              <p class="text-[14px] overflow-hidden whitespace-nowrap">{{ historyChat.summary }}</p>
            </a-tooltip>

            <!-- 下拉菜单 -->
            <a-dropdown>
              <template #overlay>
                <a-menu @click="handleMenuClick(historyChat.uuid, historyChat.id, historyChat.summary, $event)">
                  <a-menu-item key="rename">
                    <EditOutlined />
                    重命名
                  </a-menu-item>
                  <a-menu-item key="delete" danger>
                    <DeleteOutlined />
                    删除
                  </a-menu-item>
                </a-menu>
              </template>
              <!-- 右边菜单按钮 -->
              <button
                class="z-10 rounded-lg outline-none justify-center items-center bg-white
                            w-6 h-6 flex absolute right-2 top-1/2 transform -translate-y-1/2 transition-all duration-300 hover:bg-gray-50"
                :style="{ opacity: showButton === historyChat.uuid ? 1 : 0 }">
                <EllipsisOutlined class="w-4 h-4 text-gray-500" />
              </button>
            </a-dropdown>



          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 侧边栏切换按钮 -->
  <a-tooltip placement="bottom">
    <!-- Tooltip 提示文字 -->
    <template #title>
      <span>{{ sidebarOpen ? '收缩边栏' : '打开边栏' }}</span>
    </template>

    <button :class="sidebarOpen ? 'left-64' : 'left-0'" @click="toggleSidebar"
      class="fixed top-4 z-20 bg-white border border-gray-200 rounded-r-lg p-2 transition-all duration-300">
      <!-- 图标 -->
      <SvgIcon :name="sidebarOpen ? 'sidebar-open' : 'sidebar-close'"
        :customCss="sidebarOpen ? 'w-6 h-6 text-gray-400' : 'w-7 h-7 text-gray-400'" />
    </button>
  </a-tooltip>

  <!-- 删除对话确认框 -->
  <a-modal v-model:open="deleteChatModelOpen" width="400px" :centered=true title="永久删除对话" ok-text="确认" ok-type="danger" cancel-text="取消" 
    @ok="handleDeleteChatModelOk()">
      <p>删除后，该对话将不可恢复。确认删除吗？</p>
  </a-modal>

  <!-- 重命名对话弹出框 -->
  <a-modal v-model:open="renameChatModelOpen" width="600px" :centered=true title="重命名对话" ok-text="确认" cancel-text="取消"
  @ok="handleRenameChatModelOk()">
              <a-form
                ref="formRef"
                :rules="rules"
                :model="formState"
                :label-col="{ span: 2 }"
                :wrapper-col="{ span: 22 }"
                autocomplete="off"
                >
                    <a-form-item
                      label="摘要"
                      name="summary"
                    >
                      <a-input v-model:value="formState.summary" />
                    </a-form-item>
                </a-form>
  </a-modal>

</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, toRaw } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { EditOutlined, EllipsisOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { useRouter, useRoute } from 'vue-router'
// 导入获取历史对话列表的API
import { findHistoryChatPageList, deleteChat, renameChat } from '@/api/chat'
import { message } from 'ant-design-vue'

const router = useRouter()
const route = useRoute()

// 定义 props, 对外部暴露配置项
const props = defineProps({
  sidebarOpen: { type: Boolean, required: true }, // 左边栏是否展开
})

// 定义emits
const emit = defineEmits(['toggle-sidebar'])

// 历史对话
const historyChats = ref([])

// 切换侧边栏显示/隐藏
const toggleSidebar = () => {
  emit('toggle-sidebar')
}

// 当前显示右侧栏按钮的聊天 ID
const showButton = ref(null)

// 当前选中的聊天 ID
const selectedChatId = ref(null)

// 跳转到首页
const jumpToIndexPage = () => {
  router.push('/')
}

// 跳转智能客服聊天页
const jumpToCustomerServiceChatPage = () => {
  router.push({ name: 'CustomerServiceChatPage'})
}

// 左边栏滚动区域引用
const historyChatContainerRef = ref(null)

onMounted(() => {
  // 查询历史对话
  fetchHistoryChats()

  // 检查当前路由是否有 chatId 参数
  const currentChatId = route.params.chatId
  if (currentChatId) {
    // 设置当前选中的对话 ID
    selectedChatId.value = currentChatId
  }

  // 添加滚动监听事件
  if (historyChatContainerRef.value) {
    historyChatContainerRef.value.addEventListener('scroll', handleScroll)
  }
})

onBeforeUnmount(() => {
  // 移除滚动事件监听
  if (historyChatContainerRef.value) {
    historyChatContainerRef.value.removeEventListener('scroll', handleScroll);
  }
})

const handleScroll = () => {
  if (historyChatContainerRef.value) {
    // 获取滚动参数
    const { scrollTop, scrollHeight, clientHeight } = historyChatContainerRef.value
    // 距离底部距离
    const scrollPosition = scrollHeight - scrollTop - clientHeight

    // 打印滚动过程中的详细日志
    console.log('=== 滚动事件日志 ===')
    console.log('scrollPosition:', scrollPosition)

    // 当滚动到距离底部20px以内时，且有更多数据，且当前没有在加载中时，加载更多
    if (scrollPosition <= 20 && hasMore.value && !isLoadingMore.value) {
      loadMoreHistoryChats()
    }
  }
}

// 加载更多历史对话
const loadMoreHistoryChats = async () => {
  console.log('=== 开始加载更多历史对话 ===')
  console.log('当前页码:', current.value)

  // 双重检查：
  // 1. 如果当前页面已经是最后一页，则不再发送请求
  // 2. 如果已经有请求在进行中，则不再发送请求
  if (!hasMore.value) {
    console.log('=== 没有更多历史对话，不再请求 ===')
    return
  }
  
  if (isLoadingMore.value) {
    console.log('=== 已有加载请求正在进行中，不再发送新请求 ===')
    return
  }
  
  // 设置加载状态为 true，防止并发请求
  isLoadingMore.value = true

  // 计算下一页页码（向下滑动加载更早的历史对话，页码应该增加）
  const nextPageNo = current.value + 1
  console.log('=== 计算下一页页码 ===', nextPageNo)

  // 保存当前页码用于错误恢复
  const currentTemp = current.value
  // 当前需要请求的页码
  current.value = nextPageNo

  try {
    await fetchHistoryChats();
  } catch (error) {
    // 恢复页码
    current.value = currentTemp;
  }
}

// 分页相关状态
// 当前页码（默认第一页）
const current = ref(1)
// 每页展示数据量
const size = ref(20)
// 是否还有下一页数据（默认有）
const hasMore = ref(true)
// 是否正在加载中 (解决并发请求后续页数据问题)
const isLoadingMore = ref(false)

// 获取历史对话列表
const fetchHistoryChats = async () => {
  findHistoryChatPageList(current.value, size.value).then(res => {
    // 无论成功失败，请求完成后都需要重置加载状态
    isLoadingMore.value = false

    if (res.data.success) {
      const data = res.data.data

      // 判断是否还有下一页(总页数 > 当前页)
      hasMore.value = res.data.pages > current.value

      if (data && data.length > 0) {
        // 将新数据添加到历史对话列表末尾
        historyChats.value = [...historyChats.value, ...data]
      }
    }
  }).catch((error) => {
      // 错误处理，重置加载状态
      console.error('加载历史对话失败:', error)
      isLoadingMore.value = false
  })
}

// 跳转历史对话页
const jumpToChatPage = (chatId) => {
  // 更新被选中的对话 ID
  selectedChatId.value = chatId
  router.push({ name: 'ChatPage', params: { chatId } })
}

// 删除对话确认框是否展示
const deleteChatModelOpen = ref(false)
// 被删除的对话 UUID
const deleteChatUUID = ref(null)

// 重命名对话弹出框是否展示
const renameChatModelOpen = ref(false)

// 表单对象
const formState = reactive({
  id: null, // 被重命名对话 ID
  summary: '' // 被重命名对话摘要
})

// 处理菜单点击
const handleMenuClick = (uuid, id, summary, e) => {
  if (e.key === 'delete') { // 删除对话
    console.log('用户点击了删除对话：', uuid)
    // 展示确认框
    deleteChatModelOpen.value = true
    // 保存当前被删除的对话 UUID
    deleteChatUUID.value = uuid
  } else if (e.key === 'rename') { // 重命名对话
    console.log('用户点击了重命名对话：', uuid)
    
    // 展示弹出框
    renameChatModelOpen.value = true
    // 保存当前被重命名对话的 ID
    formState.id = id
    // 保存摘要
    formState.summary = summary

    console.log('表单数据：', formState)
  }
}

// 删除对话确认框确认事件
const handleDeleteChatModelOk = () => {
  deleteChat(deleteChatUUID.value).then((res) => {
      if (res.data.success) {
        message.success('删除成功')
        
        // 从 historyChats 数组中删除对应的对话
        const index = historyChats.value.findIndex(chat => chat.uuid === deleteChatUUID.value);
        if (index !== -1) {
          console.log('找到了，', index)
          historyChats.value.splice(index, 1);
        }
        
        // 跳转首页
        router.push({ name: 'Index'})
      } else {
        // 提示错误信息
        message.error(res.data.message)
      }
  }).finally(
    // 隐藏确认框
    deleteChatModelOpen.value = false
  )
}

// 表单引用
const formRef = ref(null)

// 校验规则
const rules = {
  summary: [
   { required: true, message: '请输入对话摘要', trigger: 'change' }
  ]
}

// 重命名对话弹出框确认事件
const handleRenameChatModelOk = () => {
  formRef.value
    .validate()
    .then(() => { // 校验通过
      renameChat(formState.id, formState.summary).then(res => {
          if (!res.data.success) {
            // 接口返回失败，提示错误消息
            message.error(res.data.message)

            return
          }

          // 提示操作成功
          message.success('操作成功！')
          
          // 更新 historyChats 数组中对应 id 的对话摘要
          const chatIndex = historyChats.value.findIndex(chat => chat.id === formState.id);
          if (chatIndex !== -1) {
            historyChats.value[chatIndex].summary = formState.summary;
          }
          
          // 关闭弹出框
          renameChatModelOpen.value = false
      })
    })
    .catch(error => { // 校验不通过
      console.log('error', error);
    })
}


</script>

<style scoped>
.overflow-y-auto {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
  /* 自定义滚动条颜色 */
}

.new-chat-btn {
  background-color: rgb(219 234 254);
  color: #4d6bfe;
}

.new-chat-btn:hover {
  background-color: #c6dcf8;
}
</style>