import axios from "@/axios";

// 登录
export function login(username, password) {
    return axios.post("/auth/login", {username, password})
}
