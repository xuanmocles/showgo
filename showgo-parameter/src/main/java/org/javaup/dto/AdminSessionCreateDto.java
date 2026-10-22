package org.javaup.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端创建场次：场次 + 分区票档 + 自动生成座位。
 */
@Data
public class AdminSessionCreateDto {

    @NotNull(message = "演出不能为空")
    private Long showId;

    @NotBlank(message = "场次名称不能为空")
    private String sessionName;

    @NotNull(message = "开演时间不能为空")
    private LocalDateTime beginTime;

    @NotNull(message = "散场时间不能为空")
    private LocalDateTime endTime;

    @NotNull(message = "开售时间不能为空")
    private LocalDateTime saleBeginTime;

    @NotNull(message = "停售时间不能为空")
    private LocalDateTime saleEndTime;

    /** 1待开售 2售票中 3已售罄 4已结束 5已取消 */
    private Integer status = 1;

    @Valid
    @NotEmpty(message = "至少配置一个分区")
    private List<SeatAreaConfig> areas;

    /**
     * 分区配置：一个分区是 rowCount × seatPerRow 的座位矩阵。
     */
    @Data
    public static class SeatAreaConfig {

        @NotNull(message = "分区序号不能为空")
        @Min(value = 1, message = "分区序号从 1 开始")
        private Integer areaNo;

        @NotBlank(message = "分区编码不能为空")
        private String areaCode;

        @NotBlank(message = "分区名称不能为空")
        private String areaName;

        /** 1普通 2VIP 3无障碍 */
        private Integer areaType = 1;

        /** 单价，单位分 */
        @NotNull(message = "票价不能为空")
        @Min(value = 0, message = "票价不能为负")
        private Long price;

        private String color = "#409eff";

        @NotNull(message = "排数不能为空")
        @Min(value = 1, message = "排数至少为 1")
        @Max(value = 100, message = "排数不能超过 100")
        private Integer rowCount;

        @NotNull(message = "每排座位数不能为空")
        @Min(value = 1, message = "每排至少 1 个座位")
        @Max(value = 100, message = "每排不能超过 100 个座位")
        private Integer seatPerRow;
    }
}
