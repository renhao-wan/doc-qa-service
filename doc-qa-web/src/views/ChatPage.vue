<template>
  <Layout>
    <!-- 主内容区域 -->
    <template #main-content>
      <div class="h-screen flex flex-col overflow-y-auto" ref="chatContainer">
        <!-- 聊天记录区域 -->
        <div class="flex-1 max-w-3xl mx-auto pb-24 pt-4 px-4 w-full">
          <!-- 遍历聊天记录 -->
          <template v-for="(chat, index) in chatList" :key="index">
            <!-- 用户提问消息（靠右） -->
            <div v-if="chat.role === 'user'" class="flex justify-end mb-4">
              <div class="quesiton-container">
                <p>{{ chat.content }}</p>
              </div>
            </div>

            <!-- 大模型回复消息（靠左） -->
            <div v-else class="flex mb-4">
              <!-- 头像 -->
              <div class="flex-shrink-0 mr-3">
                <div class="w-8 h-8 rounded-full flex items-center justify-center border border-gray-200">
                  <SvgIcon name="deepseek-logo" customCss="w-5 h-5"></SvgIcon>
                </div>
              </div>
              <!-- 回复的内容 -->
              <div class="p-1 mb-2 max-w-[90%]">
                <LoadingDots v-if="chat.loading" />
                <!-- 推理过程展示 -->
                <div v-if="chat.reasoning" class="reasoning-container mb-3">
                  <div class="reasoning-header">
                    <SvgIcon name="deepseek-logo" customCss="w-4 h-4 text-gray-600"></SvgIcon>
                    <span class="reasoning-title">推理过程</span>
                  </div>
                  <div class="reasoning-content">{{ chat.reasoning }}</div>
                </div>
                <!-- 正常回复内容 -->
                <StreamMarkdownRender :content="chat.content" />
              </div>
            </div>
          </template>
        </div>

        <!-- 提问输入框 -->
        <ChatInputBox v-model="message" containerClass="sticky max-w-3xl mx-auto bg-white bottom-8 left-0 w-full"
          @sendMessage="sendMessage" />
      </div>
    </template>
  </Layout>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'
import StreamMarkdownRender from '@/components/StreamMarkdownRender.vue'
import LoadingDots from '@/components/LoadingDots.vue'
import Layout from '@/layouts/Layout.vue'
import ChatInputBox from '@/components/ChatInputBox.vue'
import { useRoute } from 'vue-router'
// 导入Pinia store
import { useChatStore } from '@/stores/chatStore'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { findChatMessagePageList } from '@/api/chat'

// 获取 chat store
const chatStore = useChatStore()

console.log('首页传递过来的消息: ', history.state?.firstMessage)

const route = useRoute()

// 输入的消息
const message = ref(history.state?.firstMessage || '')

// 聊天容器引用
const chatContainer = ref(null)

// 聊天记录
const chatList = ref([])

// 监听路由参数变化
watch(() => route.params.chatId, (newChatId) => {
  if (newChatId) {
    // 更新对话 ID
    chatId.value = newChatId
    // 清空历史消息
    chatList.value = []
    // 设置页码为第一页
    current.value = 1
    // 加载历史消息
    loadHistoryMessages()
  }
})

onMounted(() => {
  // 加载历史消息
  loadHistoryMessages()

  // 为聊天容器添加滚动事件监听器
  if (chatContainer.value) {
    // 添加监听事件
    chatContainer.value.addEventListener('scroll', handleScroll)
  }

  const firstMessage = history.state?.firstMessage
  // 检查跳转路由时，是否有初始消息
  if (firstMessage) {
    message.value = firstMessage
    // 发送消息
    sendMessage({
      selectedModel: chatStore.selectedModel,
      isNetworkSearch: chatStore.isNetworkSearchSelected
    })
    
    // 发送消息后清除 history.state 中的 firstMessage，防止刷新页面时重复发送
    if (history.replaceState) {
      const newState = { ...history.state }
      delete newState.firstMessage
      history.replaceState(newState, document.title)
    }
  }

    // 滚动到最底部
    scrollToBottom()
})

// 分页相关状态
// 当前页码（默认第一页）
const current = ref(1)
// 每页展示数据量
const size = ref(3)
// 是否还有下一页数据（默认有）
const hasMore = ref(true)
// 是否正在加载中 (解决并发请求后续页数据问题)
const isLoadingMore = ref(false)

// 加载历史对话消息
const loadHistoryMessages = async () => {
  findChatMessagePageList(current.value, size.value, chatId.value).then((res) => {
      // 无论成功失败，请求完成后都需要重置加载状态
      isLoadingMore.value = false
      
      if (res.data.success) {
        const historyMessages = res.data.data
        // 判断是否还有下一页
        hasMore.value = res.data.pages > current.value

        if (historyMessages && historyMessages.length > 0) {
          // 将历史消息添加到聊天列表顶部
          chatList.value = [...historyMessages, ...chatList.value]
        }

        // 确保加载历史消息时自动滚动到底部（仅第一页）
        if (current.value === 1) {
          // 滚动到最底部
          scrollToBottom()
        }
      }
  }).catch((error) => {
      // 错误处理，重置加载状态
      console.error('加载历史消息失败:', error)
      isLoadingMore.value = false
  })
}

// SSE 连接
let eventSource = null;

// 对话 ID
const chatId = ref(route.params.chatId || null)

