package org.javaup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 候补排队：场次售罄后登记，有人退票时按排队顺序自动补位。
 */
@Data
public class WaitlistDto {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    /** 期望连座数量 */
    private Integer expectCount = 1;
}
