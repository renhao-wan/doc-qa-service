# doc-qa-web 镜像：多阶段构建（node 构建 -> nginx 托管）
#
# 构建：在本目录执行 docker build -t doc-qa-web .
#       或由仓库根目录的 docker compose 统一编排（推荐）

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build

WORKDIR /build

# 先只拷清单并装依赖：这一层只依赖 package.json / package-lock.json，
# 源码改动不会让它失效，重新构建时能跳过整个 npm 安装
# ⚠️ 用 npm ci 而不是 npm install：前者严格按 lock 文件安装、结果可复现，
#    后者会顺带改写 lock 文件，构建产物随机器而变
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 静态文件与后端反代配置。nginx.conf 的 /api 反代目标是 http://app:8080，
# 其中 app 是 compose 里的服务名，依赖容器同处 doc-qa-net 网络
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
