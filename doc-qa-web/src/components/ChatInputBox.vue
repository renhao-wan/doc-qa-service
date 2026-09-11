<template>
  <div :class="containerClass">

    <div class="bg-gray-100 rounded-3xl px-4 py-3 mx-4 border border-gray-200 flex flex-col">
        <textarea :placeholder="props.placeholder"
            class="bg-transparent border-none outline-none w-full text-sm resize-none min-h-[24px]" rows="2"
            v-model="userMessage"
            @input="autoResize"
            @keydown.enter="handleEnter"
            ref="textareaRef"></textarea>

        
        <!-- 下方容器 -->
        <div class="flex mt-3">
            <!-- 左侧 -->
            <div class="flex gap-2 relative" ref="leftContainerRef">
                <!-- 大模型下拉框 -->
                <div v-show="props.showModelDropdown" class="border border-gray-300 px-2 py-1 rounded-3xl flex items-center justify-center hover:bg-gray-200 cursor-pointer"
                ref="selectRef"
                @click="toggleModelDropdown">
                    <SvgIcon :name="currSelectedModel.icon" customCss="w-5 h-5 mr-1.5" />
                    <span class="text-gray-800 text-xs">{{ currSelectedModel.name }}</span>
                    <SvgIcon name="down-arrow" customCss="w-5 h-5 ml-1 text-gray-800 transform transition-transform duration-300"
                    :class="isModelDropdownOpen ? 'rotate-180' : ''" />
                </div>

                <!-- 下拉框菜单 -->
                <div v-if="isModelDropdownOpen" 
                ref="dropdownRef"
                :class="['absolute', 'left-0', 'w-48', 'bg-white', 'rounded-lg', 'shadow-lg', 'border', 'border-gray-200', 'z-10', 'overflow-hidden', dropdownPosition]"
                >
                    <div v-for="model in models" :key="model.id" 
                    class="px-3 py-2 hover:bg-gray-100 cursor-pointer flex items-center justify-between"
                    @click="selectModel(model)">
                        <div class="flex items-center">
                            <SvgIcon :name="model.icon" customCss="w-5 h-5 mr-2" />
                            <div class="flex flex-col text-xs">
                                <div class="text-gray-800">{{ model.name }}</div>
                                <div class="text-gray-500">{{ model.description }}</div>
                            </div>
                        </div>
                        <SvgIcon v-if="model.selected" name="check" customCss="w-3 h-3 text-gray-600" />
                    </div>
                </div>
                
                <!-- 联网搜索 -->
                <div v-show="props.showNetworkSearch" class="ml-3 border px-2 py-1 rounded-3xl flex items-center justify-center cursor-pointer"
                :class="isNetworkSearchSelected ? 'border-[#ceddee] bg-[#DBEAFE] hover:bg-[#C3DAF8]' : 'border-gray-300 hover:bg-gray-200'" 
                @click="toggleNetworkSearch">
                    <SvgIcon name="network" customCss="w-5 h-5 mr-1.5" :class="isNetworkSearchSelected ? 'text-[#4D6BFE]' : 'text-gray-500'" />
                    <span class="text-xs mr-1" :class="isNetworkSearchSelected ? 'text-[#4D6BFE]' : 'text-gray-800'">联网搜索</span>
                </div>
            </div>
            <div class="grow"></div>

            <a-tooltip placement="top">
                <!-- Tooltip 提示文字 -->
                <template #title>
                  <span>请输入你的问题</span>
                </template>

                <!-- 发送按钮 -->
                <button class="flex items-center justify-center bg-[#4d6bfe] rounded-full w-8 h-8 border border-[#4d6bfe] hover:bg-[#3b5bef] transition-colors
                        disabled:opacity-50
                        disabled:cursor-not-allowed"
                        @click="handleSendMessage"
                        :disabled="!userMessage.trim()"
                        >
                    <SvgIcon name="up-arrow" customCss="w-5 h-5 text-white"></SvgIcon>
                </button>
            </a-tooltip>
        </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { message } from 'ant-design-vue'
// 导入Pinia store
import { useChatStore } from '@/stores/chatStore'

// 获取 chat store
const chatStore = useChatStore()

