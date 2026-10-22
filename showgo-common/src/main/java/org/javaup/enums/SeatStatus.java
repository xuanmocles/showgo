package org.javaup.enums;

import lombok.Getter;

/**
 * 座位状态，与 Redis 座位图中每座位 2bit 的取值一一对应。
 */
public enum SeatStatus {

    AVAILABLE(0, "可售"),

    LOCKED(1, "锁定中"),

    SOLD(2, "已售出"),

    DISABLED(3, "不可用"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    SeatStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static SeatStatus getRc(Integer code) {
        for (SeatStatus re : SeatStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
