# AGENTS.md

## 项目概览

本仓库 `New-java-product` 主要包含 `askdata` 相关子模块，是一套面向数据问答/智能查询场景的 Java 产品代码库。

当前重点模块：`askdata/askdata-server`，近期新增了 SQLBot MCP 兼容层（`SqlbotMcpCompatController`、配套测试）用于对接 SQLBot 的 MCP 工具调用。

## 仓库结构

```text
.
├── AGENTS.md                # Agent 指南（本文件）
├── README.md                # 项目根说明
├── askdata/                 # askdata 产品模块
│   ├── askdata-server/      # 后端服务主模块（Spring Boot）
│   │   ├── README.md
│   │   ├── src/main/java/.../mcp/        # MCP 相关控制器、适配器
│   │   └── src/test/java/.../mcp/        # MCP 相关单元/集成测试
│   └── ...                  # 其他 askdata 子模块（如有）
├── docs/                    # 项目文档
│   ├── comet/changes/       # Comet 变更产物（历史记录）
│   └── ... 
└── .comet/                  # Comet 工作流配置与运行态（如存在）
```

> 实际模块以 `pom.xml` 定义的 `<modules>` 为准。

## 技术栈

- **语言**：Java
- **构建工具**：Maven（多模块结构）
- **服务框架**：Spring Boot（见 `askdata/askdata-server/pom.xml`）
- **能力扩展**：MCP（Model Context Protocol）接口适配（SQLBot 工具名别名、响应字段兼容等）
- **文档**：Markdown

## 环境要求

- JDK（以各模块 `pom.xml` 中 `maven.compiler.source/target` 或 `java.version` 为准）
- Maven 3.8+
- 本地开发需可正常连接所依赖的外部服务（如有配置请参考对应模块 README）

## 构建、运行与测试

在仓库根目录或目标模块下使用 Maven：

```bash
# 全量编译
mvn clean compile

# 运行全量测试
mvn test

# 跳过测试打包
mvn clean package -DskipTests

# 仅构建 askdata-server
mvn -pl askdata/askdata-server -am clean package

# 仅运行 askdata-server 测试
mvn -pl askdata/askdata-server test

# 本地启动（具体以 Spring Boot 配置为准）
mvn -pl askdata/askdata-server spring-boot:run
```

> 如果项目使用 `mvnw`（Maven Wrapper），优先使用 `./mvnw` 保持构建环境一致。

## 代码规范

- **风格一致性**：遵循周边代码的命名、缩进、注释密度和既有惯用写法。新增代码不要另起一套风格。
- **模块边界**：优先复用已有的 service/adapter/util，避免跨模块随意耦合。新增 MCP 工具时优先走现有实现的委托而非重复逻辑（参考 `SqlbotMcpCompatController` 的别名映射思路）。
- **异常与日志**：沿用模块已有的异常体系与日志框架用法。
- **配置**：敏感信息不要硬编码，使用配置文件或环境变量。
- **测试**：改动接口/控制器时同步补充或更新单元测试。提交前确保相关测试可通过。
- **最小改动**：只改动与当前任务直接相关的文件，避免无关格式化。

## Git 与提交约定

- **分支**：日常基于 `main` 开发，必要时按团队约定新建 feature/fix 分支。
- **提交信息**：清晰描述变更目的（可参考近期提交风格，如 `feat: ...`、`docs: ...`、`chore: ...`）。
- **改动范围**：提交前用 `git diff --stat` 自检，确保不包含调试代码、临时日志或无关文件。
- **Co-author**：如使用 Claude Code 协作，按环境提示追加 `Co-Authored-By: ...`（仓库全局规则优先）。

## 文档与变更记录

- **模块 README**：涉及模块用法、配置、接口变更时同步更新 `askdata/askdata-server/README.md` 等对应 README。
- **审计/设计文档**：较大功能或兼容性改动可记录到 `docs/`，必要时关联到 `docs/comet/changes/`。
- **变更溯源**：保留足够上下文便于后续排查（接口字段、工具名映射、兼容范围等）。

## Comet 工作流（Ambient Resume）

