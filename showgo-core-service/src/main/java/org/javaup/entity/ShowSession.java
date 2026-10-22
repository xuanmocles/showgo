package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 演出场次，库存与座位图的最小业务边界。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_session")
public class ShowSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long showId;

    private Long venueId;

    private String sessionName;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime saleBeginTime;

    private LocalDateTime saleEndTime;

    private Integer seatTotal;

    private Integer seatAvailable;

    /** 1待开售 2售票中 3已售罄 4已结束 5已取消 */
    private Integer status;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String showName;

    @TableField(exist = false)
    private String venueName;

    @TableField(exist = false)
    private List<SeatArea> areas;
}
