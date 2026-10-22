package org.javaup.ratelimit.extension;

import lombok.Data;

import java.util.List;

/**
 * @description: 限流执行上下文
 **/
@Data
public class RateLimitContext {

    private Long sessionId;
    private Long userId;
    private String clientIp;

    private List<String> keys;
    private boolean useSliding;

    private int ipWindowMillis;
    private int ipMaxAttempts;
    private int userWindowMillis;
    private int userMaxAttempts;

    private Integer result;

    public RateLimitContext() {}

    public RateLimitContext(Long sessionId, Long userId, String clientIp,
                            List<String> keys, boolean useSliding,
                            int ipWindowMillis, int ipMaxAttempts,
                            int userWindowMillis, int userMaxAttempts) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.clientIp = clientIp;
        this.keys = keys;
        this.useSliding = useSliding;
        this.ipWindowMillis = ipWindowMillis;
        this.ipMaxAttempts = ipMaxAttempts;
        this.userWindowMillis = userWindowMillis;
        this.userMaxAttempts = userMaxAttempts;
    }
}