import axios from "@/axios";

// 查询 Markdown 问答文件分页列表
export function findMarkdownFilePageList(current, size, fileName, startDate, endDate) {
    return axios.post("/customer-service/file/list", {current, size, fileName, startDate, endDate})
}

// 上传 Markdown 问答文件
// export function uploadMarkdownFile(form) {
//     return axios.post("/customer-service/md/upload", form)
// }

// 上传问答文件分片
export function uploadFileChunk(form, timeout = 30000) {
    return axios.post("/customer-service/file/upload-chunk", form, {
        timeout: timeout // 自定义超时时间，默认30s
    })
}

// 合并问答文件分片 (合并文件可能耗时较长，超时时间也要设置的长一点)
export function mergeFileChunk(fileMd5, timeout = 30000) {
    return axios.post("/customer-service/file/merge-chunk", { fileMd5 }, {
        timeout: timeout // 自定义超时时间，默认30s
    })
}

// 检查文件是否存在（秒传）
export function checkFile(fileMd5) {
    return axios.post("/customer-service/file/check", { fileMd5 })
}

// 删除 Markdown 问答文件
export function deleteMarkdownFile(id) {
    return axios.post("/customer-service/file/delete", { id })
}

// 修改 Markdown 问答文件
export function updateMarkdownFile(record) {
    return axios.post("/customer-service/file/update", record)
}
