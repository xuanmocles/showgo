package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 场馆，广播表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_venue")
public class Venue implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String name;

    private String city;

    private String address;

    private Double longitude;

    private Double latitude;

    /** 1剧院 2体育馆 3livehouse 4体育场 */
    private Integer venueType;

    private Integer capacity;

    /** 1正常 0停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 距离，单位公里，仅用于「附近场馆」检索 */
    @TableField(exist = false)
    private Double distance;
}
