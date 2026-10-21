package com.xiongx9527.askdata.datasource.support;

/**
 * 应用启动期的一次性初始化器：把配置化的应用密钥推给静态工具
 * {@link DatasourceCrypto}。
 *
 * <p>存在的理由：{@code DatasourceCrypto} 是静态工具（既有 6 处调用点零改动），
 * 而应用密钥改为配置提供后需要把配置值注入其中。该初始化器在 Bean 装配阶段
 * （早于任何请求处理）完成注入，故运行期读到的始终是配置化密钥。</p>
 */
public class DatasourceCryptoInitializer {

    /** 无状态标记类，仅用于让 Spring 在装配阶段触发一次注入。 */
    public DatasourceCryptoInitializer() {
    }
}
