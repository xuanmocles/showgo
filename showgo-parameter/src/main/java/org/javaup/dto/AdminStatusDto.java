package org.javaup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端修改状态（演出上下架 / 场次开停售）。
 */
@Data
public class AdminStatusDto {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
