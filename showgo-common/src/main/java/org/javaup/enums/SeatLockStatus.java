package org.javaup.enums;

import lombok.Getter;

/**
 * 锁座流水状态。
 */
public enum SeatLockStatus {

    LOCKED(1, "锁定中"),

    ORDERED(2, "已下单"),

    RELEASED(3, "主动释放"),

    TIMEOUT_RELEASED(4, "超时释放"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    SeatLockStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static SeatLockStatus getRc(Integer code) {
        for (SeatLockStatus re : SeatLockStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
