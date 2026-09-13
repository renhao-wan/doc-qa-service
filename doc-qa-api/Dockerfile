# doc-qa-api 镜像：多阶段构建
#
# 构建：在本目录执行 docker build -t doc-qa-api .
#       或由仓库根目录的 docker compose 统一编排（推荐）
#
# ⚠️ 构建需要联网拉取基础镜像与 Maven 依赖，国内网络下首次构建可能较慢。
#    依赖层做了缓存（见下），后续只改源码时不会重新下载。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只拷 pom 并预拉依赖：这一层只依赖 pom.xml，源码改动不会让它失效。
# 放在 COPY src 之前是本文件唯一的构建优化，但收益最大——依赖下载占了构建时间的大头
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# ⚠️ 用通配符而不是写死 doc-qa-api-1.0.0.jar：项目版本来自 pom 的 ${revision} 属性，
#    改版本号时不必回来改这行（该属性一旦缺失，产物会变成 doc-qa-api-null.jar）
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy

# 时区。应用里大量使用 LocalDateTime.now()（走 JVM 默认时区），
# 与 docker-compose 中 postgres 服务的 TZ 保持一致，否则落库时间会差 8 小时。
# 基础镜像自带 tzdata；若发现容器内时间仍是 UTC，改用
# `apt-get install -y --no-install-recommends tzdata` 显式补上
ENV TZ=Asia/Shanghai

# 以非 root 用户运行。固定 uid/gid 为 1000，便于宿主机排查挂载文件的属主
RUN groupadd --system --gid 1000 app \
 && useradd --system --uid 1000 --gid app --create-home app

WORKDIR /app

# 只拷 jar，不带构建阶段的源码与 Maven 仓库
COPY --from=build /build/target/*.jar app.jar

# 知识库文件（data/files）与上传分片（data/chunks）的落盘目录，对应
# application-dev.yml 的 knowledge-base.file-storage-path / chunk-path（相对路径，基准是工作目录）。
# ⚠️ 必须在切用户之前建好并授权：应用[运行期]以 app 用户建目录会因权限不足失败，
#    表现为分片上传报 STORAGE_DIR_UNAVAILABLE。compose 会用数据卷覆盖这里的目录
RUN mkdir -p /app/data/files /app/data/chunks \
 && chown -R app:app /app

USER app

EXPOSE 8080

# 用 exec 形式（不是 shell 形式）：确保 JVM 是 PID 1，
# 才能收到 docker stop / compose down 发来的 SIGTERM 并优雅停机
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
