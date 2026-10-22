package org.javaup.service;

import org.javaup.entity.RollbackFailureLog;

public interface IRollbackAlertService {

    /** 回滚重试耗尽后的告警出口（可对接钉钉/企业微信） */
    void sendRollbackAlert(RollbackFailureLog failureLog);
}
