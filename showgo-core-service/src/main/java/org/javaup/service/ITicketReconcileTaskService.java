package org.javaup.service;

public interface ITicketReconcileTaskService {

    /** 全量对账 */
    void reconciliationTaskExecute();

    /** 单个场次对账 */
    void reconciliationTaskExecute(Long sessionId);
}
