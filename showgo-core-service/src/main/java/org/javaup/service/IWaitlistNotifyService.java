package org.javaup.service;

import java.util.List;

public interface IWaitlistNotifyService {

    /** 通知用户候选成功（带去重，避免重复打扰） */
    void sendWaitlistNotify(Long sessionId, Long userId, List<String> seatCodes);
}