// 发送消息
const sendMessage = async (payload) => {
  // 校验发送的消息不能为空
  if (!message.value.trim()) return

  console.log('选中的模型:', payload.selectedModel)
  console.log('是否联网:', payload.isNetworkSearch)

  // 将用户发送的消息添加到 chatList 聊天列表中
  const userMessage = message.value.trim()
  chatList.value.push({ role: 'user', content: userMessage })

  // 点击发送按钮后，清空输入框
  message.value = ''

  // 添加一个占位的回复消息
  chatList.value.push({ role: 'assistant', content: '', reasoning: '', loading: true})

  try {
    // 构建请求体
    const requestBody = {
      message: userMessage,
      chatId: chatId.value,
      modelName: payload.selectedModel?.name,
      networkSearch: payload.isNetworkSearch
    }

    // 响应的回答
    let responseText = ''
    // 推理过程文本
    let reasoningText = ''
    // 获取最后一条消息
    const lastMessage = chatList.value[chatList.value.length - 1]

    const controller = new AbortController()
    const signal = controller.signal

    fetchEventSource('http://localhost:8080/chat/completion', {
      method: 'POST',
      signal: signal,
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestBody),
      onmessage(msg) {
        if (msg.event === '') {
          // 收到第一条数据后设置 loading 为 false
          if (lastMessage.loading) {
              lastMessage.loading = false;
          }
          // 解析 JSON
          let parseJson = JSON.parse(msg.data)
          
          // 处理推理过程
          if (parseJson.reasoning) {
            reasoningText += parseJson.reasoning
            lastMessage.reasoning = reasoningText
          }
          
          // 处理正常回复内容
          if (parseJson.v) {
            responseText += parseJson.v
            lastMessage.content = responseText
          }

          // 滚动到底部
          scrollToBottom()
        }
        else if (msg.event === 'close') {
          console.log('-- sse close')
          controller.abort();
        }
      },
      onerror(err) {
        throw err;    // 必须 throw 才能停止 
      }
    })
  } catch (error) {
    console.error('发送消息错误: ', error)
    // 提示用户 “请求出错”
    const lastMessage = chatList.value[chatList.value.length - 1]
    lastMessage.content = '抱歉，请求出错了，请稍后重试。'
    lastMessage.loading = false
    // 滚动到底部
    scrollToBottom()
  }
}

// 滚动到底部
const scrollToBottom = async () => {
  await nextTick() // 等待 Vue.js 完成 DOM 更新
  if (chatContainer.value) { // 若容器存在
    // 将容器的滚动条位置设置到最底部
    const container = chatContainer.value;
    container.scrollTop = container.scrollHeight;
  }
}

// 关闭 SSE 连接
const closeSSE = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

// 组件卸载时自动关闭连接
onBeforeUnmount(() => {
  closeSSE()

  // 移除滚动事件监听
  if (chatContainer.value) {
    chatContainer.value.removeEventListener('scroll', handleScroll);
  }
})

// 监听滚动事件
const handleScroll = () => {
  if (chatContainer.value) {
    // 到滚动区域顶部的距离
    const scrollTop = chatContainer.value.scrollTop
    // 滚动区域完整高度
    const scrollHeight = chatContainer.value.scrollHeight
    
    // 打印滚动过程中的详细日志
    console.log('=== 滚动事件日志 ===')
    console.log('scrollTop:', scrollTop)
    console.log('scrollHeight:', scrollHeight)
    console.log('isLoadingMore:', isLoadingMore.value)
    console.log('hasMore:', hasMore.value)

    // 当用户向上滚动到顶部附近，且有更多数据，且当前没有在加载中时，才加载更多历史消息
    if (scrollTop < 50 && hasMore.value && !isLoadingMore.value) {
      console.log('=== 触发加载更多历史消息 ===');
      loadMoreHistoryMessages();
    }
  }
}

// 加载更多历史消息
const loadMoreHistoryMessages = () => {
  console.log('=== 开始加载更多历史消息 ===')
  console.log('当前页码:', current.value)
  
  // 双重检查：
  // 1. 如果当前页面已经是最后一页，则不再发送请求
  // 2. 如果已经有请求在进行中，则不再发送请求
  if (!hasMore.value) {
    console.log('=== 没有更多历史消息，不再请求 ===')
    return
  }
  
  if (isLoadingMore.value) {
    console.log('=== 已有加载请求正在进行中，不再发送新请求 ===')
    return
  }
  
  // 设置加载状态为true，防止并发请求
  isLoadingMore.value = true
  
  // 计算下一页页码（向上滑动加载更早的历史消息，页码应该增加）
  const nextPageNo = current.value + 1
  console.log('=== 计算下一页页码 ===', nextPageNo)
  
  // 保存当前页码用于错误恢复
  const currentTemp = current.value
  // 当前需要请求的页码
  current.value = nextPageNo
  
  try {
    loadHistoryMessages()
  } catch (error) {
    // 恢复页码
    current.value = currentTemp
  }
}
</script>

<style scoped>
.quesiton-container {
  font-size: 16px;
  line-height: 28px;
  color: #262626;
  padding: calc((44px - 28px) / 2) 20px;
  box-sizing: border-box;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: #eff6ff;
  border-radius: 14px;
  max-width: calc(100% - 48px);
  position: relative;
}

/* 聊天内容区域样式 */
.overflow-y-auto {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
  /* 自定义滚动条颜色 */
}

/* 推理过程容器样式 */
.reasoning-container {
  background: linear-gradient(135deg, #f8f9fa 0%, #f1f3f5 100%);
  border: 1px solid #e9ecef;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.reasoning-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #dee2e6;
}

.reasoning-title {
  font-size: 13px;
  font-weight: 600;
  color: #495057;
  letter-spacing: 0.3px;
}

.reasoning-content {
  font-size: 14px;
  line-height: 1.6;
  color: #6c757d;
  white-space: pre-wrap;
  word-break: break-word;
  font-style: italic;
}
</style>