# askdata-server（问数 · 单体后端）

Java 17 + Spring Boot 3.5.8 单体工程；前端（SQLBot frontend 拷贝）作为嵌套 npm 工程
放在 `web/`，其构建产物 `web/dist` 经 Maven 资源目录挂入 `BOOT-INF/classes/static`，
实现单 jar 前后端同进程托管（端口 8000）。

## 构建

```bash
# 仓库根（三级 POM：根聚合 askdata-root → askdata → askdata-server）
mvn -Pskip-test package
# 产物：askdata/askdata-server/target/askdata-server.jar
```

依赖版本管理：

- fw-plus BOM（import）：`com.xiongx9527.fw.plus.boot:fw-plus-boot-dependencies:0.1.1-SNAPSHOT`
- fw-plus AI BOM（import）：`com.xiongx9527.fw.plus.ai:fw-plus-ai-boot-dependencies:0.1.1-SNAPSHOT`
- 鉴权（D10）：`com.xiongx9527.fw.plus:fw-plus-auth-satoken`（Sa-Token 1.46.0）

## 前端

```bash
cd web
npm install
npm run build   # 产物 dist/ → Maven 打包时挂入 static
# 开发模式（Vite dev server:5173，/api/v1 代理到 127.0.0.1:8000）
npm run dev
```

xpack 闭源调用点的本地补丁说明见 `web/XPackPatchNotes.md`（统一 `[askdata-xpack-patch]` 标记）。

## 运行与验证（登录闭环 A3）

```bash
java -jar target/askdata-server.jar
# 访问 http://localhost:8000 → 登录页
# 种子管理员：admin / SQLBot@123456（MD5 口令落库）
```

元数据库：H2 文件库 `./data/askdata`（首次启动自动建表 + 种子，见 `src/main/resources/schema.sql`）。
