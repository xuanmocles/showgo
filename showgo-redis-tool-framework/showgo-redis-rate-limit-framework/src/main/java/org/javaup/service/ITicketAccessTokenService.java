package org.javaup.service;

/**
 * 选座访问令牌：先领令牌再选座，把没有资格进入热链路的流量挡在入口之外。
 */
public interface ITicketAccessTokenService {

    /**
     * 令牌校验开关，关闭后所有请求直接放行。
     */
    boolean isEnabled();

    /**
     * 发放令牌，同一用户在同一场次重复领取返回同一令牌。
     */
    String issueAccessToken(Long sessionId, Long userId);

    /**
     * 校验并原子消费令牌，令牌只能使用一次。
     */
    boolean validateAndConsume(Long sessionId, Long userId, String token);
}
