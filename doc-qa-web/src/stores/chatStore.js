import { defineStore } from 'pinia'
import { ref } from 'vue'
import { findAvailableModels } from '@/api/chat'

// 模型名 → 展示信息。
// ⚠️ 这是**展示元数据**，不是可用模型清单——「哪些模型能被调用」由后端 chat.allowed-models
//    单点决定，经 GET /chat/models 下发。拆成两处的理由是归属不同：icon 是本地 svg symbol 名，
//    后端根本不知道它；而「后端接受哪些模型名」是安全边界，不能由前端说了算。
// 后端新增模型而这里没补映射时，下拉框退化成默认图标 + 空描述，**但功能正常**。
const MODEL_META = {
  'deepseek-v3': { icon: 'deepseek-logo', description: '更流畅' },
  'deepseek-r1': { icon: 'deepseek-logo', description: '深度思考' },
}

// 未录入 MODEL_META 的模型用它兜底
const DEFAULT_META = { icon: 'ai-robot-logo', description: '' }

// 创建store
export const useChatStore = defineStore('chat', () => {

  // 可用模型列表。
  // ⚠️ 不从本地持久化恢复（见文件末尾 persist.pick）：它是后端白名单的投影，
  //    缓存下来会让「后端下架了某模型，前端下拉框还列着它」一直存在，且刷新修不好。
  const models = ref([])

  // 当前选中的模型。拉取完成前是 null，用到它的地方要自己兜底
  const selectedModel = ref(null)

  // 联网搜索状态，默认为false
  const isNetworkSearchSelected = ref(false)

  // 知识库页「联网兜底」状态，默认为false。
  // 与对话页的联网搜索分开存：两者语义不同（前者是直接联网检索替换知识库检索，
  // 后者是知识库答不出时才由模型自主联网），共用一个字段会导致两边互相点亮。
  const isKbNetworkFallbackSelected = ref(false)

  // 拉取可用模型列表。
  // 只拉一次：对话页与知识库页共用本 store，而重复调用会和「恢复选中项」的逻辑打架
  // （后到的那次会把用户刚选的模型覆盖掉）。
  let loadPromise = null
  function loadModels() {
    if (!loadPromise) {
      loadPromise = findAvailableModels()
        .then((res) => {
          const names = res.data?.data ?? []

          // 每次都用新对象重建，不复用旧数组——旧对象上残留的 selected 会和新状态打架
          const list = names.map(name => ({
            name,
            ...(MODEL_META[name] ?? DEFAULT_META),
            selected: false,
          }))

          // 恢复上次选中的模型。它可能已被后端移出白名单（那是下架，不是缓存脏），
          // 这时必须回退到第一个：留着一个后端已经拒掉的选中项，用户每问一句被拒一次。
          const restored = list.find(m => m.name === selectedModel.value?.name) ?? list[0]
          if (restored) {
            restored.selected = true
          }

          models.value = list
          selectedModel.value = restored ?? null
        })
        .catch((e) => {
          // ⚠️ 失败的 promise 不能留在缓存里，否则本次会话内再也等不到重试机会
          loadPromise = null
          console.error('拉取可用模型列表失败: ', e)
        })
    }
    return loadPromise
  }

  // 更新选中的模型
  function updateSelectedModel(model) {
    // 将所有模型的 selected 置为 false
    models.value.forEach(m => {
      m.selected = false;
    });

    // 将选中模型的 selected 置为 true
    model.selected = true;

    // 更新当前选中的模型
    selectedModel.value = model;
  }

  // 更新联网搜索状态
  function updateNetworkSearchStatus(status) {
    isNetworkSearchSelected.value = status
  }

  // 更新知识库页联网兜底状态
  function updateKbNetworkFallbackStatus(status) {
    isKbNetworkFallbackSelected.value = status
  }

  // 对外暴露相关变量与方法
  return { models, selectedModel, isNetworkSearchSelected, isKbNetworkFallbackSelected, loadModels, updateSelectedModel, updateNetworkSearchStatus, updateKbNetworkFallbackStatus }
},
{
  // 开启持久化
  // ⚠️ 刻意用 pick 而不是 true：models 是后端白名单的投影，一并缓存会让「后端下架了某模型，
  //    前端还列着它」跨刷新一直存在（持久化会把它覆盖回来）。只存「选中的是哪个」和两个开关。
  persist: {
    pick: ['selectedModel', 'isNetworkSearchSelected', 'isKbNetworkFallbackSelected'],
  },
})
