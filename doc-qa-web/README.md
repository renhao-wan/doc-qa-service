# doc-qa-web

企业私有知识库问答服务的 Web 客户端（演示用）。

后端服务见 [../doc-qa-api](../doc-qa-api)，提供 REST 接口与 SSE 流式问答接口。

## 技术栈

Vue 3 + Vite 6 + Pinia + Vue Router + Ant Design Vue + Tailwind CSS v4

## 开发

```sh
npm install
npm run dev      # 开发服务器，/api 代理到 http://localhost:8080
npm run build    # 生产构建
npm run preview  # 预览构建产物
```

> 注意：流式问答接口使用 `@microsoft/fetch-event-source` 直连 `http://localhost:8080`，
> 不经过 Vite 的 `/api` 代理。换后端端口或部署时需同步修改 `src/views/` 下的调用地址。

## 目录说明

- `src/api/` — 后端接口封装
- `src/views/` — 页面：`Index`（首页）、`ChatPage`（通用对话）、`CustomerServiceChatPage`（知识库问答）
- `src/components/` — 通用组件，含流式 Markdown 渲染与 SVG 图标封装
- `src/stores/` — Pinia 状态（持久化）

## IDE 建议

[VS Code](https://code.visualstudio.com/) + [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar)（并禁用 Vetur）。