// 接收父组件传递的属性
const props = defineProps({
  // textarea 中用户输入的用户消息
  modelValue: {
    type: String,
    required: true
  },
  containerClass: { // 自定义根容器样式
    type: String,
    default: ''
  },
  placeholder: { // 设置 textarea placeholder 提示
    type: String,
    default: '给小哈 AI 机器人发送消息'
  },
  showModelDropdown: { // 展示模型下拉选择 select
    type: Boolean,
    default: true
  },
  showNetworkSearch: { // 展示联网搜索
    type: Boolean,
    default: true
  },
})

// 定义 emits
const emit = defineEmits(['update:modelValue', 'sendMessage'])

// 计算属性，用于 v-model 的双向绑定
const userMessage = computed({
  get() {
    return props.modelValue;
  },
  set(value) {
    emit('update:modelValue', value);
  }
})

// 模型列表
const models = computed(() => chatStore.models)

// 下拉菜单状态
const isModelDropdownOpen = ref(false)
// 下拉框容器引用
const selectRef = ref(null)


// 下拉菜单容器引用
const leftContainerRef = ref(null);

// 下拉菜单引用
const dropdownRef = ref(null)
// 下拉菜单位置
const dropdownPosition = ref('top-8') // 默认下方

// 下拉菜单显示/隐藏
const toggleModelDropdown = (event) => {
  isModelDropdownOpen.value = !isModelDropdownOpen.value

  // 如果下拉菜单显示，则计算位置
  if (isModelDropdownOpen.value) {
    nextTick(() => {
      if (leftContainerRef.value && dropdownRef.value) {
        const buttonRect = leftContainerRef.value.getBoundingClientRect();
        const dropdownHeight = dropdownRef.value.offsetHeight;
        
        // 检查下方空间是否足够
        if (window.innerHeight - buttonRect.bottom < dropdownHeight) {
          // 下方空间不足，显示在上方
          dropdownPosition.value = 'bottom-10';
        } else {
          // 下方空间足够，显示在下方
          dropdownPosition.value = 'top-8';
        }
      }
    })
  }
}

// 点击外部区域关闭下拉菜单
const handleClickOutside = (event) => {
  if (selectRef.value && !selectRef.value.contains(event.target)) {
    isModelDropdownOpen.value = false
  }
}

// 挂载时添加事件监听器
onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

// 卸载时移除事件监听器
onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

// 当前选择的模型，使用 store 中的选中模型
const currSelectedModel = computed(() => chatStore.selectedModel)

// 选择模型
const selectModel = (model) => {
  // 更新 store 中的选中模型
  chatStore.updateSelectedModel(model);
  
  // 关闭下拉菜单
  isModelDropdownOpen.value = false;
}

// 是否启用联网搜索，使用 store 中的状态
const isNetworkSearchSelected = computed(() => chatStore.isNetworkSearchSelected)

// 切换联网搜索选中状态
const toggleNetworkSearch = () => {
  // 更新 store 中的联网搜索状态
  chatStore.updateNetworkSearchStatus(!chatStore.isNetworkSearchSelected)
}

// 处理发送消息
const handleSendMessage = () => {
  // 若消息为空
  if (!userMessage.value.trim()) {
    message.warning('消息不能为空');
    return
  }

  emit('sendMessage', {
    selectedModel: chatStore.selectedModel,
    isNetworkSearch: chatStore.isNetworkSearchSelected
  });
  // 清空输入框
  userMessage.value = '';
}

// 文本域引用
const textareaRef = ref(null)

// 自动调整文本域高度
const autoResize = () => {
  const textarea = textareaRef.value;
  if (textarea) {
    // 重置高度以获取正确的滚动高度
    textarea.style.height = 'auto'
    
    // 计算新高度，但最大不超过 300px
    const newHeight = Math.min(textarea.scrollHeight, 300);
    textarea.style.height = newHeight + 'px';
    
    // 如果内容超出 300px，则启用滚动
    textarea.style.overflowY = textarea.scrollHeight > 300 ? 'auto' : 'hidden';
  }
}

// 监听 textarea 回车事件
const handleEnter = (event) => {
  console.log('回车键被按下', event.target.value)
  // 主动调用发送消息方法
  handleSendMessage()
}

</script>
