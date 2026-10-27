---
generated_from_state_version: 1
---

# 验证

## 当前结果

- 结果: **已归档**
- 验证情况: **已完成检查，验证结果已确认**
- 目标周期: 1
- 迭代: 1
- 验证器尝试次数: 1
- 完成时间: 2026-10-27T03:59:02.029Z
- 摘要: 独立复核 i18n-config：六份消息包键集合已对齐（各 392 键，无缺失/多余），I18nBundleKeyParityTest 直接按文件比对可防止父包回退假绿；DynamicDatasourceResolver 两处硬编码异常已改走 MessageResolver 且 I18nMessageConvergenceTest 通过；A27 配置项（向量阈值、样例行开关、元数据查询开关、图表 TTL/清理、许可证与嵌入签名）均已声明默认值并有代码读取点，A27ConfigCompletenessTest 覆盖。i18n-config-checks 全绿（exit 0，20.5s）。

## 验收

| 编号 | 结果 | 来源 | 验收项 | 原因 |
| --- | --- | --- | --- | --- |
| A26 | passed | brief.md | A26 i18n 五语种：`zh-CN`/`en`/`zh-TW`/`ko-KR`/`th-TH` 五个 locale 的键集合与使用点一致，四语种不再回退中文/英文；工作空间名等 Java 新增文案全部有对应翻译，缺失键有自动检查。 | zh-CN/en/zh-TW/ko-KR/th-TH 与缺省包键集合一致（392 键）；新增 I18nBundleKeyParityTest 按文件比对并断言英文不再等于中文；I18nA9Test(7)/I18nMessageConvergenceTest(3) 通过，硬编码文案归零。 |
| A27 | passed | brief.md | A27 配置项补齐：`application.yaml` 补齐向量召回阈值、`SAMPLE_DATA_ENABLED`、`ALLOW_METADATA_QUERIES`、token 有效期、图表图片 TTL/清理、许可证/嵌入签名开关等配置项，均有默认值与文档；未配置时行为与默认值一致。 | application.yaml 声明 askdata.vector.recall-threshold=0.4、prompt.sample-data-enabled=true、sql.allow-metadata-queries=false、chart.image-ttl-ms/cleanup-interval-ms/token-store-dir、license.enforcement/sign-key/max-count、embedded.aes-key/legacy-hmac-enabled 与 sa-token.timeout=691200；SqlSafety 缺省拒绝系统目录查询，SqlSafetySettings 启动期注入；A27ConfigCompletenessTest(4) 覆盖默认值与读取点。 |

## 检查

| 检查 | 命令 | 工作目录 | 状态 | 退出码 | 耗时 |
| --- | --- | --- | --- | ---: | ---: |
| i18n 键集合与 A27 配置项聚焦测试 | -Dmaven.test.skip=false -Dtest=I18nA9Test,I18nMessageConvergenceTest,I18nBundleKeyParityTest,A27ConfigCompletenessTest,SqlSafetyTest,SqlSafetyParserTest -DfailIfNoTests=false test | . | passed | 0 | 20562 ms |

### Builder 报告的证据

以下为 Builder 报告，不等同于 Runtime 检查凭据或独立验收结果。

- i18n 键集合与 A27 配置项聚焦测试: passed — —

## 阻塞项

_无。_

## 风险与跳过的工作

_未报告风险。_

## 之前的迭代

_没有之前的迭代。_



## 结论

独立复核 i18n-config：六份消息包键集合已对齐（各 392 键，无缺失/多余），I18nBundleKeyParityTest 直接按文件比对可防止父包回退假绿；DynamicDatasourceResolver 两处硬编码异常已改走 MessageResolver 且 I18nMessageConvergenceTest 通过；A27 配置项（向量阈值、样例行开关、元数据查询开关、图表 TTL/清理、许可证与嵌入签名）均已声明默认值并有代码读取点，A27ConfigCompletenessTest 覆盖。i18n-config-checks 全绿（exit 0，20.5s）。
