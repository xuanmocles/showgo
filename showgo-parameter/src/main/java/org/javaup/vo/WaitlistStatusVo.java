package org.javaup.vo;

import lombok.Data;

/**
 * 候补状态。
 */
@Data
public class WaitlistStatusVo {

    private Long sessionId;

    /** 0未候补 1候补中 2已补位成功 */
    private Integer status;

    private Integer expectCount;

    /** 前面还有多少人 */
    private Long aheadCount;
}
