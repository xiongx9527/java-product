package com.xiongx9527.askdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 对话 / 重排模型故障转移（failover）配置：{@code askdata.ai.fallback.*}。
 *
 * <p>框架只提供「策略扩展点」与「可配置分类器机制」，<b>不替业务决定</b>哪些错误该重试 / 切换 / 终止；
 * 具体规则由本配置注入 {@code KeywordFailoverErrorClassifier}（产品侧决策）。</p>
 *
 * <p>错误类型可配置：每条形如 {@code 关键词:动作}，动作取 RETRY / FAILOVER / ABORT（缺省动作后缀视为 FAILOVER）。
 * 未命中任何关键词按 FAILOVER 处理（中性默认，兜底切下一候选）。</p>
 */
@Component
@ConfigurationProperties(prefix = "askdata.ai.fallback")
public class FallbackProperties {

    /** 是否启用多模型故障转移（关闭则退化为「仅主模型直连、不切换」）。 */
    private boolean enabled = true;

    /**
     * 错误 → 动作 映射（关键词匹配，大小写不敏感）。
     *
     * <p>默认：瞬态错误（限流 429 / 网关 5xx / 超时 / 连接复位）在同一候选上重试；
     * 鉴权 / 余额类（401 / 403 / insufficient / balance）切换候选；
     * 调用方自身错误（400 / bad_request）立即终止（重试 / 切换都无效）。</p>
     */
    private List<String> errorPolicies = Arrays.asList(
            "429:RETRY", "503:RETRY", "504:RETRY", "timeout:RETRY", "rate_limit:RETRY",
            "connection:RETRY", "reset:RETRY", "econnrefused:RETRY", "unavailable:RETRY",
            "insufficient:FAILOVER", "balance:FAILOVER", "401:FAILOVER", "403:FAILOVER",
            "400:ABORT", "bad_request:ABORT");

    /** 单候选内最大重试次数（瞬态错误的同一候选重试；0 不重试）。 */
    private int maxRetries = 1;

    /** 重试退避间隔（毫秒）。 */
    private long backoffMs = 200L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getErrorPolicies() {
        return errorPolicies == null ? Collections.<String>emptyList() : errorPolicies;
    }

    public void setErrorPolicies(List<String> errorPolicies) {
        this.errorPolicies = errorPolicies;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }
}
