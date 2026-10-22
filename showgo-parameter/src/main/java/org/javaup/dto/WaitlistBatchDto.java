package org.javaup.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量查询候补状态。
 */
@Data
public class WaitlistBatchDto {

    @NotEmpty(message = "场次列表不能为空")
    private List<Long> sessionIds;
}
