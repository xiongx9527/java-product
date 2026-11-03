package com.xiongx9527.askdata.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后再执行副作用。
 *
 * <p>向量库删除不能放在业务事务内：业务回滚时向量已经删掉且无法恢复，会出现
 * 「行还在、向量没了」。改为提交后执行，最坏情况只是留下孤儿点（可由重建清理），
 * 不会丢数据。</p>
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** 有事务时注册 afterCommit 回调，无事务时立即执行。 */
    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
