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
 * 演出，广播表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show")
public class Show implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String name;

    private Long venueId;

    /** 1演唱会 2话剧歌剧 3音乐节 4体育赛事 5脱口秀 6儿童亲子 */
    private Integer category;

    private String artist;

    private String poster;

    private String description;

    private Integer durationMinutes;

    /** 最低票价，单位分 */
    private Long minPrice;

    /** 1上架 2下架 3已结束 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 场馆名称，列表页展示用 */
    @TableField(exist = false)
    private String venueName;

    @TableField(exist = false)
    private String city;

    /** 可售场次 */
    @TableField(exist = false)
    private List<ShowSession> sessions;
}
