# 多阶段构建 askdata-server 可执行镜像
# 阶段一：编译（需要能拉取 maven 依赖；CI 建议挂载 ~/.m2 缓存以加速）
# 构建上下文必须为仓库根（docker build -f askdata/Dockerfile -t askdata-server:latest .）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
# 多模块项目指定模块与 -am（同时构建依赖模块）；产物 jar 拷到阶段根目录的 app.jar，
# 使下一阶段用相对路径 COPY 即可，不依赖具体工作目录名或模块 target 路径。
RUN mvn -pl askdata/askdata-server -am -DskipTests clean package \
 && cp askdata/askdata-server/target/askdata-server-*.jar app.jar

# 阶段二：运行（仅 JRE 17）
FROM eclipse-temurin:17-jre
WORKDIR /app
# 相对路径：相对构建阶段 WORKDIR(/build) 解析为 /build/app.jar
COPY --from=build app.jar /app/app.jar
EXPOSE 8000
# 配置经环境变量注入；元数据库/文件存储等持久化目录挂载到 /app/data
VOLUME /app/data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
