package org.javaup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理端演出列表查询。
 */
@Data
public class AdminShowQueryDto {

    private String keyword;

    private Integer status;

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 10;
}
