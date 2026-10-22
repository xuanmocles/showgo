package org.javaup.lua;

import lombok.Data;

/**
 * 座位类 Lua 脚本的统一返回结构。
 */
@Data
public class SeatOperateResult {

    private Integer code;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;
}
