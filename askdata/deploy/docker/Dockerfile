# 多阶段构建 askdata-server 可执行镜像
# 平台线：Java 8 / Spring Boot 2.7.18 / fw-plus 0.1.1（dev 分支）
#
# 构建上下文必须为仓库根：
#   docker buildx build -f askdata/deploy/docker/Dockerfile \
#     --build-context m2repo="/Users/xiongxiao/Workspace/Idea/maven/repo" \
#     -t askdata-server:latest .
#   （路径以本机 ~/.m2/settings.xml 的 localRepository 为准；默认 ~/.m2/repository 可能没有 fw-plus 构件）
#
# 两个前置条件：
# 1) 父 POM com.xiongx9527.fw.plus:fw-plus-ai-build:0.1.1-SNAPSHOT 与 fw-plus-* 构件不在本仓库，
#    需先在 java-framework 的 dev 分支安装到本地仓库：
#      cd ../java-framework && mvn install -Drevision=0.1.1-SNAPSHOT -DskipTests
#    容器内构建复用这份仓库，因此用命名构建上下文 m2repo 把宿主本地仓库挂进来
#    （必须是可写绑定：Maven 会写 _remote.repositories / lastUpdated 等元数据）。
# 2) 前端产物 askdata/askdata-server/web/dist 需预先构建好（pom 会把 web/dist 拷进 static/）。
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY . .
# 前置：宿主已 npx vite build 生成 web/dist（容器内不跑 npm run build——它含 vue-tsc 类型检查，
# 且 .dockerignore 排除了 node_modules，容器内 npm install 需联网）。-Dskip.npm 跳过 frontend plugin，
# copy-frontend-dist 会把 web/dist 拷进 static。
# m2repo 是命名构建上下文（宿主 ~/.m2/repository）；bind 挂载只读，需先拷成可写副本供 Maven 写元数据。
RUN --mount=type=bind,from=m2repo,target=/m2seed \
    mkdir -p /root/.m2/repository \
 && cp -a /m2seed/. /root/.m2/repository/ \
 && mvn -B -pl askdata/askdata-server -am -Drevision=0.1.1-SNAPSHOT -DskipTests -Dskip.npm=true clean package \
 && cp askdata/askdata-server/target/askdata-server.jar /build/app.jar

# 阶段二：运行（仅 JRE 8）
FROM eclipse-temurin:8-jre
# Java 8 的默认字符集取自 locale；不显式设为 UTF-8 时中文响应/日志会按 ANSI_X3.4-1968 输出。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
EXPOSE 19528
# 配置经环境变量注入；元数据库/文件存储等持久化目录挂载到 /app/data
VOLUME /app/data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
