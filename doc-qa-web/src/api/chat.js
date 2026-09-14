import axios from "@/axios";

// 新建对话
export function newChat(message) {
    return axios.post("/chat/new", {message})
}

// 查询可用的模型列表。返回的是模型名数组，展示用的图标与描述由前端按名字补（见 chatStore）
export function findAvailableModels() {
    return axios.get("/chat/models")
}

// 查询历史消息分页列表
export function findChatMessagePageList(current, size, chatId) {
    return axios.post("/chat/message/list", {current, size, chatId})
}

// 查询历史对话列表
export function findHistoryChatPageList(current, size) {
    return axios.post("/chat/list", {current, size})
}

// 删除对话
export function deleteChat(uuid) {
    return axios.post("/chat/delete", {uuid})
}

// 重命名对话
export function renameChat(id, summary) {
    return axios.post("/chat/summary/rename", {id, summary})
}