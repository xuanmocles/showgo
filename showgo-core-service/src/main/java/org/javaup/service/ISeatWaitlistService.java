package org.javaup.service;

import org.javaup.dto.WaitlistDto;
import org.javaup.enums.BusinessType;
import org.javaup.vo.WaitlistStatusVo;

import java.util.List;

public interface ISeatWaitlistService {

    /** 登记候补排队 */
    WaitlistStatusVo joinWaitlist(WaitlistDto waitlistDto);

    /** 取消候补 */
    WaitlistStatusVo cancelWaitlist(Long sessionId);

    /** 查询单个场次的候补状态 */
    WaitlistStatusVo getStatus(Long sessionId);

    /** 批量查询候补状态 */
    List<WaitlistStatusVo> getStatusBatch(List<Long> sessionIds);

    /** 出票成功后把用户移出候补队列 */
    void removeFromWaitlist(Long sessionId, Long userId);

    /** 座位释放后按候补顺序自动补位：为最早候补的用户锁定座位并通知 */
    boolean autoIssueToEarliestCandidate(Long sessionId, List<Integer> freeIndexes, BusinessType businessType);
}
