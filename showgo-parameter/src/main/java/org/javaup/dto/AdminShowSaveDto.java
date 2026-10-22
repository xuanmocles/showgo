package org.javaup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端新增 / 编辑演出。
 */
@Data
public class AdminShowSaveDto {

    /** 为空表示新增 */
    private Long id;

    @NotBlank(message = "演出名称不能为空")
    private String name;

    @NotNull(message = "场馆不能为空")
    private Long venueId;

    @NotNull(message = "分类不能为空")
    private Integer category;

    private String artist;

    private String poster;

    private String description;

    private Integer durationMinutes = 120;

    /** 最低票价，单位分 */
    private Long minPrice = 0L;

    /** 1上架 2下架 3已结束 */
    private Integer status = 1;
}
