package org.javaup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 连座推荐请求。
 */
@Data
public class SeatRecommendDto {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    /** 指定分区，为空则从全场最优区域找 */
    private Long areaId;

    @Min(1)
    @Max(6)
    private Integer count = 2;
}
