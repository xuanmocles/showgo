package org.javaup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 演出列表查询。
 */
@Data
public class ShowQueryDto {

    /** 分类，为空表示全部 */
    private Integer category;

    /** 城市，为空表示全部 */
    private String city;

    /** 关键词，匹配演出名或艺人 */
    private String keyword;

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(50)
    private Integer pageSize = 10;
}
