# doc-qa-web

企业私有知识库问答服务的 Web 客户端（演示用）。

后端服务见 [../doc-qa-api](../doc-qa-api)，提供 REST 接口与 SSE 流式问答接口。**项目级文档见 [../docs/README.md](../docs/README.md)**。

## 技术栈

Vue 3 + Vite 6 + Pinia + Vue Router + Ant Design Vue + Tailwind CSS v4

## 开发

```sh
npm install
npm run dev      # 开发服务器
npm run build    # 生产构建
npm run preview  # 预览构建产物
```

无 lint / test 配置（`package.json` 的 scripts 只有 dev、build、preview）。

### 接口地址

所有接口——**包括两个 SSE 流式接口**——都走**相对路径 `/api/...`**，由 `vite.config.js` 的 `server.proxy` 转发到 `http://localhost:8080` 并去掉 `/api` 前缀。

⚠️ **不要硬编码 `http://localhost:8080`**。后端地址只存在于 `vite.config.js` 的 proxy 配置里，换端口只改一处。

**SSE 为什么走 proxy 而不是跨域直连**：加上 `Authorization` 头之后，跨域直连会先发 `OPTIONS` 预检，而**预检请求不携带 token**，必然被后端的认证链拒掉。走 proxy 后同源，后端也不需要配 CORS。

## 目录说明

- `src/api/` — 后端接口封装
- `src/views/` — 页面
  - `LoginPage.vue` — 登录页（`meta.public: true`，免登录访问）
  - `Index.vue` — 首页
  - `ChatPage.vue` — 通用对话页（`/chat/:chatId`）
  - `KnowledgeBaseChatPage.vue` — 知识库问答页（`/knowledge-base/chat`）
- `src/components/` — 通用组件，含流式 Markdown 渲染与 SVG 图标封装
- `src/stores/` — Pinia 状态，**均持久化到 localStorage**
  - `authStore.js` — token 与用户信息
  - `chatStore.js` — 选中的模型、联网搜索开关等对话偏好
- `src/router/index.js` — 路由表（hash 模式）与全局前置守卫：未登录一律跳登录页

## 两个约定

**SVG 图标**：由 `vite-plugin-svg-icons` 扫描 `src/assets/icons/`，symbolId 格式为 `icon-[dir]-[name]`。新增图标直接放 SVG 文件即可，无需注册。

**Ant Design Vue 按需引入**：由 `unplugin-vue-components` 自动完成，组件无需手动 import；`importStyle: false`，因此样式依赖 Tailwind 与 `src/assets/main.css`。