本仓库内已接入 Comet Ambient Resume。处理改动或调查类任务前，Agent 应遵循该协议按需触发 `comet resume-probe` 并进入对应 Skill，而不是随意切换 workflow。

- **显式调用优先**：若用户已通过宿主显式调用任意 Comet Skill（`/comet`、`/comet-native`、`/comet-hotfix` 等），直接进入该 Skill，不运行 probe。
- **非 Comet Skill 优先**：若用户显式调用非 Comet 的 Skill/斜杠命令，按该命令意图执行，不运行 probe。
- **流程内回复**：已处于 Comet 流程中等待用户选择/确认时，将回复视为当前 change 的继续，直接推进。
- **严格按探针结果**：仅信任 `workflow`、`skill`、`entrySource`。`auto_resume` -> 进入 `nextCommand` 指向的入口；`ask_user` -> 仅简短提问并等待；`out_of_scope`/`none` -> 不进入 Comet workflow，但不影响已在进行的流程。
- **不强行挂载**：不能仅因存在 active change 就将无关任务归入该 change。

<comet-ambient-resume>
<!-- Managed by Comet. Edits inside this block may be replaced by comet init/update. -->
<!-- Contract: comet.resume_probe.v2 -->

## Comet Ambient Resume

在这个仓库中，开始处理需要改动或调查的任务前，如果可能存在活跃 Comet workflow，把当前用户请求传入只读探针：`comet resume-probe . --stdin --json`。

- 如果用户通过宿主明确调用任意 Comet Skill（例如 `@comet`、`/comet`、`@comet-native` 或 `/comet-hotfix`），显式调用优先于本恢复协议；不要运行 resume probe，直接进入被调用的 Skill。
- 如果用户通过宿主明确调用的是非 Comet 的 Skill 或斜杠命令，任务意图已由该调用明确：不要运行 resume probe，直接执行该 Skill。
- 如果你正在 Comet 流程内（包括正在等待用户回复你在流程中提出的问题），不要运行 resume probe；把这类回复（例如方案/选项选择）当作当前 change 的继续，直接按用户的选择推进。
- 只信任返回的 `workflow`、`skill` 和 `entrySource`；它们只由项目配置或无配置兼容回退决定。不得扫描或切换另一套 workflow。
- 如果 probe 返回 `auto_resume`，简短说明选中的 active change，并进入 `nextCommand` 指向的永久入口。不要把状态命令当作恢复入口直接推进。
- 如果 probe 返回 `ask_user`，只问一个简短问题并等待用户回复。
- 如果当前请求未明确调用 Comet Skill，且 probe 返回 `out_of_scope` 或 `none`，不要进入 Comet workflow。
- `out_of_scope` 或 `none` 只表示不要因为这个新请求进入 Comet workflow；它绝不表示要暂停或退出一个已在进行的 Comet 流程。
- 如果配置或状态无效且没有 `nextCommand`，停止并报告原因；不要猜测另一个 workflow。
- 不能只因为存在 active change 就把无关任务挂到该 change。Native 的未提交改动由 Native 入口检查，不由探针自动归因。
</comet-ambient-resume>

## Agent 工作注意事项

- **先读再改**：修改前先阅读目标文件及其临近实现，复用已有模式和工具类，避免发明新的抽象。
- **关注 MCP 兼容**：若涉及 `mcp` 包下的控制器/适配，需同时考虑既有实现、SQLBot 工具名映射及响应字段兼容性（可参考最新提交 `e2d7d1e` 的改动思路）。
- **最小验证**：完成改动后优先跑与变更最相关的测试（如 `-Dtest=SqlbotMcpCompatControllerTest`），再视情况跑全量测试。
- **文档同步**：接口路径、工具列表、请求/响应字段有变化时，及时更新模块 README 或相关文档。
- **安全与合规**：不要提交密钥、token、证书等敏感信息。遵循仓库现有的安全约束。
- **利用既有上下文**：本仓库已有 `docs/comet/changes/` 历史产物，可作为兼容性改动的参考案例，但不要照搬过时约定，需结合当前代码实际情况判断。
